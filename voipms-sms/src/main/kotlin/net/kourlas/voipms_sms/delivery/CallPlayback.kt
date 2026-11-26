package net.kourlas.voipms_sms.delivery

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.MediaDataSource
import android.media.AudioManager
import android.os.Build
import android.media.MediaPlayer
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import java.io.IOException
import java.lang.ref.WeakReference
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import kotlin.math.roundToInt

internal fun Context.dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

internal enum class PlayState { READY, LOADING, PLAYING, PAUSED, UNAVAILABLE }

/** Bounded provider audio stays in memory. Reads occur on Android's decoder thread. */
internal class VoicemailDataSource(private var bytes: ByteArray?) : MediaDataSource() {
    @Synchronized override fun getSize(): Long = (bytes ?: throw IOException("Closed")).size.toLong()
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val source = bytes ?: throw IOException("Closed")
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) throw IOException("Invalid read")
        if (size == 0) return 0
        if (position >= source.size) return -1
        val count = minOf(size, source.size - position.toInt())
        source.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }
    @Synchronized override fun close() { bytes?.fill(0); bytes = null }
}

internal class VoicePlaybackModel : ViewModel() {
    val state = MutableStateFlow("" to PlayState.READY)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var source: VoicemailDataSource? = null
    private var owner: WeakReference<FragmentActivity>? = null
    private var activeGeneration = -1L
    private var activeLines = emptySet<String>()
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusSerial = 0L
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val observer = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { stop() }
        override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            if (this@VoicePlaybackModel.owner?.get() === owner) this@VoicePlaybackModel.owner = null
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        focusSerial++
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        else focusListener?.let { manager.abandonAudioFocus(it) }
        focusRequest = null
        focusListener = null
        audio = null
    }

    @Suppress("DEPRECATION")
    private fun gainFocus(activity: FragmentActivity): Boolean {
        abandonFocus()
        val manager = activity.getSystemService(AudioManager::class.java)
        audio = manager
        val serial = focusSerial
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (serial != focusSerial) return@OnAudioFocusChangeListener
            if (change == AudioManager.AUDIOFOCUS_LOSS) stop() else if (change < 0) pause()
        }
        focusListener = listener
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener).setWillPauseWhenDucked(true).build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) abandonFocus()
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun allowed(activity: FragmentActivity, item: CallFeedItem): Boolean {
        if (readAccountCredentials(activity)?.generation != activeGeneration || activeLines.isEmpty()) return false
        val selected = getDids(activity, onlyRetrieveMessages = true)
        return activeLines.all { line ->
            val thread = ConversationId(line, item.peer)
            line in selected && (!PrivateThreads.isPrivate(activity, thread) ||
                (activity is ConversationActivity && item.did == line && activity.conversationId == thread))
        }
    }

    fun play(activity: FragmentActivity, item: CallFeedItem) {
        val account = readAccountCredentials(activity)?.takeIf { it.configured } ?: return
        if (!item.voicemail) return
        if (owner?.get() !== activity) {
            stop()
            owner?.get()?.lifecycle?.removeObserver(observer)
            owner = WeakReference(activity)
            activity.lifecycle.addObserver(observer)
        }
        if (state.value == (item.id to PlayState.PAUSED)) {
            val recording = player ?: return stop()
            if (!allowed(activity, item)) return stop()
            if (gainFocus(activity)) {
                try { recording.start(); state.value = item.id to PlayState.PLAYING }
                catch (_: IllegalStateException) { failure(activity) }
            }
            return
        }
        stop()
        activeGeneration = account.generation
        state.value = item.id to PlayState.LOADING
        job = activity.lifecycleScope.launch {
            try {
                val box = CallHistory.mailboxes(account, getDids(activity, onlyRetrieveMessages = true))[item.mailbox]
                if (box == null || box.did != item.did ||
                    (item.did.isEmpty() && item.mailboxLines != box.lines)) return@launch failure(activity)
                activeLines = box.lines
                if (!allowed(activity, item)) return@launch stop()
                val bytes = CallHistory.audio(account, item)
                if (!allowed(activity, item)) { bytes.fill(0); return@launch stop() }
                val recording = MediaPlayer()
                player = recording
                val recordingSource = VoicemailDataSource(bytes)
                source = recordingSource
                recording.setAudioAttributes(attributes)
                recording.setDataSource(recordingSource)
                recording.setOnPreparedListener { ready ->
                    if (player !== ready) return@setOnPreparedListener
                    if (!allowed(activity, item) || !gainFocus(activity)) return@setOnPreparedListener stop()
                    try { ready.start(); state.value = item.id to PlayState.PLAYING }
                    catch (_: IllegalStateException) { failure(activity) }
                }
                recording.setOnCompletionListener { completed -> if (player === completed) stop() }
                recording.setOnErrorListener { failed, _, _ -> if (player === failed) failure(activity); true }
                recording.prepareAsync()
            } catch (e: CancellationException) { throw e }
            catch (_: RecordingUnavailable) {
                stop()
                state.value = item.id to PlayState.UNAVAILABLE
            }
            catch (_: Exception) { failure(activity) }
        }
    }

    private fun failure(activity: FragmentActivity) {
        stop()
        Toast.makeText(activity, R.string.calls_audio_failed, Toast.LENGTH_LONG).show()
    }
    fun pause() {
        if (state.value.second != PlayState.PLAYING) return
        try { player?.pause(); state.value = state.value.first to PlayState.PAUSED }
        catch (_: IllegalStateException) { stop() }
        abandonFocus()
    }
    fun stop() {
        job?.cancel()
        job = null
        val recording = player
        player = null
        recording?.setOnPreparedListener(null)
        recording?.setOnCompletionListener(null)
        recording?.setOnErrorListener(null)
        recording?.release()
        source?.close()
        source = null
        abandonFocus()
        activeGeneration = -1L
        activeLines = emptySet()
        state.value = "" to PlayState.READY
    }
    override fun onCleared() { stop() }
}

internal object CallPlayback {
    fun controls(activity: FragmentActivity, event: CallFeedItem): View {
        val model = ViewModelProvider(activity)[VoicePlaybackModel::class.java]
        val row = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun button(icon: Int, label: Int, action: () -> Unit): ImageButton {
            val button = ImageButton(activity).apply {
                setImageResource(icon); contentDescription = activity.getString(label)
                val res = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,res,true)
                setBackgroundResource(res.resourceId)
                imageTintList = androidx.appcompat.content.res.AppCompatResources.getColorStateList(context,R.color.primary)
                setOnClickListener { action() }
            }
            row.addView(button,activity.dp(48),activity.dp(48)); return button
        }
        val play = button(R.drawable.ic_vm_play,R.string.calls_play) { model.play(activity,event) }
        val pause = button(R.drawable.ic_vm_pause,R.string.calls_pause,model::pause)
        val stop = button(R.drawable.ic_vm_stop,R.string.calls_stop,model::stop)
        val label = TextView(activity).apply { textSize=12f; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        row.addView(label)
        var observation: Job? = null
        fun update(id: String,s: PlayState) {
            val state = if (id == event.id) s else PlayState.READY
            play.isEnabled = state == PlayState.READY || state == PlayState.PAUSED || state == PlayState.UNAVAILABLE
            pause.isEnabled = state == PlayState.PLAYING
            stop.isEnabled = state != PlayState.READY && state != PlayState.UNAVAILABLE
            for (button in listOf(play,pause,stop)) button.alpha = if(button.isEnabled) 1f else .32f
            label.text = when(state) { PlayState.READY -> ""; PlayState.LOADING -> activity.getString(R.string.calls_buffering)
                PlayState.PLAYING -> activity.getString(R.string.calls_playing); PlayState.PAUSED -> activity.getString(R.string.calls_paused)
                PlayState.UNAVAILABLE -> activity.getString(R.string.calls_recording_unavailable) }
        }
        update(model.state.value.first,model.state.value.second)
        row.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) { observation = activity.lifecycleScope.launch { model.state.collect { (id,s) -> update(id,s) } } }
            override fun onViewDetachedFromWindow(v:View) { observation?.cancel() }
        })
        return row
    }
}
