package net.kourlas.voipms_sms.sms.workers

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.ToJson
import net.kourlas.voipms_sms.utils.normalizeVoipMsPhoneNumber
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val easternTime = TimeZone.getTimeZone("America/New_York")

internal data class RetrievalWindow(val from: String, val to: String)

data class IncomingMessage(
    val voipId: Long,
    val date: Date,
    val isIncoming: Boolean,
    val did: String,
    val contact: String,
    val text: String?,
    val media1: String?,
    val media2: String?,
    val media3: String?
)

internal fun accountKey(email: String): String =
    MessageDigest.getInstance("SHA-256").digest(email.trim().lowercase(Locale.ROOT).toByteArray())
        .toHexString()

internal fun preferenceDay(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

/** The start preference is a local calendar date. Provider timestamps are Eastern instants. */
internal fun retrievalWindows(
    startDay: String, checkedThrough: String?, now: Date
): List<RetrievalWindow> {
    val providerDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = easternTime }
    val start = Calendar.getInstance(easternTime, Locale.US)
    start.time = providerDay.parse(startDay)!!
    if (checkedThrough != null) {
        val overlap = Calendar.getInstance(easternTime, Locale.US)
        overlap.time = providerDay.parse(minOf(checkedThrough, providerDay.format(now)))!!
        // Re-read yesterday as well to catch delayed provider records at midnight.
        overlap.add(Calendar.DATE, -1)
        if (overlap.after(start)) start.time = overlap.time
    }

    val today = providerDay.parse(providerDay.format(now))!!
    val windows = mutableListOf<RetrievalWindow>()
    while (start.time <= today) {
        val from = providerDay.format(start.time)
        start.add(Calendar.DATE, 89) // Both API endpoints are inclusive.
        windows.add(RetrievalWindow(from, providerDay.format(minOf(start.time, today))))
        start.add(Calendar.DATE, 1)
    }
    return windows
}

@JsonClass(generateAdapter = true)
internal data class MessagesResponse(val status: String, val sms: MessageRows? = null)

internal data class MessageRows(val rows: List<MessageResponse?>)

/** VoIP.ms collection responses may contain a single object or an array. */
internal class MessageRowsAdapter {
    @FromJson
    fun fromJson(reader: JsonReader, adapter: JsonAdapter<MessageResponse>): MessageRows {
        fun readRow(): MessageResponse? {
            // A field of the wrong type must not discard other messages in the same response.
            val row = try {
                reader.peekJson().use { adapter.fromJson(it) }
            } catch (_: JsonDataException) {
                null
            }
            reader.skipValue()
            return row
        }
        if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            return MessageRows(listOf(readRow()))
        }
        reader.beginArray()
        val rows = mutableListOf<MessageResponse?>()
        while (reader.hasNext()) rows.add(readRow())
        reader.endArray()
        return MessageRows(rows)
    }

    @ToJson
    fun toJson(rows: MessageRows): List<MessageResponse?> = rows.rows
}

@JsonClass(generateAdapter = true)
internal data class MessageResponse(
    val date: String? = null,
    val id: String? = null,
    val type: String? = null,
    val did: String? = null,
    val contact: String? = null,
    val message: String? = null,
    val col_media1: String? = null,
    val col_media2: String? = null,
    val col_media3: String? = null,
    val media: List<String?>? = null
) {
    fun incoming(requestedDid: String, formatter: SimpleDateFormat): IncomingMessage? {
        val line = normalizeVoipMsPhoneNumber(requestedDid)
        val wireLine = did?.let(::normalizeVoipMsPhoneNumber) ?: return null
        fun nanp(number: String) = if (number.length == 11 && number.startsWith('1')) {
            number.substring(1)
        } else number
        if (line.isEmpty() || !line.all(Char::isDigit) || nanp(wireLine) != nanp(line)) return null
        val address = contact?.let(::normalizeVoipMsPhoneNumber) ?: return null
        if (address.isEmpty() || !address.all(Char::isDigit)) return null
        val voipId = id?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val incoming = when (type) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        val dateText = date ?: return null
        val position = ParsePosition(0)
        val timestamp = formatter.parse(dateText, position) ?: return null
        if (position.index != dateText.length) return null
        val attachments = (listOf(col_media1, col_media2, col_media3) + media.orEmpty())
            .filterNotNull().filter(String::isNotBlank).distinct()
        if (message.isNullOrEmpty() && attachments.isEmpty()) return null
        return IncomingMessage(
            voipId, timestamp, incoming, line, address, message,
            attachments.getOrNull(0), attachments.getOrNull(1), attachments.getOrNull(2)
        )
    }
}

internal fun messageDateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
    timeZone = easternTime
    isLenient = false
}
