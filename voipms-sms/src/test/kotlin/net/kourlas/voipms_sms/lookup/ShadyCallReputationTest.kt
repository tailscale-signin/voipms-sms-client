package net.kourlas.voipms_sms.lookup

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShadyCallReputationTest {
    private val number = "+12125550123"
    private val page = "https://shadycall.com/number/2125550123"

    private fun response(): JSONObject = JSONObject()
        .put("number", "2125550123")
        .put("international", number)
        .put("url", page)
        .put("complaints", 0)
        .put("badReports", 0)

    @Test fun validNanpNumbersProduceAProviderAttributionUrl() {
        assertTrue(ShadyCallReputation.supports("+14165550199"))
        assertEquals(page, ShadyCallReputation.sourceUrl(number))
        for (invalid in listOf("911", "2125550123", "+442079460000", "+10255550123", "+12125550123?data")) {
            assertFalse(ShadyCallReputation.supports(invalid))
            assertThrows(IllegalArgumentException::class.java) { ShadyCallReputation.sourceUrl(invalid) }
        }
    }

    @Test fun onlyConsumerComplaintsAndNegativeCommunityReportsAreDisplayed() {
        val json = response().put("complaints", 7).put("badReports", 3).put("reports", 19)
        assertEquals(ShadyCallReport(7, 3, page), ShadyCallReputation.parse(number, json.toString()))
        assertNull(ShadyCallReputation.parse(number,
            response().put("reports", 16).put("safeReports", 16).put("verdict", "safe").toString()))
    }

    @Test fun mismatchedNumbersAndExternalAttributionLinksAreRejected() {
        for (entry in listOf("international" to "+12125550124", "number" to "2125550124",
            "url" to "https://example.invalid/number/2125550123")) {
            assertThrows(IOException::class.java) {
                ShadyCallReputation.parse(number, response().put(entry.first, entry.second).toString())
            }
        }
    }

    @Test fun invalidCountsCannotBecomePositiveEvidence() {
        for (value in listOf(-1, 3.5, "2", 3_000_000_000L)) {
            assertThrows(IOException::class.java) {
                ShadyCallReputation.parse(number, response().put("badReports", value).toString())
            }
        }
    }
}
