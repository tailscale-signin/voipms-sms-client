package net.kourlas.voipms_sms.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import java.nio.ByteOrder

object ConversationSpeech {
    const val RESULT = "reviewed_text"
    fun dictate(context: Context) = Intent(context, SpeechReviewActivity::class.java)
    fun transcribe(activity: AppCompatActivity, uri: Uri) {
        if (uri.scheme == "content") activity.startActivity(Intent(activity, SpeechReviewActivity::class.java).setData(uri))
    }
}

class SpeechReviewActivity : AppCompatActivity(), RecognitionListener {
    private var engine: SpeechRecognizer? = null
    private var pipe: ParcelFileDescriptor? = null
    private var writer: ParcelFileDescriptor? = null
    private var encoding: kotlinx.coroutines.Job? = null
    private lateinit var status: TextView
    private lateinit var review: EditText
    private lateinit var action: Button
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) recognize() else status.setText(R.string.tools_microphone_denied)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(if (intent.data == null) R.string.tools_dictate else R.string.tools_transcribe)
        status = TextView(this)
        review = EditText(this).apply { setHint(R.string.tools_transcript); minLines = 3 }
        action = Button(this).apply { setText(R.string.tools_start_listening); setOnClickListener {
            if (engine != null) {
                if (intent.data == null) engine?.stopListening()
                else { reset(); status.setText(R.string.tools_audio_prompt) }
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this@SpeechReviewActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recognize()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        } }
        if (intent.data != null) action.setText(R.string.tools_transcribe)
        val finish = Button(this).apply { setText(if (intent.data == null) R.string.tools_use_draft else android.R.string.ok); setOnClickListener {
            if (intent.data == null && review.text.isNotBlank()) setResult(RESULT_OK, Intent().putExtra(ConversationSpeech.RESULT, review.text.toString()).putExtra("thread", intent.getStringExtra("thread")))
            finish()
        } }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(status); addView(review); addView(action); addView(finish)
        }
        setContentView(android.widget.ScrollView(this).apply { addView(box) })
        status.setText(if (intent.data == null) R.string.tools_speech_prompt else R.string.tools_audio_prompt)
        if (Build.VERSION.SDK_INT < 31 || (intent.data != null && Build.VERSION.SDK_INT < 33) ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            action.isEnabled = false
            status.setText(R.string.tools_speech_unavailable)
        }
        review.setText(savedInstanceState?.getString("text").orEmpty())
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("text", review.text.toString())
        super.onSaveInstanceState(outState)
    }
    private fun recognize() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return
        reset()
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, resources.configuration.locales[0].toLanguageTag())
        val uri = intent.data
        try {
            if (uri != null) {
                if (Build.VERSION.SDK_INT < 33) return
                val descriptors = ParcelFileDescriptor.createPipe()
                pipe = descriptors[0]
                writer = descriptors[1]
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptors[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                encoding = lifecycleScope.launch {
                    try { withContext(Dispatchers.IO) { decode(uri, descriptors[1]) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { reset(); status.setText(R.string.tools_speech_failed) }
                }
            }
            engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            engine?.setRecognitionListener(this)
            engine?.startListening(request)
            action.setText(R.string.tools_stop)
            action.isEnabled = true
            status.setText(if (uri == null) R.string.tools_listening else R.string.tools_transcribing)
        } catch (_: RuntimeException) { reset(); status.setText(R.string.tools_speech_failed) }
    }
    /** Decode the attachment to signed 16-bit mono PCM before passing it to the on-device service. */
    private suspend fun decode(uri: Uri, target: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseOutputStream(target).use { out ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(this, uri, null)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    var inputDone = false
                    var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(rate > 0 && channels in 1..32)
                    var phase = 0
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (!inputDone) {
                            val ix = codec.dequeueInputBuffer(10000)
                            if (ix >= 0) {
                                val input = requireNotNull(codec.getInputBuffer(ix))
                                val size = extractor.readSampleData(input, 0)
                                inputDone = size < 0
                                codec.queueInputBuffer(ix, 0, maxOf(0, size), if (inputDone) 0 else extractor.sampleTime, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                if (!inputDone) extractor.advance()
                            }
                        }
                        val ix = codec.dequeueOutputBuffer(info, 10000)
                        if (ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val output = codec.outputFormat
                            rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            require(rate > 0 && channels in 1..32)
                            require(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) || output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                        } else if (ix >= 0) {
                            val data = requireNotNull(codec.getOutputBuffer(ix)).order(ByteOrder.nativeOrder())
                            data.position(info.offset); data.limit(info.offset + info.size)
                            val bytes = java.io.ByteArrayOutputStream()
                            while (data.remaining() >= channels * 2) {
                                var sum = 0
                                repeat(channels) { sum += data.short.toInt() }
                                val sample = sum / channels
                                phase += 16000
                                while (phase >= rate) { bytes.write(sample and 255); bytes.write((sample shr 8) and 255); phase -= rate }
                            }
                            bytes.writeTo(out)
                            codec.releaseOutputBuffer(ix, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                } finally { codec.release() }
            } finally { extractor.release() }
        }
    }
    private fun reset() {
        runCatching { engine?.cancel(); engine?.destroy() }; engine = null
        runCatching { pipe?.close() }; pipe = null
        runCatching { writer?.close() }; writer = null
        encoding?.cancel(); encoding = null
        action.isEnabled = true
        action.setText(if (intent.data == null) R.string.tools_start_listening else R.string.tools_transcribe)
    }
    override fun onStop() {
        if (engine != null) reset()
        if ((intent.getBooleanExtra("private", false) || intent.data != null) && !isChangingConfigurations) finish()
        super.onStop()
    }
    override fun onDestroy() { reset(); super.onDestroy() }
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        reset()
        if (text.isNotBlank()) { review.setText(text); status.setText(R.string.tools_review) }
        else status.setText(R.string.tools_speech_failed)
    }
    override fun onError(error: Int) { reset(); status.setText(R.string.tools_speech_failed) }
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
