package net.kourlas.voipms_sms.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.withTranslation
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import java.io.File
import kotlin.math.roundToInt

internal object PhotoEditor {
    fun open(activity: AppCompatActivity, file: File, replace: (String) -> Unit) {
        activity.lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { MmsMedia.photo(file.readBytes()) }
                val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val editor = MaskView(activity, image)
                val mode = Button(activity).apply { setText(R.string.mms_mode_cover) }
                mode.setOnClickListener {
                    editor.cropping = !editor.cropping
                    mode.setText(if (editor.cropping) R.string.mms_mode_crop else R.string.mms_mode_cover)
                }
                panel.addView(mode)
                panel.addView(editor, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.47).toInt()))
                val dialog = AlertDialog.Builder(activity).setTitle(R.string.mms_edit)
                    .setMessage(R.string.mms_edit_help).setView(panel)
                    .setNeutralButton(R.string.mms_reset, null)
                    .setPositiveButton(R.string.mms_apply, null).setNegativeButton(android.R.string.cancel, null).show()
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { editor.clear() }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val result = editor.result()
                    dialog.dismiss()
                    activity.lifecycleScope.launch {
                        try { replace(withContext(Dispatchers.IO) { MmsMedia.savePhoto(activity, result) }) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Toast.makeText(activity, R.string.mms_unavailable, Toast.LENGTH_LONG).show() }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(activity, R.string.mms_unavailable, Toast.LENGTH_LONG).show() }
        }
    }

    private class MaskView(context: Context, private val image: Bitmap) : View(context) {
        var cropping = false
        private val regions = mutableListOf<RectF>()
        private val frame = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var crop: RectF? = null
        private var active: RectF? = null
        private var firstX = 0f
        private var firstY = 0f
        fun clear() { crop = null; active = null; regions.clear(); invalidate() }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val scale = minOf(w.toFloat() / image.width, h.toFloat() / image.height)
            val fw = image.width * scale
            val fh = image.height * scale
            frame.set((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
        }
        override fun onDraw(canvas: Canvas) {
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            canvas.drawBitmap(image, null, frame, paint)
            canvas.withTranslation(frame.left, frame.top) {
                canvas.scale(frame.width() / image.width, frame.height() / image.height)
                paint.color = Color.BLACK
                regions.forEach { canvas.drawRect(it, paint) }
                if (!cropping) active?.let { canvas.drawRect(it, paint) }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4 * image.width / frame.width()
                paint.color = Color.WHITE
                crop?.let { canvas.drawRect(it, paint) }
                if (cropping) active?.let { canvas.drawRect(it, paint) }
            }
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = ((e.x - frame.left) / frame.width() * image.width).coerceIn(0f, image.width.toFloat())
            val y = ((e.y - frame.top) / frame.height() * image.height).coerceIn(0f, image.height.toFloat())
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { firstX = x; firstY = y; active = null }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    active = RectF(minOf(firstX,x),minOf(firstY,y), maxOf(firstX,x),maxOf(firstY,y))
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        active?.takeIf { it.width() >= 2f && it.height() >= 2f }?.let {
                            if (cropping) crop = it else regions.add(it)
                        }
                        active = null
                        performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> active = null
            }
            invalidate()
            return true
        }
        fun result(): Bitmap = render(image, regions, crop)

    }
    internal fun render(image: Bitmap, covers: List<RectF>, crop: RectF?): Bitmap {
        val rendered = image.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(rendered)
        val opaque = Paint().apply { color = Color.BLACK }
        covers.forEach { canvas.drawRect(it, opaque) }
        val area = crop ?: return rendered
        val left = area.left.roundToInt()
        val top = area.top.roundToInt()
        return Bitmap.createBitmap(rendered, left, top,
            area.right.roundToInt() - left, area.bottom.roundToInt() - top)
    }

}
