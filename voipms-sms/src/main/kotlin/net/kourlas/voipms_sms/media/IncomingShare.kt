package net.kourlas.voipms_sms.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

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
}
