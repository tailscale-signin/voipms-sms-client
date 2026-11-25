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
        assertEquals("Example Mobile · mobile", TelnyxLookup.parse(number, false,
            body(""" "carrier":{"name":"Example Mobile","type":"mobile"} """)))
        assertNull(TelnyxLookup.parse(number, false, body(""" "carrier":null """)))
        assertEquals("Example Co · confidence 70", TelnyxLookup.parse(number, true,
            body(""" "caller_identity":{"name":"Example Co","address":"Street information","confidence_score":70} """)))
        assertThrows(IllegalArgumentException::class.java) {
            TelnyxLookup.parse("+14165550124", false, body(""" "carrier":null """))
        }
    }
}
