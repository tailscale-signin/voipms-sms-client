package net.kourlas.voipms_sms.lookup

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException

internal data class ShadyCallReport(
    val complaints: Int,
    val communityReports: Int,
    val sourceUrl: String
)

/** US complaint data for an explicitly chosen North American number. No results are retained here. */
internal object ShadyCallReputation {
    private val nanp = Regex("\\+1[2-9][0-9]{2}[2-9][0-9]{6}")

    fun supports(canonical: String): Boolean = nanp.matches(canonical)

    fun sourceUrl(canonical: String): String {
        require(supports(canonical))
        return "https://shadycall.com/number/${canonical.drop(2)}"
    }

    suspend fun search(canonical: String): ShadyCallReport? {
        require(supports(canonical))
        val url = "https://shadycall.com/api/v1/number/${canonical.drop(2)}".toHttpUrl()
        return parse(canonical, lookupJson(url, "application/json"))
    }

    internal fun parse(canonical: String, json: String): ShadyCallReport? {
        val page = sourceUrl(canonical)
        val result = JSONObject(json)
        if (result.getString("number") != canonical.drop(2) ||
            result.getString("international") != canonical || result.getString("url") != page) {
            throw IOException("The reputation service returned a different number or source")
        }
        val complaints = result.count("complaints")
        val badReports = result.count("badReports")
        if (complaints == 0 && badReports == 0) return null
        return ShadyCallReport(complaints, badReports, page)
    }

    private fun JSONObject.count(field: String): Int {
        val count = when (val value = get(field)) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw IOException("Invalid reputation count")
        }
        if (count !in 0L..Int.MAX_VALUE.toLong()) throw IOException("Invalid reputation count")
        return count.toInt()
    }
}
