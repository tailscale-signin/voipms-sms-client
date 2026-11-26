package net.kourlas.voipms_sms.lookup

import android.app.Application
import android.text.style.ForegroundColorSpan
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WikidataDirectoryTest {
    @Test fun acceptsCanadianAndExplicitInternationalNumbersAndRejectsShortCodesAndExtensions() {
        for (number in listOf("4165550199", "14165550199", "+1 (416) 555-0199")) {
            assertEquals("+14165550199", WikidataDirectory.number(number))
        }
        assertEquals("+442079460958", WikidataDirectory.number("+44 20 7946 0958"))
        for (number in listOf("911", "12345", "1-800-FLOWERS", "0014165550199", "4165550199x123", "+10000000000", "+1\n4165550199")) {
            assertNull(WikidataDirectory.number(number))
        }
    }

    @Test fun queriesOnlyExactLiteralsAndExcludesHumanProfiles() {
        val query = WikidataDirectory.query("+14165550199")
        assertTrue(query.contains("\"+1-416-555-0199\""))
        assertTrue(query.contains("\"+14165550199\""))
        assertTrue(query.contains("wdt:P1329 ?phone"))
        assertTrue(query.contains("FILTER NOT EXISTS { ?item wdt:P31 wd:Q5 }"))
        assertThrows(IllegalArgumentException::class.java) { WikidataDirectory.query("123 } UNION { ") }
    }

    @Test fun displaysOnlyASingleLabeledWikidataEntity() {
        val item = """{"item":{"value":"http://www.wikidata.org/entity/Q670250"},"name":{"value":"Art Gallery of Ontario"}}"""
        fun result(vararg rows: String) = """{"results":{"bindings":[${rows.joinToString(",")}]}}"""
        assertEquals("Art Gallery of Ontario", WikidataDirectory.parse(result(item)))
        assertNull(WikidataDirectory.parse(result()))
        assertNull(WikidataDirectory.parse(result(item, item.replace("Q670250", "Q2"))))
        assertNull(WikidataDirectory.parse(result(item.replace("www.wikidata.org", "untrusted.example"))))
        assertNull(WikidataDirectory.parse(result(item.replace("Art Gallery of Ontario", " "))))
    }

    @Test fun localContactsAndShortCodesDoNotOfferOnlineLookups() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(OnlineNumberLookup.available("4165550199", "Saved name"))
        assertTrue(OnlineNumberLookup.available("4165550199", null))
        assertFalse(OnlineNumberLookup.available("911", null))
        assertNull(OnlineNumberLookup.label(app, "4165550199", "Saved name"))
        assertNull(OnlineNumberLookup.label(app, "4165550199", null))
    }

    @Test @Config(qualifiers = "night") fun blueLookupNumbersRemainReadableInDarkMode() = verifyContrast()
    @Test @Config(qualifiers = "notnight") fun blueLookupNumbersRemainReadableInLightMode() = verifyContrast()

    private fun verifyContrast() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for (toolbar in listOf(false, true)) {
            val text = OnlineNumberLookup.blueNumber(app, "4165550199", toolbar)
            val blue = text.getSpans(0, text.length, ForegroundColorSpan::class.java).single()
            val background = if (toolbar) R.color.toolbar_background else R.color.surface
            assertTrue(ColorUtils.calculateContrast(blue.foregroundColor, app.getColor(background)) >= 4.5)
            assertEquals(0, text.getSpanStart(blue))
            assertEquals(text.length, text.getSpanEnd(blue))
        }
    }
}
