package net.kourlas.voipms_sms.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.sms.Message

internal object IncomingShare {
    fun isShare(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return false
        return intent.type == "text/plain" || listOf("image/", "audio/", "video/").any { intent.type?.startsWith(it) == true }
    }

    fun uris(context: Context, source: Intent): List<Uri> {
        val all = ArrayList<Uri>()
        source.clipData?.let { clip ->
            for (i in 0 until minOf(clip.itemCount, MmsMedia.MAX_FILES + 1)) clip.getItemAt(i).uri?.let(all::add)
        }
        if (source.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(source, Intent.EXTRA_STREAM, Uri::class.java)?.take(MmsMedia.MAX_FILES + 1)?.let(all::addAll)
        } else {
            IntentCompat.getParcelableExtra(source, Intent.EXTRA_STREAM, Uri::class.java)?.let(all::add)
        }
        return all.distinct().filter { it.scheme == "content" && it.authority != context.packageName + ".message_media" }
    }

    fun forward(context: Context, source: Intent, dest: Intent) {
        val refs = uris(context, source)
        if (refs.isEmpty()) return
        dest.clipData = ClipData.newRawUri(context.getString(net.kourlas.voipms_sms.R.string.mms_attachments), refs.first()).also {
            refs.drop(1).forEach { uri -> it.addItem(ClipData.Item(uri)) }
        }
        dest.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun share(owner: AppCompatActivity, message: Message) {
        owner.lifecycleScope.launch {
            val trace = if (message.medias.isEmpty()) null else DebugJournal.begin(owner, DiagnosticAction.MEDIA_EXPORT)
            try {
                trace?.step(DiagnosticStep.READING, count = message.medias.size.toLong())
                val files = withContext(Dispatchers.IO) { message.medias.map { MmsMedia.previewFile(owner, it) } }
                val refs = ArrayList(files.map { MmsMedia.uri(owner, it) })
                val intent = Intent(if (refs.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
                intent.type = if (files.isEmpty()) "text/plain" else {
                    val types = files.map { MmsMedia.mime(it) }.distinct()
                    if (types.size == 1) types.first() else {
                        val families = types.map { it.substringBefore('/') }.distinct()
                        if (families.size == 1) families.first() + "/*" else "*/*"
                    }
                }
                if (message.text.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, message.text)
                if (refs.isNotEmpty()) {
                    if (refs.size == 1) intent.putExtra(Intent.EXTRA_STREAM, refs.first())
                    else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, refs)
                    intent.clipData = ClipData.newRawUri(null, refs.first()).also { clip ->
                        refs.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                owner.startActivity(Intent.createChooser(intent, null))
                // The receiving app owns any subsequent save or share.
                trace?.step(DiagnosticStep.HANDOFF, count = refs.size.toLong())
            } catch (e: CancellationException) {
                trace?.step(DiagnosticStep.CANCELLED)
                throw e
            } catch (e: Exception) {
                trace?.step(DiagnosticStep.FAILED, error = e)
                android.widget.Toast.makeText(owner, net.kourlas.voipms_sms.R.string.mms_unavailable,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

}
