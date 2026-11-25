package net.kourlas.voipms_sms.delivery

import android.content.Intent
import android.media.MediaPlayer
import android.util.Base64
import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.preferences.AccountCredentials
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.utils.HttpClientManager
import net.kourlas.voipms_sms.utils.awaitDecoded
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Date
import java.util.TimeZone

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
            if (status !in setOf("success","no_cdr","no_messages","no_voicemail_messages","no_dids")) throw IOException(status)
            body
        }
    }

    private val eastern = TimeZone.getTimeZone("America/New_York")

    private fun instant(row: JSONObject, zone: TimeZone): Long? {
        row.optString("origtime").toLongOrNull()?.takeIf { it > 0 }?.let { return it * 1000L }
        return runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = zone; isLenient = false
        }.parse(row.getString("date"))?.time }.getOrNull()
    }

    suspend fun missed(account: AccountCredentials, dids: Set<String>, since: Long): List<CallFeedItem> {
        val dates = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = eastern }
        val args = mapOf("date_from" to dates.format(Date(since)), "date_to" to dates.format(Date()),
            "answered" to "0", "noanswer" to "1", "busy" to "1", "failed" to "1", "timezone" to "-5")
        return request(account, "getCDR", args).rows("cdr").mapNotNull { row ->
            val did = callerNumber(row.optString("destination"))?.takeIf { it in dids } ?: return@mapNotNull null
            if (row.optString("disposition").uppercase(Locale.ROOT) !in setOf("NO ANSWER","NOANSWER","BUSY","FAILED","CONGESTION")) return@mapNotNull null
            val peer = callerNumber(row.optString("callerid")).orEmpty()
            val time = instant(row, eastern) ?: return@mapNotNull null
            val source = row.optString("uniqueid").ifBlank { "$time:${row.optString("callerid")}:${row.optString("disposition")}" }
            CallFeedItem("call:$did:$source", did, peer, time, false)
        }
    }

    suspend fun mailboxes(account: AccountCredentials, dids: Set<String>): Map<String, Pair<String,TimeZone>> = coroutineScope {
        val mailboxList = async { request(account, "getVoicemails").rows("voicemails") }
        val lines = request(account, "getDIDsInfo").rows("dids").groupBy { it.optString("voicemail") }
        val zones = mailboxList.await().associate { it.optString("mailbox") to it.optString("timezone") }
        lines.mapNotNull { (mailbox, matching) ->
            if (mailbox.toLongOrNull()?.let { it > 0 } != true) return@mapNotNull null
            val numbers = matching.map { normalizeVoipMsDid(it.optString("did")) }.distinct()
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
            mailbox to (numbers.singleOrNull().orEmpty() to zone)
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

    suspend fun audio(account: AccountCredentials, item: CallFeedItem): ByteArray {
        val rows = request(account,"getVoicemailMessages",mapOf("mailbox" to item.mailbox,"folder" to item.folder)).rows("messages")
        val same = rows.filter { it.optString("callerid") == item.caller && it.optString("date") == item.providerDate }
        val row = same.singleOrNull() ?: same.singleOrNull { it.optString("message_num") == item.slot } ?: throw IOException("Voicemail unavailable")
        val args = mapOf("mailbox" to item.mailbox,"folder" to item.folder,"message_num" to row.optString("message_num"))
        val file = request(account,"getVoicemailMessageFile",args).rows("message").singleOrNull() ?: throw IOException("Voicemail unavailable")
        return Base64.decode(file.getString("data"), Base64.DEFAULT)
    }
}
