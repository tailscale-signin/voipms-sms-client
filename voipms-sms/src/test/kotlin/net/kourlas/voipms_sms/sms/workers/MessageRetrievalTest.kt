package net.kourlas.voipms_sms.sms.workers

import net.kourlas.voipms_sms.utils.JsonParserManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Date

class MessageRetrievalTest {
    @Test
    fun emptyLineScansHistoryOnceAndThenUsesItsOwnOverlappingWindow() {
        val now = Date.from(Instant.parse("2026-04-02T05:00:00Z"))
        assertEquals(
            listOf(RetrievalWindow("2026-01-01", "2026-03-31"), RetrievalWindow("2026-04-01", "2026-04-02")),
            retrievalWindows("2026-01-01", null, now)
        )
        assertEquals(
            listOf(RetrievalWindow("2026-04-01", "2026-04-02")),
            retrievalWindows("2026-01-01", "2026-04-02", now)
        )
        assertEquals(emptyList<RetrievalWindow>(), retrievalWindows("2026-04-03", null, now))
    }

    @Test
    fun providerWallTimesRespectDaylightSaving() {
        val formatter = messageDateFormat()
        val message = MessageResponse(id = "9999999999", type = "1", did = "14165550100",
            contact = "+14165550101", message = "Hi")
        for ((date, utc) in listOf(
            "2026-07-01 12:00:00" to "2026-07-01T16:00:00Z",
            "2026-01-01 12:00:00" to "2026-01-01T17:00:00Z"
        )) {
            val incoming = message.copy(date = date).incoming("4165550100", formatter)!!
            assertEquals(Date.from(Instant.parse(utc)), incoming.date)
            assertEquals("4165550100", incoming.did)
            assertEquals("4165550101", incoming.contact)
        }
        assertNull(message.copy(date = "2026-07-32 12:00:00").incoming("4165550100", formatter))
        assertNull(message.copy(date = "2026-07-01 12:00:00").incoming("4165550102", formatter))
    }

    @Test
    fun mixedAndSingleRecordResponsesPreserveValidSmsAndAttachments() {
        val adapter = JsonParserManager.getInstance().parser.adapter(MessagesResponse::class.java)
        val row = """{"id":91,"type":1,"did":"+14165550100","contact":"12345","date":"2026-07-01 12:00:00","media":["https://example.invalid/photo.jpg"]}"""
        val mixed = adapter.fromJson("""{"status":"success","sms":[{"id":{}},$row]}""")!!.sms!!.rows
        assertNull(mixed.first())
        val incoming = mixed.last()!!.incoming("4165550100", messageDateFormat())!!
        assertEquals(91L, incoming.voipId)
        assertEquals("12345", incoming.contact)
        assertEquals("https://example.invalid/photo.jpg", incoming.media1)

        val single = adapter.fromJson("""{"status":"success","sms":$row}""")!!.sms!!.rows
        assertEquals(mixed.last(), single.single())
    }
}
