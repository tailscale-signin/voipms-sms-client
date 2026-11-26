package net.kourlas.voipms_sms.media

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.preferences.currentDirectoryAccountGeneration
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
        val host = activity as? ConversationActivity ?: return
        val manager = host.supportFragmentManager
        if (start !in refs.indices || manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
        MmsGalleryDialog().apply {
            arguments = Bundle().apply {
                putLong("message", id)
                putStringArrayList("refs", ArrayList(refs))
                putInt("index", start)
                putString("thread", host.conversationId.getId())
                putLong("account", currentDirectoryAccountGeneration(host))
            }
        }.showNow(manager, TAG)
    }

    const val TAG = "mms-gallery"

    fun ocr(owner: AppCompatActivity, id: Long, refs: List<String>, bitmap: Bitmap) {
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

class MmsGalleryDialog : DialogFragment() {
    private val refs by lazy { requireArguments().getStringArrayList("refs").orEmpty() }
    private var index = 0
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var video: VideoView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        index = (state ?: requireArguments()).getInt("index")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        Dialog(requireContext(), android.R.style.Theme_Material_NoActionBar_Fullscreen).apply {
            if (requireActivity().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.mms_gallery, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<View>(R.id.gallery_close).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.gallery_previous).setOnClickListener { if (index > 0) { index--; load() } }
        view.findViewById<View>(R.id.gallery_next).setOnClickListener { if (index < refs.lastIndex) { index++; load() } }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            view.findViewById<View>(R.id.gallery_header).setPadding(safe.left, safe.top, safe.right, 0)
            view.findViewById<View>(R.id.gallery_footer).setPadding(safe.left, 0, safe.right, safe.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        val owner = requireActivity() as ConversationActivity
        val args = requireArguments()
        if (index !in refs.indices || !owner.canOpenMedia(args.getString("thread"), args.getLong("account"))) {
            dismissAllowingStateLoss()
            return
        }
        requireDialog().window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        load()
    }

    private fun release() {
        job?.cancel()
        player?.release()
        player = null
        video?.stopPlayback()
        video = null
    }

    override fun onStop() {
        release()
        super.onStop()
    }

    private fun load() {
        release()
        val root = requireView()
        val owner = requireActivity() as AppCompatActivity
        val media = root.findViewById<FrameLayout>(R.id.gallery_media)
        val actions = root.findViewById<LinearLayout>(R.id.gallery_actions)
        val header = root.findViewById<View>(R.id.gallery_header)
        val footer = root.findViewById<View>(R.id.gallery_footer)
        actions.removeAllViews()
        media.removeAllViews()
        header.isVisible = true
        footer.isVisible = true
        root.findViewById<View>(R.id.gallery_navigation).isVisible = refs.size > 1
        root.findViewById<View>(R.id.gallery_previous).isEnabled = index > 0
        root.findViewById<View>(R.id.gallery_next).isEnabled = index < refs.lastIndex
        root.findViewById<TextView>(R.id.gallery_position).text = getString(R.string.mms_gallery_position, index + 1, refs.size)
        fun action(label: Int, perform: () -> Unit) {
            actions.addView(Button(root.context, null, android.R.attr.borderlessButtonStyle).apply {
                setText(label)
                setOnClickListener { perform() }
            })
        }
        val centered = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        media.addView(ProgressBar(root.context), centered)
        val ref = refs[index]
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { MmsMedia.displayFile(owner, ref) }
                val mime = MmsMedia.mime(file)
                media.removeAllViews()
                when {
                    mime.startsWith("image/") -> {
                        val bitmap = withContext(Dispatchers.IO) { MmsMedia.photo(file.readBytes()) }
                        media.addView(ImageView(root.context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageBitmap(bitmap)
                            contentDescription = getString(R.string.mms_toggle_controls)
                            setOnClickListener {
                                val visible = !header.isVisible
                                header.isVisible = visible
                                footer.isVisible = visible
                            }
                        }, FrameLayout.LayoutParams(-1, -1))
                        action(R.string.mms_read_image) { MmsGallery.ocr(owner, requireArguments().getLong("message"), refs, bitmap) }
                    }
                    mime.startsWith("video/") -> {
                        val view = VideoView(root.context)
                        video = view
                        media.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                        view.setVideoURI(MmsMedia.uri(owner, file))
                        action(R.string.mms_play_pause) { if (view.isPlaying) view.pause() else view.start() }
                    }
                    else -> {
                        media.addView(TextView(root.context).apply {
                            text = getString(R.string.mms_file_label, index + 1, file.extension.uppercase(), (file.length() + 999) / 1000)
                        }, centered)
                        action(R.string.mms_play_pause) {
                            try {
                                val audio = player ?: requireNotNull(MediaPlayer.create(owner, MmsMedia.uri(owner, file))).also { player = it }
                                if (audio.isPlaying) audio.pause() else audio.start()
                            } catch (_: Exception) { Toast.makeText(owner, R.string.mms_unavailable, Toast.LENGTH_LONG).show() }
                        }
                        action(R.string.mms_transcribe) { ConversationSpeech.transcribe(owner, MmsMedia.uri(owner, file)) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                media.removeAllViews()
                media.addView(TextView(root.context).apply { setText(R.string.mms_unavailable); gravity = Gravity.CENTER }, centered)
                action(R.string.inbox_load_retry) { load() }
            }
        }
    }
}
