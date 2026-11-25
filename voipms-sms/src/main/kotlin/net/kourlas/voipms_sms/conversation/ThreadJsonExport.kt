package net.kourlas.voipms_sms.conversation

import com.squareup.moshi.JsonWriter
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.utils.JsonParserManager
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException

internal object ThreadJsonExport {
    class ClipboardLimit : IOException()

    /** Leave Binder headroom for UTF-16 text and the rest of the clipboard transaction. */
    private const val CLIPBOARD_BYTES = 256L * 1024

    fun clipboard(messages: List<Message>, checkActive: () -> Unit): String {
        val bytes = Buffer()
        val limited = object : ForwardingSink(bytes) {
            override fun write(source: Buffer, byteCount: Long) {
                if (bytes.size + byteCount > CLIPBOARD_BYTES) throw ClipboardLimit()
                super.write(source, byteCount)
            }
        }.buffer()
        write(limited, messages, checkActive)
        return bytes.readUtf8()
    }

    /** Preserve the existing ExportableMessages interchange format while streaming its contents. */
    fun write(sink: BufferedSink, messages: List<Message>, checkActive: () -> Unit) {
        val adapter = JsonParserManager.getInstance().parser.adapter(Message::class.java)
        val writer = JsonWriter.of(sink)
        writer.beginObject().name("messages").beginArray()
        for (message in messages) {
            checkActive()
            adapter.toJson(writer, message)
        }
        writer.endArray().endObject()
        writer.flush()
    }
}
