package net.kourlas.voipms_sms.sms.workers

import net.kourlas.voipms_sms.utils.hasOversizedSmsUrl
import net.kourlas.voipms_sms.utils.splitSms
import org.junit.Assert.*
import org.junit.Test

class SmsPartsTest {
    @Test fun clickableLinksStartOnFreshPartWhenNeeded() {
        val link = "https://example.com/ticket/1234"
        val text = "A".repeat(145) + " " + link + " see you"
        val parts = splitSms(text, 160)
        assertTrue(parts.any { it.contains(link) })
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.toByteArray().size <= 160 })
    }

    @Test fun utf8AndOversizedGraphemeAreNeverTruncated() {
        val text = "é".repeat(78) + "東🦜" + "a" + "\u0301".repeat(190)
        val parts = splitSms(text, 160)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.toByteArray().size in 1..160 &&
            it == String(it.toByteArray(), Charsets.UTF_8) })
    }

    @Test fun urlsLargerThanOnePartAreFlagged() {
        val text = "link https://example.com/" + "à".repeat(75)
        assertTrue(hasOversizedSmsUrl(text, 160))
        assertFalse(hasOversizedSmsUrl("Bonjour https://example.ca/été", 160))
        assertEquals(text, splitSms(text, 160).joinToString(""))
    }
}
