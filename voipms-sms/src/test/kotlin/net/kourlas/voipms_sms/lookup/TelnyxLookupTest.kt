package net.kourlas.voipms_sms.lookup

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TelnyxLookupTest {
    @Test fun carrierInformationAndRestrictedCoverageRemainDistinct() {
        val number = "+14165550123"
        fun body(extra: String) = """{"data":{"phone_number":"$number",$extra}}"""
        fun parse(value: String, source: CallerSource) =
            ProviderLookup.parse(source, number, org.json.JSONObject(body(value)))
        assertEquals(listOf(CallerFact(FactKind.CARRIER, "Example Mobile"), CallerFact(FactKind.LINE, "mobile")),
            parse(""" "carrier":{"name":"Example Mobile","type":"mobile"} """, CallerSource.TELNYX_CARRIER))
        assertTrue(parse(""" "carrier":null """, CallerSource.TELNYX_CARRIER).isEmpty())
        assertEquals(listOf(CallerFact(FactKind.NAME, "Example Co"), CallerFact(FactKind.CONFIDENCE, "70")),
            parse(""" "caller_identity":{"name":"Example Co","address":"Street information","confidence_score":70} """, CallerSource.TELNYX_IDENTITY))
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLookup.parse(CallerSource.TELNYX_CARRIER, "+14165550124", org.json.JSONObject(body(""" "carrier":null """)))
        }
    }
}
