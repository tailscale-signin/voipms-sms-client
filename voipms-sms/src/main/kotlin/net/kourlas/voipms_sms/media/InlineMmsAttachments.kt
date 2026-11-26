package net.kourlas.voipms_sms.media

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.delivery.dp
import kotlin.math.roundToInt

/** Each attachment keeps its place while visible photos load. A recycled row owns no pending request. */
internal class InlineMmsAttachments(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val cache: LruCache<String, Bitmap>
) : View.OnAttachStateChangeListener {
    private var id = 0L
    private var refs = emptyList<String>()
    private val pending = mutableMapOf<Int, FrameLayout>()
    private val jobs = mutableListOf<Job>()

    init { container.addOnAttachStateChangeListener(this) }

    fun bind(messageId: Long, attachments: List<String>) {
        if (messageId == id && attachments == refs) return
        clear()
        id = messageId
        refs = attachments
        container.visibility = if (refs.isEmpty()) View.GONE else View.VISIBLE
        for ((index, ref) in refs.withIndex()) {
            val slot = FrameLayout(activity)
            container.addView(slot, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (index > 0) topMargin = activity.dp(4)
            })
            val thumbnail = cache.get(ref)
            if (thumbnail != null) {
                showPhoto(slot, index, thumbnail)
            } else {
                slot.addView(Button(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = activity.getString(R.string.mms_attachment_index, index + 1)
                    setOnClickListener { open(index) }
                })
                if (MmsMedia.mayBePhoto(ref)) pending[index] = slot
            }
        }
        if (container.isAttachedToWindow) load()
    }

    private fun open(index: Int) = MmsGallery.show(activity, id, refs, index)

    private fun showPhoto(slot: FrameLayout, index: Int, bitmap: Bitmap) {
        slot.removeAllViews()
        val aspect = bitmap.width.toFloat() / bitmap.height
        val preview = object : AppCompatImageView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val preferred = minOf(activity.dp(270), (activity.dp(320) * aspect).roundToInt())
                val width = View.resolveSize(preferred.coerceAtLeast(1), widthMeasureSpec)
                val height = View.resolveSize((width / aspect).roundToInt().coerceAtLeast(1), heightMeasureSpec)
                setMeasuredDimension(width, height)
            }
        }
        slot.addView(preview.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = activity.getString(R.string.mms_open_photo, index + 1, refs.size)
            setImageBitmap(bitmap)
            isFocusable = true
            setOnClickListener { open(index) }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun load() {
        for ((index, slot) in pending.toMap()) {
            val ref = refs[index]
            jobs += activity.lifecycleScope.launch {
                try {
                    val bitmap = cache.get(ref) ?: workers.withPermit {
                        withContext(Dispatchers.IO) {
                            val file = MmsMedia.displayFile(activity, ref)
                            if (MmsMedia.mime(file).startsWith("image/")) MmsMedia.photo(file.readBytes(), 768) else null
                        }
                    }
                    if (bitmap != null) {
                        cache.put(ref, bitmap)
                        showPhoto(slot, index, bitmap)
                    }
                    pending.remove(index)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    (slot.getChildAt(0) as? Button)?.text = activity.getString(R.string.mms_attachment_retry, index + 1)
                }
            }
        }
    }

    override fun onViewAttachedToWindow(v: View) = load()
    override fun onViewDetachedFromWindow(v: View) {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    fun clear() {
        onViewDetachedFromWindow(container)
        pending.clear()
        refs = emptyList()
        container.removeAllViews()
        container.visibility = View.GONE
    }

    private companion object { val workers = Semaphore(2) }
}
