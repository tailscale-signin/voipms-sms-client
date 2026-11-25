package net.kourlas.voipms_sms.media

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.tools.ConversationSpeech

internal object MmsGallery {
    fun show(activity: AppCompatActivity, id: Long, refs: List<String>, start: Int) {
        if (start !in refs.indices) return
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val media = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(media)
        panel.addView(actions)
        val dialog = AlertDialog.Builder(activity).setView(ScrollView(activity).apply { addView(panel) })
            .setPositiveButton(android.R.string.ok, null).setNeutralButton(R.string.mms_previous, null)
            .setNegativeButton(R.string.mms_next, null).create()
        var index = start
        var job: Job? = null
        var player: MediaPlayer? = null
        var video: VideoView? = null
        fun release() { player?.release(); player = null; video?.stopPlayback(); video = null }
        fun action(label: Int, run: () -> Unit) {
            actions.addView(Button(activity).apply { setText(label); setOnClickListener { run() } })
        }
        fun load() {
            job?.cancel()
            release()
            actions.removeAllViews(); media.removeAllViews()
            media.addView(ProgressBar(activity))
            dialog.setTitle(activity.getString(R.string.mms_gallery_position, index + 1, refs.size))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = index > 0
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = index < refs.lastIndex
            val ref = refs[index]
            job = activity.lifecycleScope.launch {
                try {
                    val file = withContext(Dispatchers.IO) { MmsMedia.previewFile(activity, ref) }
                    val type = MmsMedia.mime(file)
                    media.removeAllViews()
                    val size = (activity.resources.displayMetrics.heightPixels * 0.42).toInt()
                    when {
                        type.startsWith("image/") -> {
                            val bitmap = withContext(Dispatchers.IO) { MmsMedia.photo(file.readBytes()) }
                            media.addView(ImageView(activity).apply {
                                contentDescription = activity.getString(R.string.mms_preview)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setImageBitmap(bitmap)
                            }, LinearLayout.LayoutParams(-1, size))
                            action(R.string.mms_read_image) { ocr(activity, id, refs, bitmap) }
                        }
                        type.startsWith("video/") -> {
                            val view = VideoView(activity)
                            video = view
                            media.addView(view, LinearLayout.LayoutParams(-1, size))
                            view.setVideoURI(MmsMedia.uri(activity, file))
                            action(R.string.mms_play_pause) { if (view.isPlaying) view.pause() else view.start() }
                        }
                        type.startsWith("audio/") -> {
                            action(R.string.mms_play_pause) {
                                try {
                                    val audio = player ?: MediaPlayer.create(activity, MmsMedia.uri(activity, file))?.also { player = it }
                                    if (audio?.isPlaying == true) audio.pause() else audio?.start()
                                } catch (_: IllegalStateException) { toast(activity, R.string.mms_unavailable) }
                            }
                            action(R.string.mms_transcribe) { ConversationSpeech.transcribe(activity, MmsMedia.uri(activity, file)) }
                        }
                    }
                    media.addView(TextView(activity).apply {
                        text = activity.getString(R.string.mms_file_label, index + 1, file.extension.uppercase(),
                            (file.length() + 999) / 1000)
                    })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    media.removeAllViews()
                    media.addView(TextView(activity).apply { setText(R.string.mms_unavailable) })
                }
            }
        }
        dialog.setOnDismissListener { job?.cancel(); release() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { if (index > 0) { index--; load() } }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { if (index < refs.lastIndex) { index++; load() } }
        load()
    }

    private fun ocr(owner: AppCompatActivity, id: Long, refs: List<String>, bitmap: Bitmap) {
        fun read() {
            owner.lifecycleScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { PhotoOcr.read(owner, bitmap) }
                    if (text.isBlank()) { toast(owner, R.string.mms_ocr_empty); return@launch }
                    val db = Database.getInstance(owner)
                    val msg = if (id > 0) db.getMessageDatabaseId(id) else null
                    val dialog = AlertDialog.Builder(owner).setTitle(R.string.mms_read_image)
                        .setMessage(text).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.copy) { _, _ ->
                            val clip = ClipData.newPlainText(owner.getString(R.string.mms_read_image), text)
                            clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            owner.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        }
                    if (msg != null && !PrivateThreads.isPrivate(owner, msg.conversationId)) {
                        dialog.setNeutralButton(R.string.mms_index) { _, _ -> index(owner, id, refs) }
                    }
                    dialog.show()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(owner, R.string.mms_ocr_failed) }
                catch (e: LinkageError) { toast(owner, R.string.mms_ocr_failed) }
            }
        }
        if (PhotoOcr.installed(owner)) read() else AlertDialog.Builder(owner)
            .setMessage(R.string.mms_ocr_model).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mms_download) { _, _ -> read() }.show()
    }
    private fun index(owner: AppCompatActivity, id: Long, refs: List<String>) {
        owner.lifecycleScope.launch {
            try {
                val db = Database.getInstance(owner)
                val msg = db.getMessageDatabaseId(id) ?: return@launch
                if (PrivateThreads.isPrivate(owner, msg.conversationId)) return@launch
                val result = withContext(Dispatchers.IO) {
                    refs.mapNotNull { ref ->
                        val file = MmsMedia.previewFile(owner, ref)
                        if (!MmsMedia.mime(file).startsWith("image/")) null
                        else PhotoOcr.read(owner, MmsMedia.photo(file.readBytes())).takeIf(String::isNotBlank)
                    }.joinToString("\n")
                }
                if (!PrivateThreads.isPrivate(owner, msg.conversationId) && db.getMessageDatabaseId(id)?.medias == refs) {
                    if (db.setSearchOcr(id, result, refs)) toast(owner, R.string.mms_indexed)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(owner, R.string.mms_ocr_failed) }
            catch (e: LinkageError) { toast(owner, R.string.mms_ocr_failed) }
        }
    }
    private fun toast(owner: AppCompatActivity, res: Int) = Toast.makeText(owner, res, Toast.LENGTH_LONG).show()
}
