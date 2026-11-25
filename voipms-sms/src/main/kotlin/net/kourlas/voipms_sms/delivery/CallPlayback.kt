package net.kourlas.voipms_sms.delivery

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import java.io.File
import kotlin.math.roundToInt

internal fun Context.dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

internal enum class PlayState { READY, LOADING, PLAYING, PAUSED }

internal class VoicePlaybackModel : ViewModel() {
    val state = MutableStateFlow("" to PlayState.READY)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var owner: FragmentActivity? = null
    private var audio: AudioManager? = null
    private val focus = AudioManager.OnAudioFocusChangeListener { change ->
        if(change == AudioManager.AUDIOFOCUS_LOSS) stop() else if(change < 0) pause()
    }
    private val observer = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { stop() }
        override fun onDestroy(owner: LifecycleOwner) { owner.lifecycle.removeObserver(this); this@VoicePlaybackModel.owner = null }
    }

    @Suppress("DEPRECATION")
    fun play(activity: FragmentActivity, item: CallFeedItem) {
        val account = readAccountCredentials(activity)?.takeIf { it.configured } ?: return
        if (item.did.isNotEmpty() && item.did !in getDids(activity)) return
        if (owner !== activity) {
            owner?.lifecycle?.removeObserver(observer); owner = activity; activity.lifecycle.addObserver(observer)
        }
        fun gainFocus(): Boolean {
            audio = activity.getSystemService(AudioManager::class.java)
            return audio?.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (state.value == (item.id to PlayState.PAUSED)) {
            if (gainFocus()) { player?.start(); state.value = item.id to PlayState.PLAYING }
            return
        }
        stop()
        state.value = item.id to PlayState.LOADING
        job = activity.lifecycleScope.launch {
            try {
                val box = CallHistory.mailboxes(account,getDids(activity, onlyRetrieveMessages = true))[item.mailbox]
                if (box == null || box.did != item.did) { failure(activity); return@launch }
                val bytes = CallHistory.audio(account,item)
                if (readAccountCredentials(activity)?.generation != account.generation) { stop(); return@launch }
                val path = File.createTempFile("voicemail-", ".audio", activity.cacheDir)
                file = path
                withContext(Dispatchers.IO) { path.writeBytes(bytes) }
                val device = MediaPlayer()
                player = device
                device.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                device.setDataSource(path.absolutePath)
                device.setOnPreparedListener {
                    if (readAccountCredentials(activity)?.generation == account.generation && gainFocus()) {
                        it.start(); state.value = item.id to PlayState.PLAYING
                    } else stop()
                }
                device.setOnCompletionListener { stop() }
                device.setOnErrorListener { _, _, _ -> failure(activity); true }
                device.prepareAsync()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure(activity) }
        }
    }
    private fun failure(activity: FragmentActivity) {
        stop()
        Toast.makeText(activity,R.string.calls_audio_failed,Toast.LENGTH_LONG).show()
    }
    fun pause() {
        if (state.value.second == PlayState.PLAYING) {
            player?.pause(); state.value = state.value.first to PlayState.PAUSED
        }
    }
    @Suppress("DEPRECATION")
    fun stop() {
        job?.cancel(); job=null
        player?.release(); player=null
        file?.delete(); file=null
        audio?.abandonAudioFocus(focus); audio=null
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
            play.isEnabled = state == PlayState.READY || state == PlayState.PAUSED
            pause.isEnabled = state == PlayState.PLAYING
            stop.isEnabled = state != PlayState.READY
            for (button in listOf(play,pause,stop)) button.alpha = if(button.isEnabled) 1f else .32f
            label.text = when(state) { PlayState.READY -> ""; PlayState.LOADING -> activity.getString(R.string.calls_buffering)
                PlayState.PLAYING -> activity.getString(R.string.calls_playing); PlayState.PAUSED -> activity.getString(R.string.calls_paused) }
        }
        update(model.state.value.first,model.state.value.second)
        row.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) { observation = activity.lifecycleScope.launch { model.state.collect { (id,s) -> update(id,s) } } }
            override fun onViewDetachedFromWindow(v:View) { observation?.cancel() }
        })
        return row
    }
}
