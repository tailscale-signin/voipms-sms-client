package net.kourlas.voipms_sms.delivery

import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.kourlas.voipms_sms.preferences.AccountCredentials
import net.kourlas.voipms_sms.utils.HttpClientManager
import net.kourlas.voipms_sms.utils.awaitDecoded
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class VoiceMailbox(val lines: Set<String>, val zone: TimeZone) {
    val did: String get() = lines.singleOrNull().orEmpty()
}

internal fun callerNumber(value: String): String? {
    val literal = if ('<' in value) value.substringAfterLast('<').substringBefore('>') else value
    if (!literal.matches(Regex("[+0-9 ()-]+"))) return null
    return normalizeVoipMsDid(literal.filter(Char::isDigit)).takeIf { it.length == 10 }
}

private fun JSONObject.rows(key: String): List<JSONObject> = when (val value = opt(key)) {
    is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
    is JSONObject -> listOf(value)
    else -> emptyList()
}

internal object CallHistory {
    private suspend fun request(account: AccountCredentials, method: String, args: Map<String, String> = emptyMap()): JSONObject {
        val form = FormBody.Builder().add("api_username", account.email).add("api_password", account.password)
            .add("method", method)
        args.forEach { (name, value) -> form.add(name, value) }
        val request = Request.Builder().url("https://voip.ms/api/v1/rest.php").post(form.build()).build()
        val client = HttpClientManager.getInstance().client.newBuilder().followRedirects(false).build()
        return client.newCall(request).awaitDecoded { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            if (response.body.source().request(12L * 1024 * 1024)) throw IOException("Response too large")
            val body = JSONObject(response.body.string())
            val status = body.optString("status")
            if (status !in setOf("success","no_cdr","no_messages","no_voicemail_messages","no_mailbox","no_dids")) throw IOException(status)
            body
        }
    }

    private val eastern = TimeZone.getTimeZone("America/New_York")

    internal fun instant(row: JSONObject, zone: TimeZone): Long? {
        row.optString("origtime").toLongOrNull()?.takeIf { it in 1L..253402300799L }?.let { return it * 1000L }
        val raw = row.optString("date")
        val pattern = if (raw.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm:ss"
        val parser = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone; isLenient = false }
        val position = ParsePosition(0)
        return parser.parse(raw, position)?.time?.takeIf { position.index == raw.length }
    }

    internal fun isInbound(row: JSONObject): Boolean {
        val direction = row.optString("destination_type").trim().uppercase(Locale.ROOT)
        if (direction.isNotEmpty()) return direction == "IN" || direction.startsWith("IN:")
        return row.optString("description").trim().startsWith("Inbound", ignoreCase = true)
    }

    suspend fun missed(account: AccountCredentials, dids: Set<String>, since: Long): List<CallFeedItem> {
        val dates = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = eastern }
        val args = mapOf("date_from" to dates.format(Date(since)), "date_to" to dates.format(Date()),
            "answered" to "0", "noanswer" to "1", "busy" to "1", "failed" to "1", "timezone" to "-5")
        return request(account, "getCDR", args).rows("cdr").mapNotNull { row ->
            if (!isInbound(row)) return@mapNotNull null
            val did = callerNumber(row.optString("destination"))?.takeIf { it in dids } ?: return@mapNotNull null
            if (row.optString("disposition").uppercase(Locale.ROOT) !in setOf("NO ANSWER","NOANSWER","BUSY","FAILED","CONGESTION")) return@mapNotNull null
            val peer = callerNumber(row.optString("callerid")).orEmpty()
            val time = instant(row, eastern) ?: return@mapNotNull null
            val source = row.optString("uniqueid").ifBlank { "$time:${row.optString("callerid")}:${row.optString("disposition")}" }
            CallFeedItem("call:$did:$source", did, peer, time, false)
        }
    }

    internal fun mailboxRoutes(row: JSONObject): Set<String> = buildSet {
        row.optString("voicemail").takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }?.let(::add)
        for (field in listOf("routing", "failover_busy", "failover_unreachable", "failover_noanswer")) {
            val route = row.optString(field)
            if (route.startsWith("vm:")) route.removePrefix("vm:")
                .takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }?.let(::add)
        }
    }

    suspend fun mailboxes(account: AccountCredentials, dids: Set<String>): Map<String, VoiceMailbox> = coroutineScope {
        val mailboxList = async { request(account, "getVoicemails").rows("voicemails") }
        val lines = request(account, "getDIDsInfo").rows("dids").flatMap { row ->
            mailboxRoutes(row).map { mailbox -> mailbox to normalizeVoipMsDid(row.optString("did")) }
        }.groupBy({ it.first }, { it.second })
        val zones = mailboxList.await().associate { it.optString("mailbox") to it.optString("timezone") }
        lines.mapNotNull { (mailbox, matching) ->
            if (mailbox.toLongOrNull()?.let { it > 0 } != true) return@mapNotNull null
            val numbers = matching.distinct()
            if (!numbers.all { it in dids }) return@mapNotNull null
            val zoneName = zones[mailbox]
            val zone = when (zoneName?.lowercase(Locale.ROOT)) {
                "eastern", "eastern time" -> eastern
                "central", "central time" -> TimeZone.getTimeZone("America/Chicago")
                "mountain", "mountain time" -> TimeZone.getTimeZone("America/Denver")
                "pacific", "pacific time" -> TimeZone.getTimeZone("America/Los_Angeles")
                null, "" -> eastern
                else -> if (zoneName in TimeZone.getAvailableIDs()) TimeZone.getTimeZone(zoneName) else eastern
            }
            mailbox to VoiceMailbox(numbers.toSet(), zone)
        }.toMap()
    }

    suspend fun voicemail(account: AccountCredentials, mailbox: String, did: String, zone: TimeZone): List<CallFeedItem> {
        val rows = request(account,"getVoicemailMessages",mapOf("mailbox" to mailbox)).rows("messages")
        val copies = mutableMapOf<String,Int>()
        return rows.sortedBy { it.optInt("message_num") }.mapNotNull { row ->
            val peer = callerNumber(row.optString("callerid")).orEmpty()
            val time = instant(row, zone) ?: return@mapNotNull null
            val date = row.optString("date")
            val folder = row.optString("folder").ifBlank { "INBOX" }
            val slot = row.optString("message_num").takeIf { it.toIntOrNull() != null } ?: return@mapNotNull null
            val key = "vm:$mailbox:$date:$peer:${row.optString("duration")}"
            val copy = copies.getOrElse(key) { 0 }
            copies[key] = copy + 1
            CallFeedItem("$key:$copy", did, peer, time, true, mailbox, folder, slot, row.optString("callerid"), date)
        }
    }

    /** A message number is a mutable folder slot. Never select between indistinguishable recordings. */
    internal fun locateSlot(rows: List<JSONObject>, item: CallFeedItem): String? {
        val match = rows.singleOrNull { row ->
            val duration = row.optString("duration")
            val key = "vm:${item.mailbox}:${item.providerDate}:${item.peer}:$duration:"
            row.optString("mailbox") == item.mailbox && row.optString("folder") == item.folder &&
                row.optString("callerid") == item.caller && row.optString("date") == item.providerDate &&
                item.id.startsWith(key)
        }
        return match?.optString("message_num")?.takeIf { it.toIntOrNull()?.let { slot -> slot >= 0 } == true }
    }

    suspend fun audio(account: AccountCredentials, item: CallFeedItem): ByteArray {
        val folder = mapOf("mailbox" to item.mailbox, "folder" to item.folder)
        suspend fun currentSlot() = locateSlot(request(account, "getVoicemailMessages", folder).rows("messages"), item)
        val slot = currentSlot() ?: throw IOException("Voicemail unavailable")
        val file = request(account, "getVoicemailMessageFile", folder + ("message_num" to slot))
            .rows("message").singleOrNull() ?: throw IOException("Voicemail unavailable")
        if (file.optString("mailbox") != item.mailbox || file.optString("folder") != item.folder ||
            file.optString("message_num") != slot || currentSlot() != slot) throw IOException("Voicemail changed")
        return Base64.decode(file.getString("data"), Base64.DEFAULT).takeIf { it.isNotEmpty() }
            ?: throw IOException("Empty voicemail")
    }
}
