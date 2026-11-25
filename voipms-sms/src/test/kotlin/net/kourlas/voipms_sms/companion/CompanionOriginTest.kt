package net.kourlas.voipms_sms.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionOriginTest {
    @Test fun handoffUsesAnExactHttpsOrigin() {
        assertEquals("https://inbox.example.org", originOrNull("https://inbox.example.org/"))
        assertEquals("https://inbox.example.org:9443", originOrNull("https://inbox.example.org:9443"))
        for (invalid in listOf(
            "http://inbox.example.org", "https://name:pw@inbox.example.org",
            "https://inbox.example.org/pair", "https://inbox.example.org/?draft=secret",
            "https://inbox.example.org/#token", "javascript:alert(1)"
        )) assertNull(invalid, originOrNull(invalid))
    }
}
