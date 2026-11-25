package net.kourlas.voipms_sms.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import net.kourlas.voipms_sms.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.WeakHashMap

/** A font selected in Appearance stays on this device, outside Android backup. */
object ChatFont : Application.ActivityLifecycleCallbacks {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private var selected: Typeface? = null
    private var generation = 0
    private val created = WeakHashMap<Activity, Int>()
    private val resumed = mutableMapOf<Activity, ViewTreeObserver.OnGlobalLayoutListener?>()

    val hasCustomFont: Boolean get() = selected != null

    private fun fontFile(context: Context) = File(context.noBackupFilesDir, "chat-font")

    fun start(app: Application) {
        val file = fontFile(app)
        if (file.isFile) selected = runCatching { load(file) }.getOrNull()
        app.registerActivityLifecycleCallbacks(this)
    }

    private fun load(file: File): Typeface {
        // Typeface.createFromFile on older Android versions can silently substitute the default.
        RandomAccessFile(file, "r").use { sfnt ->
            require(file.length() in 12..MAX_BYTES.toLong())
            require(sfnt.readInt() in intArrayOf(0x00010000, 0x4F54544F)) // TrueType or OpenType
            val count = sfnt.readUnsignedShort()
            require(count in 1..256 && 12L + 16L * count <= file.length())
            sfnt.seek(12)
            val required = mutableSetOf(0x636D6170, 0x68656164, 0x6D617870) // cmap, head, maxp
            repeat(count) {
                required.remove(sfnt.readInt())
                sfnt.readInt() // table checksum
                val offset = sfnt.readInt().toLong() and 0xffffffffL
                val length = sfnt.readInt().toLong() and 0xffffffffL
                require(offset + length <= file.length())
            }
            require(required.isEmpty())
        }
        return if (Build.VERSION.SDK_INT >= 26) {
            requireNotNull(Typeface.Builder(file).build())
        } else {
            Typeface.createFromFile(file)
        }
    }

    /** Runs on the I/O dispatcher. A failed import preserves the previous font. */
    fun import(context: Context, uri: Uri): Typeface {
        val destination = fontFile(context)
        val temporary = File.createTempFile("font-import-", ".tmp", destination.parentFile)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        require(total <= MAX_BYTES)
                        output.write(buffer, 0, size)
                    }
                }
            } ?: throw IOException("Unreadable font")
            val font = load(temporary)
            android.system.Os.rename(temporary.absolutePath, destination.absolutePath)
            return font
        } finally {
            temporary.delete()
        }
    }

    fun reset(context: Context) {
        val file = fontFile(context)
        if (file.exists() && !file.delete()) throw IOException("Unable to restore bundled font")
        change(null)
    }

    fun change(typeface: Typeface?) {
        selected = typeface
        generation++
        resumed.keys.toList().forEach { if (!it.isFinishing) it.recreate() }
    }

    fun setInboxWeight(view: TextView, bold: Boolean) {
        val face = selected
        view.typeface = if (face == null) {
            ResourcesCompat.getFont(view.context, if (bold) R.font.dm_sans_bold else R.font.dm_sans_regular)
        } else {
            Typeface.create(face, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun style(view: View, font: Typeface) {
        if (view is TextView) {
            val old = view.typeface ?: Typeface.DEFAULT
            val wanted = if (Build.VERSION.SDK_INT >= 28) Typeface.create(font, old.weight, old.isItalic)
                         else Typeface.create(font, old.style)
            if (old != wanted) view.typeface = wanted
        }
        if (view is RecyclerView && view.getTag(R.id.custom_font_children_hook) == null) {
            view.setTag(R.id.custom_font_children_hook, true)
            view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(child: View) = style(child, font)
                override fun onChildViewDetachedFromWindow(child: View) = Unit
            })
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) style(view.getChildAt(i), font)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) { created[activity] = generation }

    override fun onActivityResumed(activity: Activity) {
        if (created[activity] != generation) {
            if (!activity.isFinishing) activity.recreate()
            return
        }
        val decor = activity.window.decorView
        val observer = selected?.let { font ->
            ViewTreeObserver.OnGlobalLayoutListener { style(decor, font) }.also {
                decor.viewTreeObserver.addOnGlobalLayoutListener(it)
                style(decor, font)
            }
        }
        resumed[activity] = observer
    }

    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)?.let { observer ->
            val tree = activity.window.decorView.viewTreeObserver
            if (tree.isAlive) tree.removeOnGlobalLayoutListener(observer)
        }
    }

    override fun onActivityDestroyed(activity: Activity) { created.remove(activity) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
