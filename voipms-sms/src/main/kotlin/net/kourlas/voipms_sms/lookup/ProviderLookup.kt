package net.kourlas.voipms_sms.lookup

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Each credential is attached only to its provider's fixed HTTPS destination. */
internal object ProviderLookup {
    suspend fun search(source: CallerSource, number: String, credentials: LookupCredentials): List<CallerFact> {
        val response = try {
            lookupResponse(request(source, number, credentials))
        } catch (e: LookupHttpError) {
            if (e.status == 404 && source == CallerSource.HIYA) return emptyList()
            throw e
        }
        return parse(source, number, JSONObject(response))
    }

    fun request(source: CallerSource, number: String, credentials: LookupCredentials): Request {
        require(WikidataDirectory.number(number) == number)
        val builder = Request.Builder().header("Accept", "application/json")
        fun url(base: String, vararg params: Pair<String, String>, segment: String? = null) {
            val target = base.toHttpUrl().newBuilder()
            if (segment != null) target.addPathSegment(segment)
            for ((key, value) in params) target.addQueryParameter(key, value)
            builder.url(target.build())
        }
        fun bearer(field: AccountField = AccountField.TOKEN) =
            builder.header("Authorization", "Bearer " + credentials[field])
        fun basic(user: AccountField, pass: AccountField) =
            builder.header("Authorization", Credentials.basic(credentials[user], credentials[pass]))
        fun apiKey(header: String) = builder.header(header, credentials[AccountField.KEY])
        fun json(body: JSONObject) = builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        when (source) {
            CallerSource.TELNYX_CARRIER, CallerSource.TELNYX_IDENTITY -> {
                val type = if (source == CallerSource.TELNYX_IDENTITY) "caller-identity" else "carrier"
                url("https://api.telnyx.com/v2/number_lookup", "type" to type, segment = number)
                bearer(AccountField.KEY)
            }
            CallerSource.TWILIO -> {
                url("https://lookups.twilio.com/v2/PhoneNumbers", "Fields" to "caller_name", segment = number)
                basic(AccountField.TWILIO_SID, AccountField.PASSWORD)
            }
            CallerSource.HIYA -> {
                url("https://developer.hiya.com/v1/phones/phoneProfile", "phone" to number)
                builder.header("x-hiya-account-user-id", credentials[AccountField.USER])
                bearer()
            }
            CallerSource.SIGNALWIRE -> {
                val space = credentials[AccountField.SPACE].lowercase(Locale.ROOT)
                require(validSpace(space))
                url("https://$space.signalwire.com/api/relay/rest/lookup/phone_number", "include" to "carrier,cnam", segment = number)
                basic(AccountField.PROJECT, AccountField.TOKEN)
            }
            CallerSource.TRESTLE -> {
                url("https://api.trestleiq.com/3.1/cnam", "phone" to number)
                apiKey("x-api-key")
            }
            CallerSource.TRUECNAM -> {
                url("https://api.truecnam.net/api/v1", "username" to credentials[AccountField.KEY],
                    "password" to credentials[AccountField.PASSWORD], "resp_type" to "basic",
                    "resp_format" to "json", "call_party" to "terminating", "calling_number" to number.drop(1))
            }
            CallerSource.IPQS -> {
                url("https://www.ipqualityscore.com/api/json/phone", "phone" to number)
                apiKey("ipqs-key")
            }
            CallerSource.CALLCONTROL -> url("https://api.callcontrol.com/api/2015-11-01/Reputation",
                "api_key" to credentials[AccountField.KEY], segment = number.drop(1))
            CallerSource.PHONEBLOCK -> {
                val prefix = MessageDigest.getInstance("SHA-1").digest(number.toByteArray(Charsets.US_ASCII))
                    .take(2).joinToString("") { "%02X".format(Locale.ROOT, it) }
                url("https://phoneblock.net/phoneblock/api/check-prefix", "sha1" to prefix)
                bearer()
            }
            CallerSource.PLIVO -> {
                url("https://lookup.plivo.com/v1/Number", "type" to "carrier", segment = number)
                basic(AccountField.PLIVO_ID, AccountField.TOKEN)
            }
            CallerSource.SINCH -> {
                val target = "https://lookup.api.sinch.com/v2/projects".toHttpUrl().newBuilder()
                    .addPathSegment(credentials[AccountField.PROJECT]).addPathSegment("lookups").build()
                builder.url(target)
                bearer()
                json(JSONObject().put("number", number).put("features", org.json.JSONArray().put("LineType")))
            }
            CallerSource.VONAGE -> {
                url("https://api-eu.vonage.com/identity-insights/v1/requests")
                bearer()
                json(JSONObject().put("phone_number", number.drop(1)).put("purpose", "FraudPreventionAndDetection")
                    .put("insights", JSONObject().put("original_carrier", JSONObject())))
            }
            CallerSource.VERIPHONE -> {
                url("https://api.veriphone.io/v3/verify", "phone" to number)
                bearer(AccountField.KEY)
            }
            CallerSource.NUMLOOKUP -> {
                url("https://api.numlookupapi.com/v1/validate", segment = number)
                apiKey("apikey")
            }
            CallerSource.NUMVERIFY -> url("https://api.apilayer.net/numverify/validate",
                "access_key" to credentials[AccountField.KEY], "number" to number.drop(1))
            CallerSource.NEUTRINO -> {
                url("https://neutrinoapi.net/phone-validate")
                builder.header("User-ID", credentials[AccountField.USER])
                apiKey("API-Key")
                builder.post(FormBody.Builder().add("number", number).build())
            }
            CallerSource.ABSTRACT -> url("https://phoneintelligence.abstractapi.com/v1/",
                "api_key" to credentials[AccountField.KEY], "phone" to number)
            CallerSource.APIVERVE -> {
                val region = requireNotNull(CallerSource.region(number)).lowercase(Locale.ROOT)
                url("https://api.apiverve.com/v1/phonenumbervalidator", "number" to number, "country" to region)
                apiKey("X-API-Key")
            }
            else -> error("Source has a dedicated public adapter")
        }
        return builder.build()
    }

    fun validSpace(value: String) = value.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))

    fun parse(source: CallerSource, number: String, data: JSONObject): List<CallerFact> = buildList {
        fun put(kind: FactKind, value: String?) {
            val clean = value?.replace(control, " ")?.trim()?.take(120) ?: return
            val normalized = clean.lowercase(Locale.ROOT)
            if (clean.isNotEmpty() && normalized !in emptyValues && (kind != FactKind.NAME || normalized !in genericNames)) {
                add(CallerFact(kind, clean))
            }
        }
        fun metadata(carrier: String?, type: String?, area: String?, original: Boolean = false) {
            put(if (original) FactKind.RANGE_HOLDER else FactKind.CARRIER, carrier)
            put(FactKind.LINE, type)
            put(FactKind.AREA, area)
        }
        fun match(echo: String?) {
            val digits = echo?.replace(separators, "")?.removePrefix("+")
            require(digits == number.drop(1)) { "Lookup response number differs" }
        }
        fun score(value: Any?) {
            if (value is Int && value in 0..100) put(FactKind.RISK, "$value/100")
        }
        when (source) {
            CallerSource.TELNYX_CARRIER, CallerSource.TELNYX_IDENTITY -> {
                val result = data.getJSONObject("data")
                match(result.text("phone_number"))
                if (source == CallerSource.TELNYX_IDENTITY) {
                    val info = result.optJSONObject("caller_identity") ?: return@buildList
                    val confidence = info.optInt("confidence_score")
                    if (confidence in 50..90 && info.text("name") != null) {
                        put(FactKind.NAME, info.text("name"))
                        if (any { it.kind == FactKind.NAME }) put(FactKind.CONFIDENCE, confidence.toString())
                    }
                } else {
                    val carrier = result.optJSONObject("carrier") ?: return@buildList
                    metadata(carrier.text("name"), carrier.text("type"), null)
                }
            }
            CallerSource.TWILIO -> {
                if (!data.getBoolean("valid")) return@buildList
                match(data.text("phone_number"))
                val info = data.optJSONObject("caller_name") ?: return@buildList
                if (!info.isNull("error_code")) throw IOException("Caller name package unavailable")
                put(FactKind.NAME, info.text("caller_name"))
            }
            CallerSource.HIYA -> {
                match(data.text("number"))
                put(FactKind.NAME, data.text("name"))
                metadata(data.text("carrier"), data.text("connectionType"), data.text("location"))
            }
            CallerSource.SIGNALWIRE -> {
                if (!data.getBoolean("valid_number")) return@buildList
                match(data.text("e164"))
                put(FactKind.NAME, data.optJSONObject("cnam")?.text("caller_id"))
                val carrier = data.optJSONObject("carrier")
                metadata(carrier?.text("lec"), carrier?.text("linetype") ?: data.text("number_type"), data.text("location"))
            }
            CallerSource.TRESTLE -> {
                if (data.optJSONObject("error") != null) throw IOException("CNAM lookup returned an error")
                if (data.getBoolean("is_valid")) put(FactKind.NAME, data.optJSONObject("belongs_to")?.text("name"))
            }
            CallerSource.TRUECNAM -> {
                if (data.getString("err") != "0") throw IOException("CNAM lookup returned an error")
                match(data.text("calling_number"))
                put(FactKind.NAME, data.text("name"))
            }
            CallerSource.IPQS -> {
                check(data.getBoolean("success"))
                if (!data.getBoolean("valid")) return@buildList
                match(data.text("formatted"))
                put(FactKind.NAME, data.text("name"))
                metadata(data.text("carrier"), data.text("line_type"), data.text("region") ?: data.text("country"))
                score(data.opt("fraud_score"))
            }
            CallerSource.CALLCONTROL -> {
                val flagged = data.getBoolean("IsSpam")
                val confidence = data.optInt("Confidence", -1)
                if (flagged) {
                    put(FactKind.REPORTS, data.text("CallType") ?: "Reported spam")
                    put(FactKind.REPORTED_NAME, data.text("ReportedCallerName"))
                    if (confidence in 0..10) put(FactKind.CONFIDENCE, "$confidence/10")
                }
            }
            CallerSource.PHONEBLOCK -> {
                val bucket = data.getJSONArray("numbers")
                for (i in 0 until bucket.length()) {
                    val item = bucket.getJSONObject(i)
                    if (item.text("phone") != number || item.optBoolean("archived") || item.optBoolean("whiteListed")) continue
                    val votes = item.getInt("votes")
                    if (votes > 0) put(FactKind.REPORTS, "$votes community votes")
                    break
                }
            }
            CallerSource.PLIVO -> {
                match(data.text("phone_number"))
                val carrier = data.optJSONObject("carrier")
                metadata(carrier?.text("name"), carrier?.text("type"), data.optJSONObject("country")?.text("name"))
            }
            CallerSource.SINCH -> {
                match(data.text("number"))
                val line = data.optJSONObject("line") ?: return@buildList
                if (line.optJSONObject("error") != null) throw IOException("Line type unavailable")
                metadata(line.text("carrier"), line.text("type"), data.text("countryCode"))
            }
            CallerSource.VONAGE -> {
                val carrier = data.getJSONObject("insights").getJSONObject("original_carrier")
                when (carrier.getJSONObject("status").getString("code")) {
                    "OK" -> metadata(carrier.text("name"), carrier.text("network_type"), carrier.text("country_code"), true)
                    "NOT_FOUND", "NO_COVERAGE", "UNSUPPORTED_NETWORK_TYPE" -> Unit
                    "UNAUTHORIZED", "INVALID_PURPOSE" -> throw LookupHttpError(403)
                    else -> throw IOException("Carrier insight unavailable")
                }
            }
            CallerSource.VERIPHONE -> {
                check(data.getString("status") == "success")
                if (!data.getBoolean("phone_valid")) return@buildList
                match(data.text("e164") ?: data.text("phone"))
                metadata(data.text("carrier"), data.text("phone_type"), data.text("phone_region") ?: data.text("country"), true)
            }
            CallerSource.NUMLOOKUP, CallerSource.NUMVERIFY -> {
                if (data.optJSONObject("error") != null) throw IOException("Number lookup returned an error")
                if (!data.getBoolean("valid")) return@buildList
                match(data.text("international_format"))
                metadata(data.text("carrier"), data.text("line_type"), data.text("location") ?: data.text("country_name"), true)
            }
            CallerSource.NEUTRINO -> {
                if (!data.getBoolean("valid")) return@buildList
                match(data.text("international-number"))
                metadata(data.text("prefix-network"), data.text("type"), data.text("location") ?: data.text("country"), true)
            }
            CallerSource.ABSTRACT -> {
                if (!data.getJSONObject("phone_validation").getBoolean("is_valid")) return@buildList
                match(data.getJSONObject("phone_format").text("international"))
                val carrier = data.optJSONObject("phone_carrier")
                val area = data.optJSONObject("phone_location")
                put(FactKind.NAME, data.optJSONObject("phone_registration")?.text("name"))
                metadata(carrier?.text("name"), carrier?.text("line_type"), area?.text("region") ?: area?.text("country_name"))
                put(FactKind.RISK, data.optJSONObject("phone_risk")?.text("risk_level"))
            }
            CallerSource.APIVERVE -> {
                check(data.getString("status") == "ok")
                val info = data.getJSONObject("data")
                if (!info.getBoolean("isValid")) return@buildList
                match(info.getJSONObject("formatted").text("e164"))
                metadata(info.text("carrier"), info.text("type"), info.text("location") ?: info.text("countryName"), true)
                score(info.opt("riskScore"))
            }
            else -> error("Source has a dedicated public adapter")
        }
    }

    private val separators = Regex("[ ().-]")
    private val control = Regex("[\\p{Cc}\\p{Cf}]+")
    private val genericNames = setOf("wireless caller", "cell phone", "private caller", "private name", "unavailable name", "toll free call")
    private val emptyValues = setOf("unknown", "unavailable", "anonymous", "restricted", "n/a", "null", "not available")
    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
