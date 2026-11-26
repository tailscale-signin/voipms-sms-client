package net.kourlas.voipms_sms.lookup

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallerSourcesTest {
    private val us = "+12025550123"
    private val ca = "+14165550123"
    private val credentials = LookupCredentials(AccountField.entries.associateWith { "unit-test" })

    private data class Fixture(val source: CallerSource, val host: String, val path: String, val json: String, val expected: CallerFact)

    @Test fun publicReportFeedUsesOnlyRepeatedReportsAndNoSenderInRequest() {
        val numbers = ReportedNumbers.numbers("""[{"caller_id_number":"(416) 555-0199","reports":"2"},
            {"caller_id_number":"4165550141","reports":"1"},{"caller_id_number":"unknown","reports":"4"}]""")
        assertEquals(setOf("+14165550199"), numbers)
        val request = ReportedNumbers.request(java.time.LocalDate.of(2026, 8, 28))
        assertEquals("opendata.fcc.gov", request.url.host)
        assertEquals("caller_id_number", request.url.queryParameter("\$group"))
        assertFalse(request.url.toString().contains("5550199"))
    }

    @Test fun providerContractsSendTheCorrectRequestAndExtractOnlyDisplayFields() {
        val name = CallerFact(FactKind.NAME, "Example")
        val carrier = CallerFact(FactKind.CARRIER, "Network")
        val holder = CallerFact(FactKind.RANGE_HOLDER, "Network")
        val line = CallerFact(FactKind.LINE, "mobile")
        val fixtures = listOf(
            Fixture(CallerSource.TELNYX_IDENTITY, "api.telnyx.com", "/v2/number_lookup/$us",
                """{"data":{"phone_number":"$us","caller_identity":{"name":"Example","confidence_score":70,"address":"Hidden property"}}}""", name),
            Fixture(CallerSource.TELNYX_CARRIER, "api.telnyx.com", "/v2/number_lookup/$us",
                """{"data":{"phone_number":"$us","carrier":{"name":"Network","type":"mobile"}}}""", carrier),
            Fixture(CallerSource.TWILIO, "lookups.twilio.com", "/v2/PhoneNumbers/$us",
                """{"valid":true,"phone_number":"$us","caller_name":{"caller_name":"Example","error_code":null}}""", name),
            Fixture(CallerSource.HIYA, "developer.hiya.com", "/v1/phones/phoneProfile",
                """{"number":"$us","name":"Example","carrier":null,"location":"Washington"}""", name),
            Fixture(CallerSource.SIGNALWIRE, "unit-test.signalwire.com", "/api/relay/rest/lookup/phone_number/$us",
                """{"e164":"$us","valid_number":true,"cnam":{"caller_id":"Example"},"carrier":{"lec":"Network"}}""", name),
            Fixture(CallerSource.TRESTLE, "api.trestleiq.com", "/3.1/cnam",
                """{"is_valid":true,"belongs_to":{"name":"Example","firstname":"Hidden property"}}""", name),
            Fixture(CallerSource.TRUECNAM, "api.truecnam.net", "/api/v1",
                """{"calling_number":"${us.drop(1)}","err":"0","name":"Example"}""", name),
            Fixture(CallerSource.IPQS, "www.ipqualityscore.com", "/api/json/phone",
                """{"success":true,"valid":true,"formatted":"$us","name":"Example","fraud_score":23,"associated_email_addresses":["Hidden property"]}""", name),
            Fixture(CallerSource.CALLCONTROL, "api.callcontrol.com", "/api/2015-11-01/Reputation/${us.drop(1)}",
                """{"IsSpam":true,"Confidence":8,"ReportedCallerName":"Example","CallType":"Telemarketing"}""", CallerFact(FactKind.REPORTED_NAME,"Example")),
            Fixture(CallerSource.PHONEBLOCK, "phoneblock.net", "/phoneblock/api/check-prefix",
                """{"numbers":[{"phone":"$ca","votes":90},{"phone":"$us","votes":5}]}""", CallerFact(FactKind.REPORTS,"5 community votes")),
            Fixture(CallerSource.PLIVO, "lookup.plivo.com", "/v1/Number/$us",
                """{"phone_number":"$us","carrier":{"name":"Network"},"country":{"name":"US"}}""", carrier),
            Fixture(CallerSource.SINCH, "lookup.api.sinch.com", "/v2/projects/unit-test/lookups",
                """{"number":"$us","line":{"type":"mobile","carrier":"Network"},"countryCode":"US"}""", carrier),
            Fixture(CallerSource.VONAGE, "api-eu.vonage.com", "/identity-insights/v1/requests",
                """{"insights":{"original_carrier":{"name":"Network","network_type":"mobile","status":{"code":"OK"}}}}""", holder),
            Fixture(CallerSource.VERIPHONE, "api.veriphone.io", "/v3/verify",
                """{"status":"success","phone_valid":true,"e164":"$us","carrier":"Network","phone_region":"Washington"}""", holder),
            Fixture(CallerSource.NUMLOOKUP, "api.numlookupapi.com", "/v1/validate/$us",
                """{"valid":true,"international_format":"$us","carrier":"Network"}""", holder),
            Fixture(CallerSource.NUMVERIFY, "api.apilayer.net", "/numverify/validate",
                """{"valid":true,"international_format":"$us","carrier":"Network"}""", holder),
            Fixture(CallerSource.NEUTRINO, "neutrinoapi.net", "/phone-validate",
                """{"valid":true,"international-number":"$us","type":"mobile"}""", line),
            Fixture(CallerSource.ABSTRACT, "phoneintelligence.abstractapi.com", "/v1/",
                """{"phone_validation":{"is_valid":true},"phone_format":{"international":"$us"},"phone_registration":{"name":"Example"},"phone_breaches":["Hidden property"]}""", name),
            Fixture(CallerSource.APIVERVE, "api.apiverve.com", "/v1/phonenumbervalidator",
                """{"status":"ok","data":{"isValid":true,"formatted":{"e164":"$us"},"type":"mobile","riskScore":0}}""", line)
        )
        assertEquals(CallerSource.entries.filter { it.account != null }.toSet(), fixtures.map { it.source }.toSet())
        for (fixture in fixtures) {
            val req = ProviderLookup.request(fixture.source, us, credentials)
            assertTrue(req.url.isHttps)
            assertEquals(fixture.host, req.url.host)
            assertEquals(fixture.path, req.url.encodedPath)
            val facts = ProviderLookup.parse(fixture.source, us, JSONObject(fixture.json))
            assertTrue(fixture.source.toString(), fixture.expected in facts)
            assertFalse(facts.any { it.value.contains("Hidden property") })
        }
        val h = ProviderLookup.request(CallerSource.HIYA, us, credentials)
        assertEquals("Bearer unit-test", h.header("Authorization"))
        assertEquals("unit-test", h.header("x-hiya-account-user-id"))
        assertEquals("caller_name", ProviderLookup.request(CallerSource.TWILIO, us, credentials).url.queryParameter("Fields"))
        val pb = ProviderLookup.request(CallerSource.PHONEBLOCK, "+4917650642602", credentials)
        assertEquals(setOf("sha1"), pb.url.queryParameterNames)
        assertEquals("3D1D", pb.url.queryParameter("sha1"))
        assertFalse(pb.url.toString().contains("491765"))
        for (provider in listOf(CallerSource.NEUTRINO, CallerSource.SINCH, CallerSource.VONAGE)) {
            assertEquals("POST", ProviderLookup.request(provider, us, credentials).method)
        }
    }

    @Test fun invalidResultsDoNotBecomeIdentityOrSpam() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLookup.parse(CallerSource.HIYA, ca, JSONObject("""{"number":"$us","name":"Another person"}"""))
        }
        assertTrue(ProviderLookup.parse(CallerSource.VERIPHONE, ca, JSONObject("""{"status":"success","phone_valid":false}""")).isEmpty())
        assertTrue(ProviderLookup.parse(CallerSource.TWILIO, us,
            JSONObject("""{"valid":true,"phone_number":"$us","caller_name":null}""")).isEmpty())
        assertTrue(ProviderLookup.parse(CallerSource.PHONEBLOCK, ca,
            JSONObject("""{"numbers":[{"phone":"$us","votes":8},{"phone":"$ca","archived":true,"votes":4}]}""")).isEmpty())
        assertFalse(ProviderLookup.validSpace("a.invalid.example"))
        assertFalse(ProviderLookup.validSpace("user@server"))
        assertFalse(ProviderLookup.validSpace("a/../b"))
        assertFalse(CallerSource.TWILIO.supports(ca, CallerSource.region(ca)))
        assertTrue(CallerSource.CANADA.supports(ca, CallerSource.region(ca)))
        assertFalse(CallerSource.SHADYCALL.supports(ca, CallerSource.region(ca)))
        assertTrue(CallerSource.TWILIO.supports(us, CallerSource.region(us)))
    }

    @Test fun canadianTableMatchesInServiceExchangeAndHandlesQuotedFields() {
        val data = """ "NPA","CO Code (NXX)","Status","Exchange Area","Province","Company","Remarks"
            416,200,"In Service","Toronto","ON","Example, Inc.",""
            416,201,"Available","Toronto","ON","Other",""
            613,200,"In Service","Ottawa","ON","Other",""
        """.trimIndent().trimStart()
        val result = CanadianCodeDirectory.parse("416", data)
        assertEquals(setOf("416200"), result.keys)
        assertEquals(listOf(CallerFact(FactKind.AREA,"Toronto, ON"), CallerFact(FactKind.RANGE_HOLDER,"Example, Inc.")), result["416200"])
        assertTrue(CanadianCodeDirectory.parse("416", data + "\n416,200,In Service,Other,ON,Other,").isEmpty())
    }

    @Test fun coverageCacheFailuresAndConcurrentRequestsAreIsolated() = runBlocking {
        var now = 1000L
        val active = AtomicInteger()
        val max = AtomicInteger()
        val calls = java.util.concurrent.ConcurrentHashMap<CallerSource, AtomicInteger>()
        val engine = CallerIdEngine({ now }) { source, _, _, _ ->
            calls.computeIfAbsent(source) { AtomicInteger() }.incrementAndGet()
            val count = active.incrementAndGet()
            max.updateAndGet { maxOf(count, it) }
            try {
                delay(12)
                when (source) {
                    CallerSource.HIYA -> throw LookupHttpError(429)
                    CallerSource.TRESTLE -> throw LookupHttpError(403)
                    CallerSource.WIKIDATA -> emptyList()
                    else -> listOf(CallerFact(FactKind.AREA, "example"))
                }
            } finally { active.decrementAndGet() }
        }
        val selected = CallerSource.entries + CallerSource.WIKIDATA
        val auth = LookupAccount.entries.associateWith { credentials } - LookupAccount.PLIVO
        val delivered = mutableListOf<SourceResult>()
        val first = engine.search(ca, selected, auth, Locale.CANADA) { delivered += it }
        assertEquals(first.toSet(), delivered.toSet())
        assertEquals(first.size, delivered.size)
        assertTrue(max.get() in 2..4)
        assertFalse(calls.containsKey(CallerSource.TWILIO))
        assertFalse(calls.containsKey(CallerSource.PLIVO))
        assertEquals(LookupStatus.LIMITED, first.single { it.source == CallerSource.HIYA }.status)
        assertEquals(LookupStatus.ACCESS, first.single { it.source == CallerSource.TRESTLE }.status)
        engine.search(ca, selected, auth, Locale.CANADA)
        assertEquals(2, calls.getValue(CallerSource.HIYA).get())
        assertEquals(1, calls.getValue(CallerSource.WIKIDATA).get())
        assertEquals(1, calls.getValue(CallerSource.CANADA).get())
        now += 120_001
        engine.search(ca, listOf(CallerSource.WIKIDATA, CallerSource.CANADA), auth, Locale.CANADA)
        assertEquals(2, calls.getValue(CallerSource.WIKIDATA).get())
        assertEquals(1, calls.getValue(CallerSource.CANADA).get())
        now += 900_000
        engine.search(ca, listOf(CallerSource.CANADA), auth, Locale.CANADA)
        assertEquals(2, calls.getValue(CallerSource.CANADA).get())
        val cancelled = CallerIdEngine { _, _, _, _ -> throw CancellationException() }
        assertThrows(CancellationException::class.java) {
            runBlocking { cancelled.search(us, listOf(CallerSource.DEVICE), emptyMap(), Locale.US) }
        }
        Unit
    }

    @Test fun replacingAProviderCredentialCannotReuseThePreviousAccountsResult() = runBlocking {
        val calls = AtomicInteger()
        val engine = CallerIdEngine { _, _, _, _ ->
            listOf(CallerFact(FactKind.NAME, "Listing " + calls.incrementAndGet()))
        }
        suspend fun lookup(key: LookupCredentials) = engine.search(us, listOf(CallerSource.TRESTLE),
            mapOf(LookupAccount.TRESTLE to key), Locale.US).single().facts.single().value
        assertEquals("Listing 1", lookup(credentials))
        assertEquals("Listing 1", lookup(credentials))
        assertEquals("Listing 2", lookup(LookupCredentials(mapOf(AccountField.KEY to "replacement"))))
    }

    @Test fun conflictingNamesAreNeverPromotedToTheInboxAndCarriersDoNotVoteOnIdentity() {
        fun row(s: CallerSource, k: FactKind, value: String) =
            SourceResult(s, listOf(CallerFact(k,value)), LookupStatus.FOUND, 1)
        val name = row(CallerSource.WIKIDATA, FactKind.NAME, "Example")
        val carrier = row(CallerSource.TELNYX_CARRIER, FactKind.CARRIER, "Different")
        val corroboration = row(CallerSource.TRESTLE, FactKind.NAME, "EXAMPLE")
        val result = summarizeCaller(listOf(carrier, corroboration, name))
        assertEquals("Example", result.fact?.value)
        assertEquals(listOf(CallerSource.WIKIDATA,CallerSource.TRESTLE), result.sources)
        val conflict = summarizeCaller(listOf(name, row(CallerSource.HIYA, FactKind.NAME, "Other")))
        assertTrue(conflict.conflictingNames)
        assertNull(conflict.fact)
        assertNull(summarizeCaller(listOf(row(CallerSource.DEVICE, FactKind.AREA,"Toronto"))).fact)
    }
}
