package net.kourlas.voipms_sms.conversation

import java.util.Date
import kotlinx.coroutines.CancellationException
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.utils.JsonParserManager
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThreadJsonExportTest {
    private fun message(id: Long, text: String) = Message(id, id + 123, Date(id * 1000), true,
        "5550000001", "5550000002", text, "", "", "", false, true, false)

    @Test fun savedDocumentAndClipboardMatchTheExistingInterchangeFormat() {
        val rows = (1L..137L).map { message(it, "quote \" and newline\n$it") }
        val oldFormat = JsonParserManager.getInstance().parser.adapter(ConversationActivity.ExportableMessages::class.java)
        val file = Buffer()
        ThreadJsonExport.write(file, rows) {}
        assertEquals(oldFormat.toJson(ConversationActivity.ExportableMessages(rows)), file.readUtf8())
        assertEquals(rows, oldFormat.fromJson(ThreadJsonExport.clipboard(rows) {})?.messages)
        assertEquals("{\"messages\":[]}", ThreadJsonExport.clipboard(emptyList()) {})
    }

    @Test fun largeHistoryRemainsAvailableInTheFileAfterTheClipboardLimit() {
        val rows = listOf(message(1, "☃".repeat(180_000)))
        assertThrows(ThreadJsonExport.ClipboardLimit::class.java) { ThreadJsonExport.clipboard(rows) {} }
        val file = Buffer()
        ThreadJsonExport.write(file, rows) {}
        val recovered = JsonParserManager.getInstance().parser.adapter(ConversationActivity.ExportableMessages::class.java)
            .fromJson(file)
        assertEquals(rows, recovered?.messages)
    }

    @Test fun cancellationStopsBeforeSerializingTheNextMessage() {
        var checks = 0
        val rows = (1L..3L).map { message(it, "text") }
        assertThrows(CancellationException::class.java) {
            ThreadJsonExport.write(Buffer(), rows) {
                if (++checks == 2) throw CancellationException()
            }
        }
        assertEquals(2, checks)
    }
}
