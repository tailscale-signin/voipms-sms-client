package net.kourlas.voipms_sms.lookup

import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.kourlas.voipms_sms.R
import java.io.IOException
import java.util.Locale

internal enum class AccountField(val label: Int, val secret: Boolean = false) {
    KEY(R.string.cid_api_key, true), TOKEN(R.string.cid_token, true),
    PASSWORD(R.string.cid_password, true), PROJECT(R.string.cid_project), USER(R.string.cid_user),
    SPACE(R.string.cid_space), TWILIO_SID(R.string.cid_twilio_sid), PLIVO_ID(R.string.cid_plivo_id)
}

internal enum class LookupAccount(val brand: String, vararg val fields: AccountField) {
    TELNYX("Telnyx", AccountField.KEY),
    TWILIO("Twilio", AccountField.TWILIO_SID, AccountField.PASSWORD),
    HIYA("Hiya", AccountField.USER, AccountField.TOKEN),
    SIGNALWIRE("SignalWire", AccountField.SPACE, AccountField.PROJECT, AccountField.TOKEN),
    TRESTLE("Trestle", AccountField.KEY),
    TRUECNAM("TrueCNAM", AccountField.KEY, AccountField.PASSWORD),
    IPQS("IPQualityScore", AccountField.KEY),
    CALLCONTROL("Call Control", AccountField.KEY),
    PHONEBLOCK("PhoneBlock", AccountField.TOKEN),
    PLIVO("Plivo", AccountField.PLIVO_ID, AccountField.TOKEN),
    SINCH("Sinch", AccountField.PROJECT, AccountField.TOKEN),
    VONAGE("Vonage", AccountField.TOKEN),
    VERIPHONE("Veriphone", AccountField.KEY),
    NUMLOOKUP("numlookupapi", AccountField.KEY),
    NUMVERIFY("Numverify", AccountField.KEY),
    NEUTRINO("Neutrino API", AccountField.USER, AccountField.KEY),
    ABSTRACT("Abstract API", AccountField.KEY),
    APIVERVE("APIVerve", AccountField.KEY)
}

// Only users' own provider credentials. Deliberately no data-class toString or persistence.
internal class LookupCredentials(private val values: Map<AccountField, String>) {
    // A result can keep this scope without retaining a one-use API key.
    val cacheScope = Any()
    operator fun get(field: AccountField): String = values.getValue(field)
}

internal enum class CallerSource(val title: Int, val account: LookupAccount? = null) {
    DEVICE(R.string.cid_device),
    LOCAL_CALLING_GUIDE(R.string.cid_lcg),
    CANADA(R.string.cid_canada),
    WIKIDATA(R.string.cid_wikidata),
    SHADYCALL(R.string.cid_shadycall),
    TELNYX_CARRIER(R.string.telnyx_carrier, LookupAccount.TELNYX),
    TELNYX_IDENTITY(R.string.telnyx_identity, LookupAccount.TELNYX),
    TWILIO(R.string.cid_twilio, LookupAccount.TWILIO),
    HIYA(R.string.cid_hiya, LookupAccount.HIYA),
    SIGNALWIRE(R.string.cid_signalwire, LookupAccount.SIGNALWIRE),
    TRESTLE(R.string.cid_trestle, LookupAccount.TRESTLE),
    TRUECNAM(R.string.cid_truecnam, LookupAccount.TRUECNAM),
    IPQS(R.string.cid_ipqs, LookupAccount.IPQS),
    CALLCONTROL(R.string.cid_callcontrol, LookupAccount.CALLCONTROL),
    PHONEBLOCK(R.string.cid_phoneblock, LookupAccount.PHONEBLOCK),
    PLIVO(R.string.cid_plivo, LookupAccount.PLIVO),
    SINCH(R.string.cid_sinch, LookupAccount.SINCH),
    VONAGE(R.string.cid_vonage, LookupAccount.VONAGE),
    VERIPHONE(R.string.cid_veriphone, LookupAccount.VERIPHONE),
    NUMLOOKUP(R.string.cid_numlookup, LookupAccount.NUMLOOKUP),
    NUMVERIFY(R.string.cid_numverify, LookupAccount.NUMVERIFY),
    NEUTRINO(R.string.cid_neutrino, LookupAccount.NEUTRINO),
    ABSTRACT(R.string.cid_abstract, LookupAccount.ABSTRACT),
    APIVERVE(R.string.cid_apiverve, LookupAccount.APIVERVE);

    val attribution get() = account?.brand ?: when (this) {
        DEVICE -> "libphonenumber"
        LOCAL_CALLING_GUIDE -> "Local Calling Guide"
        CANADA -> "Canadian Numbering Administrator"
        WIKIDATA -> "Wikidata"
        SHADYCALL -> "ShadyCall"
        else -> error("Missing attribution")
    }

    fun supports(number: String, region: String?): Boolean = when (this) {
        LOCAL_CALLING_GUIDE -> LocalCallingGuide.prefix(number) != null
        CANADA -> region == "CA"
        TELNYX_IDENTITY -> region == "CA" || region == "US"
        TRUECNAM -> number.startsWith("+1")
        SHADYCALL, TWILIO -> region == "US"
        APIVERVE -> region?.length == 2
        else -> true
    }

    companion object {
        fun region(number: String): String? = PhoneNumberUtil.getInstance().run {
            runCatching { getRegionCodeForNumber(parse(number, null)) }.getOrNull()
        }
    }
}

internal enum class FactKind(val title: Int) {
    NAME(R.string.cid_name), REPORTED_NAME(R.string.cid_reported_name), AREA(R.string.cid_area), CARRIER(R.string.cid_carrier),
    RANGE_HOLDER(R.string.cid_range_holder), LINE(R.string.cid_line), REPORTS(R.string.cid_reports),
    RISK(R.string.cid_risk), CONFIDENCE(R.string.cid_confidence)
}
internal data class CallerFact(val kind: FactKind, val value: String)
internal enum class LookupStatus(val title: Int) {
    FOUND(R.string.cid_found), EMPTY(R.string.cid_empty), ACCESS(R.string.cid_access),
    LIMITED(R.string.cid_limited), UNAVAILABLE(R.string.cid_unavailable)
}
internal data class SourceResult(
    val source: CallerSource, val facts: List<CallerFact>, val status: LookupStatus, val time: Long
)
internal class LookupHttpError(val status: Int) : IOException("Lookup returned HTTP " + status)

/** An account owns its own engine instance. Only successful lookups are reused. */
internal class CallerIdEngine(
    private val now: () -> Long = System::currentTimeMillis,
    private val query: suspend (CallerSource, String, LookupCredentials?, Locale) -> List<CallerFact> = ::lookupCaller
) {
    private data class Key(val number: String, val source: CallerSource, val locale: Locale, val credentialScope: Any?)
    private val cache = object : LinkedHashMap<Key, SourceResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, SourceResult>) = size > 256
    }
    private val workers = Semaphore(4)

    private fun fresh(result: SourceResult, time: Long): Boolean {
        val age = time - result.time
        return age in 0 until if (result.status == LookupStatus.EMPTY) 120_000L else 900_000L
    }

    suspend fun search(
        number: String,
        sources: Collection<CallerSource>,
        credentials: Map<LookupAccount, LookupCredentials>,
        locale: Locale,
        onResult: (SourceResult) -> Unit = {}
    ): List<SourceResult> = coroutineScope {
        require(WikidataDirectory.number(number) == number)
        val region = CallerSource.region(number)
        val eligible = sources.distinct().filter { it.supports(number, region) }
        eligible.map { source ->
            async {
                val result = withContext(Dispatchers.IO) {
                    val account = source.account
                    val secret = credentials[account]
                    val key = Key(number, source, locale, secret?.cacheScope)
                    if (account != null && secret == null) {
                        return@withContext SourceResult(source, emptyList(), LookupStatus.ACCESS, now())
                    }
                    workers.withPermit {
                        synchronized(this@CallerIdEngine) { cache[key]?.takeIf { fresh(it, now()) } }
                            ?.let { return@withPermit it }
                        val result = try {
                            val facts = query(source, number, secret, locale)
                            SourceResult(source, facts, if (facts.isEmpty()) LookupStatus.EMPTY else LookupStatus.FOUND, now())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            val status = when ((e as? LookupHttpError)?.status) {
                                401, 402, 403 -> LookupStatus.ACCESS
                                429 -> LookupStatus.LIMITED
                                else -> LookupStatus.UNAVAILABLE
                            }
                            SourceResult(source, emptyList(), status, now())
                        }
                        if (result.status == LookupStatus.FOUND || result.status == LookupStatus.EMPTY) {
                            synchronized(this@CallerIdEngine) { cache[key] = result }
                        }
                        result
                    }
                }
                onResult(result)
                result
            }
        }.awaitAll()
    }
}

internal data class CallerSummary(val fact: CallerFact?, val sources: List<CallerSource>, val conflictingNames: Boolean)

internal fun summarizeCaller(results: List<SourceResult>): CallerSummary {
    val online = results.filter { it.source != CallerSource.DEVICE }.sortedBy { it.source.ordinal }
    val ordered = listOf(FactKind.NAME, FactKind.REPORTS, FactKind.AREA, FactKind.CARRIER, FactKind.RANGE_HOLDER, FactKind.LINE, FactKind.RISK)
    for (kind in ordered) {
        val matches = online.flatMap { result -> result.facts.filter { it.kind == kind }.map { it to result.source } }
        if (matches.isEmpty()) continue
        val first = matches.first().first
        val agreeing = matches.filter { it.first.value.equals(first.value, ignoreCase = true) }
        if (kind == FactKind.NAME && agreeing.size != matches.size) return CallerSummary(null, matches.map { it.second }.distinct(), true)
        return CallerSummary(first, agreeing.map { it.second }.distinct(), false)
    }
    return CallerSummary(null, emptyList(), false)
}

private suspend fun lookupCaller(source: CallerSource, number: String, credentials: LookupCredentials?, locale: Locale): List<CallerFact> {
    fun single(kind: FactKind, text: String?) = text?.let { listOf(CallerFact(kind, it)) }.orEmpty()
    return when (source) {
        CallerSource.DEVICE -> single(FactKind.AREA, NumberOrigin.offline(number, locale))
        CallerSource.LOCAL_CALLING_GUIDE -> single(FactKind.AREA, LocalCallingGuide.search(number))
        CallerSource.CANADA -> CanadianCodeDirectory.search(number)
        CallerSource.WIKIDATA -> single(FactKind.NAME, WikidataDirectory.search(number))
        CallerSource.SHADYCALL -> ShadyCallReputation.search(number)?.let {
            listOf(CallerFact(FactKind.REPORTS, "${it.complaints} US complaints · ${it.communityReports} community reports"))
        }.orEmpty()
        else -> ProviderLookup.search(source, number, requireNotNull(credentials))
    }
}
