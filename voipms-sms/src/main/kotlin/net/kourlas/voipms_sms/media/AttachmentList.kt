package net.kourlas.voipms_sms.media

import org.json.JSONArray

internal object AttachmentList {
    fun encode(items: List<String>): String = if (items.isEmpty()) "" else JSONArray(items).toString()

    fun decode(json: String): List<String> = if (json.isBlank()) emptyList() else runCatching {
        val items = JSONArray(json)
        List(items.length()) { items.getString(it) }.filter(String::isNotBlank)
    }.getOrDefault(emptyList())
}
