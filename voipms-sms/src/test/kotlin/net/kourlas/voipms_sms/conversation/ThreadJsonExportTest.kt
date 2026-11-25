package net.kourlas.voipms_sms.conversation

import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.utils.JsonParserManager
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThreadJsonExportTest {
    private fun message(id: Long, text: String) = Message(id, id + 123, Date(id * 1000), true,
        "5550000001", "5550000002", text, "", "", "", false, true, false)

    @Test fun savedDocumentAndClipboardMatchTheExistingInterchangeFormatAcrossPages() = runBlocking {
        val rows = (1L..273L).map { message(it, "quote \" and newline\n$it") }
        val pages: ThreadPageReader = { visit -> rows.chunked(128).forEach(visit) }
        val oldFormat = JsonParserManager.getInstance().parser.adapter(ConversationActivity.ExportableMessages::class.java)
        val file = Buffer()
        assertEquals(273L, ThreadJsonExport.write(file, {}, pages))
        assertEquals(oldFormat.toJson(ConversationActivity.ExportableMessages(rows)), file.readUtf8())
        assertEquals(rows, oldFormat.fromJson(ThreadJsonExport.clipboard({}, pages).text)?.messages)
        assertEquals("{\"messages\":[]}", ThreadJsonExport.clipboard({}) {}.text)
    }

    @Test fun largeHistoryRemainsAvailableInTheFileAfterTheClipboardLimit() = runBlocking {
        val rows = listOf(message(1, "☃".repeat(180_000)))
        var pages = 0
        assertThrows(ThreadJsonExport.ClipboardLimit::class.java) {
            runBlocking {
                ThreadJsonExport.clipboard({}) { visit ->
                    pages++
                    visit(rows)
                    pages++
                    visit(rows)
                }
            }
        }
        assertEquals(1, pages)
        val file = Buffer()
        ThreadJsonExport.write(file, {}) { it(rows) }
        val recovered = JsonParserManager.getInstance().parser.adapter(ConversationActivity.ExportableMessages::class.java)
            .fromJson(file)
        assertEquals(rows, recovered?.messages)
    }

    @Test fun cancellationStopsBeforeSerializingTheNextMessage() {
        var checks = 0
        val rows = (1L..3L).map { message(it, "text") }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                ThreadJsonExport.write(Buffer(), { if (++checks == 2) throw CancellationException() }) { it(rows) }
            }
        }
        assertEquals(2, checks)
    }
}
