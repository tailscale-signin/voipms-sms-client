package net.kourlas.voipms_sms.lookup

import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import net.kourlas.voipms_sms.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only the number explicitly selected by the user is included in this request. */
internal object WikidataDirectory {
    private val nanp = Regex("[2-9][0-9]{2}[2-9][0-9]{6}")
    private val international = Regex("\\+[2-9][0-9]{6,14}")

    fun number(raw: String): String? {
        val candidate = raw.replace(Regex("[ ().-]"), "")
        val national = when {
            candidate.startsWith("+1") -> candidate.drop(2)
            candidate.length == 11 && candidate.startsWith("1") -> candidate.drop(1)
            else -> candidate
        }
        return when {
            nanp.matches(national) -> "+1$national"
            international.matches(candidate) -> candidate
            else -> null
        }
    }

    internal fun query(canonical: String): String {
        require(number(canonical) == canonical)
        val forms = mutableSetOf(canonical)
        if (canonical.startsWith("+1")) {
            val area = canonical.substring(2, 5)
            val prefix = canonical.substring(5, 8)
            val line = canonical.substring(8)
            forms += listOf("+1-$area-$prefix-$line", "+1 $area $prefix $line", "+1 $area-$prefix-$line")
        } else {
            PhoneNumberUtils.formatNumber(canonical, "US")?.let { formatted ->
                if (formatted.matches(Regex("\\+[0-9 ()-]+"))) {
                    forms += formatted
                    forms += formatted.replace(Regex("[ ()-]+"), "-")
                }
            }
        }
        val values = forms.joinToString(" ") { "\"$it\"" }
        return """
            SELECT DISTINCT ?item ?name WHERE {
              VALUES ?phone { $values }
              ?item wdt:P1329 ?phone .
              FILTER NOT EXISTS { ?item wdt:P31 wd:Q5 }
              OPTIONAL { ?item rdfs:label ?name . FILTER(LANG(?name) = "en") }
            } LIMIT 2
        """.trimIndent()
    }

    internal fun parse(json: String): String? {
        val rows = JSONObject(json).getJSONObject("results").getJSONArray("bindings")
        if (rows.length() != 1) return null
        val row = rows.getJSONObject(0)
        if (row.optJSONObject("item")?.optString("value")
                ?.matches(Regex("https?://www\\.wikidata\\.org/entity/Q[1-9][0-9]*")) != true) return null
        return row.optJSONObject("name")?.optString("value")
            ?.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
            ?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
    }

    suspend fun search(canonical: String): String? {
        val url = "https://query-next.wikidata.org/sparql".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("timeout", "8s")
            .addQueryParameter("query", query(canonical))
            .build()
        return parse(lookupJson(url, "application/sparql-results+json"))
    }
}

private val directoryHttpClient = OkHttpClient.Builder()
    .callTimeout(12, TimeUnit.SECONDS)
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

/** Dedicated unauthenticated client for public directories. No VoIP.ms credentials or cookies are attached. */
internal suspend fun lookupJson(url: okhttp3.HttpUrl, accept: String): String =
    suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("Accept", accept)
            .header("User-Agent", "VoIPms-SMS/${BuildConfig.VERSION_NAME} (https://github.com/tailscale-signin/voipms-sms-client)")
            .build()
        val call = directoryHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = response.use {
                        if (!it.isSuccessful) throw IOException("Directory returned HTTP ${it.code}")
                        val source = it.body.source()
                        if (source.request(64 * 1024L + 1)) throw IOException("Oversize directory response")
                        source.readUtf8()
                    }
                    continuation.resume(json)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
