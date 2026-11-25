package net.kourlas.voipms_sms.lookup

import android.app.Application
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NumberOriginTest {
    @Test fun canadianAndInternationalNumbersHaveOfflineOrigins() {
        assertFalse(NumberOrigin.offline("4163920000", Locale.CANADA).isNullOrBlank())
        assertTrue(NumberOrigin.offline("442079460958", Locale.UK).orEmpty().contains("London"))
        assertEquals("Portugal", NumberOrigin.offline("+351912345678", Locale.CANADA))
        assertNull(NumberOrigin.offline("12345", Locale.CANADA))
        assertNull(NumberOrigin.offline("+18005550100", Locale.CANADA))
    }

    @Test fun prefixSearchAcceptsOnlyOneMatchingAssignment() {
        assertEquals("416392", LocalCallingGuide.prefix("+14163920000"))
        assertNull(LocalCallingGuide.prefix("+442079460958"))
        val row = "<prefixdata><npa>416</npa><nxx>392</nxx><rc>Toronto</rc><region>ON</region></prefixdata>"
        fun doc(rows: String) = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ELEMENT root ANY>]><root>$rows</root>"
        assertEquals("Toronto, ON", LocalCallingGuide.parse("416392", doc(row + row)))
        assertNull(LocalCallingGuide.parse("416392", doc(row + row.replace("Toronto", "Ottawa"))))
        assertNull(LocalCallingGuide.parse("905392", doc(row)))
        assertNull(LocalCallingGuide.parse("416392", doc("")))
    }
}
