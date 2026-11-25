package net.kourlas.voipms_sms.tools

import androidx.core.content.edit
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.CalendarContract
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.sms.Message
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale

internal object MessageActions {
    fun templates(host: AppCompatActivity, did: String, editor: EditText) {
        val prefs = host.getSharedPreferences("line_templates", Context.MODE_PRIVATE)
        val data = JSONArray(prefs.getString(did, "[]"))
        val values = MutableList(data.length()) { data.getString(it) }
        fun save() { prefs.edit { putString(did, JSONArray(values).toString()) } }
        fun edit(idx: Int?) {
            val form = DialogForm(host)
            val field = form.text(R.string.tools_template, idx?.let { values[it] }.orEmpty(), true)
            val dialog = AlertDialog.Builder(host).setTitle(R.string.tools_template).setView(form.view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.tools_save) { _, _ ->
                    val body = field.text.toString().trim()
                    if (body.isNotEmpty()) {
                        if (idx == null) values.add(body) else values[idx] = body
                        save()
                    }
                }
            if (idx != null) dialog.setNeutralButton(R.string.tools_delete) { _, _ -> values.removeAt(idx); save() }
            dialog.show()
        }
        val dialog = AlertDialog.Builder(host).setTitle(host.getString(R.string.tools_templates_line, LineProfiles.label(host, did)))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.tools_add) { _, _ -> edit(null) }
        if (values.isEmpty()) dialog.setMessage(R.string.tools_no_templates) else {
            dialog.setItems(values.toTypedArray()) { _, i ->
                val start = editor.selectionStart.takeIf { it >= 0 } ?: editor.length()
                val end = editor.selectionEnd.takeIf { it >= 0 } ?: start
                editor.text.replace(minOf(start, end), maxOf(start, end), values[i])
            }.setNeutralButton(R.string.tools_edit) { _, _ ->
                AlertDialog.Builder(host).setTitle(R.string.tools_edit).setItems(values.toTypedArray()) { _, i -> edit(i) }.show()
            }
        }
        dialog.show()
    }
    fun translation(host: AppCompatActivity, original: String, onUse: ((String) -> Unit)? = null) {
        if (original.isBlank()) { Toast.makeText(host, R.string.tools_empty, Toast.LENGTH_SHORT).show(); return }
        val codes = listOf("en", "fr", "pt")
        val names = codes.map { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        val form = DialogForm(host)
        form.label(R.string.tools_translation_from)
        val source = form.spinner(names)
        source.contentDescription = host.getString(R.string.tools_translation_from)
        form.label(R.string.tools_translation_to)
        val dest = form.spinner(names, 1)
        dest.contentDescription = host.getString(R.string.tools_translation_to)
        AlertDialog.Builder(host).setTitle(R.string.tools_translate).setView(form.view)
            .setMessage(R.string.tools_translation_note)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.tools_translate) { _, _ ->
                val from = codes[source.selectedItemPosition]
                val to = codes[dest.selectedItemPosition]
                val pending = AlertDialog.Builder(host).setTitle(R.string.tools_translate)
                    .setMessage(R.string.tools_preparing_translation).setNegativeButton(android.R.string.cancel, null).show()
                fun review(result: String?) {
                    if (host.isFinishing || host.isDestroyed || !pending.isShowing) return
                    pending.dismiss()
                    val dialog = AlertDialog.Builder(host).setTitle(R.string.tools_translate)
                    if (result.isNullOrBlank()) dialog.setMessage(R.string.tools_translation_unavailable)
                    else {
                        dialog.setMessage(host.getString(R.string.tools_translation_result, original, result))
                        if (onUse != null) dialog.setPositiveButton(R.string.tools_use_draft) { _, _ -> onUse(result) }
                    }
                    dialog.setNegativeButton(android.R.string.cancel, null).show()
                }
                if (from == to) review(original) else LocalTranslator.run(host, original, from, to, ::review)
            }.show()
    }
    fun selected(host: AppCompatActivity, msg: Message) {
        val choices = mutableListOf(R.string.tools_translate, R.string.tools_event, R.string.tools_remind)
        val parcel = parcelUrl(msg.text)
        if (parcel != null) choices.add(R.string.tools_track)
        AlertDialog.Builder(host).setTitle(R.string.tools_message_actions)
            .setItems(choices.map { host.getString(it) }.toTypedArray()) { _, i ->
                when (choices[i]) {
                    R.string.tools_translate -> translation(host, msg.text)
                    R.string.tools_event -> calendar(host, msg)
                    R.string.tools_remind -> ThreadReminders.choose(host, msg.conversationId)
                    R.string.tools_track -> if (parcel != null) launch(host, Intent(Intent.ACTION_VIEW, parcel.toUri()))
                }
            }.show()
    }
    internal fun parcelUrl(text: String): String? {
        val ups = Regex("""\b1Z[0-9A-Z]{16}\b""", RegexOption.IGNORE_CASE).find(text)?.value?.uppercase(Locale.ROOT)
        if (ups != null) return "https://www.ups.com/track?tracknum=$ups"
        val postal = Regex("""\b[A-Z]{2}\d{9}CA\b""", RegexOption.IGNORE_CASE).find(text)?.value?.uppercase(Locale.ROOT)
        val ca = postal ?: if (Regex("Canada Post|Postes Canada", RegexOption.IGNORE_CASE).containsMatchIn(text)) Regex("""\b\d{16}\b""").find(text)?.value else null
        if (ca != null) return "https://www.canadapost-postescanada.ca/track-reperage/en#/details/$ca"
        if (text.contains("FedEx", true)) {
            val id = Regex("""\b(?:\d{12}|\d{15}|\d{20}|\d{22})\b""").find(text)?.value
            if (id != null) return "https://www.fedex.com/fedextrack/?trknbr=$id"
        }
        return null
    }
    private fun calendar(host: AppCompatActivity, msg: Message) {
        val body = msg.text
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, body.lineSequence().firstOrNull().orEmpty().take(80))
            .putExtra(CalendarContract.Events.DESCRIPTION, body)
        val date = Regex("""\b\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2})?\b""").find(body)?.value
        if (date != null) {
            val format = SimpleDateFormat(if (date.length == 10) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm", Locale.ROOT)
            format.isLenient = false
            runCatching { format.parse(date.replace('T', ' ')) }.getOrNull()?.let {
                intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.time)
                intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.time + 3600000L)
            }
        }
        launch(host, intent)
    }
    private fun launch(host: AppCompatActivity, intent: Intent) {
        try { host.startActivity(intent) } catch (_: ActivityNotFoundException) {
            Toast.makeText(host, R.string.tools_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
