package net.kourlas.voipms_sms.lookup

import android.util.LruCache
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xmlpull.v1.XmlPullParser

internal object LocalCallingGuide {
    private val areas = LruCache<String, String>(128)

    fun prefix(canonical: String): String? =
        canonical.takeIf { it.length == 12 && it.startsWith("+1") && WikidataDirectory.number(it) == it }
            ?.substring(2, 8)

    suspend fun search(canonical: String): String? = withContext(Dispatchers.IO) {
        val prefix = prefix(canonical) ?: return@withContext null
        areas.get(prefix)?.let { return@withContext it }
        val url = "https://localcallingguide.com/xmlprefix.php".toHttpUrl().newBuilder()
            .addQueryParameter("npa", prefix.take(3))
            .addQueryParameter("nxx", prefix.takeLast(3)).build()
        parse(prefix, lookupResponse(url, "application/xml, text/xml"))?.also { areas.put(prefix, it) }
    }

    fun parse(prefix: String, xml: String): String? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(xml.reader())
        val matches = mutableSetOf<String>()
        var fields = mutableMapOf<String, String>()
        var inRow = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when {
                    parser.depth == 2 && parser.name == "prefixdata" -> {
                        inRow = true
                        fields = mutableMapOf()
                    }
                    inRow && parser.depth == 3 && parser.name in setOf("npa", "nxx", "rc", "region") ->
                        fields[parser.name] = parser.nextText().trim().take(80)
                }
                XmlPullParser.END_TAG -> if (inRow && parser.depth == 2) {
                    val area = fields["rc"].orEmpty()
                    val region = fields["region"].orEmpty()
                    if (fields["npa"] + fields["nxx"] == prefix && area.isNotBlank() && region.isNotBlank()) {
                        matches.add("$area, $region")
                    }
                    inRow = false
                }
            }
        }
        return matches.singleOrNull()
    }
}
