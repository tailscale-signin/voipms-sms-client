package net.kourlas.voipms_sms.conversation

import com.squareup.moshi.JsonWriter
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.utils.JsonParserManager
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException

internal typealias ThreadPageReader = suspend ((List<Message>) -> Unit) -> Unit

internal object ThreadJsonExport {
    class ClipboardLimit : IOException()

    /** Leave Binder headroom for UTF-16 text and the rest of the clipboard transaction. */
    private const val CLIPBOARD_BYTES = 256L * 1024

    data class ClipboardContents(val text: String, val count: Long)

    suspend fun clipboard(checkActive: () -> Unit, readPages: ThreadPageReader): ClipboardContents {
        val bytes = Buffer()
        val limited = object : ForwardingSink(bytes) {
            override fun write(source: Buffer, byteCount: Long) {
                if (bytes.size + byteCount > CLIPBOARD_BYTES) throw ClipboardLimit()
                super.write(source, byteCount)
            }
        }.buffer()
        val count = write(limited, checkActive, readPages)
        return ClipboardContents(bytes.readUtf8(), count)
    }

    /** Preserve the existing ExportableMessages interchange format while streaming its contents. */
    suspend fun write(sink: BufferedSink, checkActive: () -> Unit, readPages: ThreadPageReader): Long {
        val adapter = JsonParserManager.getInstance().parser.adapter(Message::class.java)
        val writer = JsonWriter.of(sink)
        writer.beginObject().name("messages").beginArray()
        var count = 0L
        readPages { messages ->
            for (message in messages) {
                checkActive()
                adapter.toJson(writer, message)
                count++
            }
        }
        writer.endArray().endObject()
        writer.flush()
        return count
    }
}
