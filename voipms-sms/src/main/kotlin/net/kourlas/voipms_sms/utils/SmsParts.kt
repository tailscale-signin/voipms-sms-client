package net.kourlas.voipms_sms.utils

import java.text.BreakIterator
import java.util.Locale

private val urlPattern = Regex("""(?:https?://|www\.)[^\s]+""", RegexOption.IGNORE_CASE)

/** Preserves every character, keeps URLs together when they fit, and never splits a Unicode code point. */
internal fun splitSms(text: String, limit: Int): List<String> {
    require(limit >= 4)
    if (text.toByteArray().size <= limit) return listOf(text)
    val result = mutableListOf<String>()
    val part = StringBuilder()
    var used = 0
    fun flush() {
        if (part.isNotEmpty()) result.add(part.toString())
        part.setLength(0)
        used = 0
    }
    fun append(token: String) {
        val size = token.toByteArray().size
        if (used + size > limit) flush()
        part.append(token)
        used += size
    }
    val boundary = BreakIterator.getCharacterInstance(Locale.ROOT)
    fun appendClusters(segment: String) {
        boundary.setText(segment)
        var left = boundary.first()
        var right = boundary.next()
        while (right != BreakIterator.DONE) {
            val cluster = segment.substring(left, right)
            if (cluster.toByteArray().size <= limit) {
                append(cluster)
            } else {
                // A combining sequence can itself exceed the provider's maximum.
                var pos = 0
                while (pos < cluster.length) {
                    val end = pos + Character.charCount(cluster.codePointAt(pos))
                    append(cluster.substring(pos, end))
                    pos = end
                }
            }
            left = right
            right = boundary.next()
        }
    }
    var start = 0
    for (match in urlPattern.findAll(text)) {
        appendClusters(text.substring(start, match.range.first))
        if (match.value.toByteArray().size <= limit) append(match.value) else appendClusters(match.value)
        start = match.range.last + 1
    }
    appendClusters(text.substring(start))
    flush()
    return result
}
internal fun hasOversizedSmsUrl(text: String, limit: Int): Boolean =
    urlPattern.findAll(text).any { it.value.toByteArray().size > limit }
