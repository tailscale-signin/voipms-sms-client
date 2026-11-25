package net.kourlas.voipms_sms.privacy

import androidx.core.content.edit
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.sms.InboxRules
import net.kourlas.voipms_sms.utils.getContactName
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber

internal object SpamReview {
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("inbox-review", 0)
    fun phrases(c: android.content.Context) = prefs(c).getStringSet("phrases", emptySet()).orEmpty().toSet()
    fun show(activity: AppCompatActivity, refresh: () -> Unit) {
        val existing = phrases(activity).sorted()
        val choices = arrayOf(activity.getString(R.string.spam_add_phrase), *existing.toTypedArray())
        AlertDialog.Builder(activity).setTitle(R.string.spam_rules).setItems(choices) { _, i ->
            if (i > 0) {
                AlertDialog.Builder(activity).setTitle(R.string.spam_remove).setMessage(existing[i-1])
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        prefs(activity).edit { putStringSet("phrases", phrases(activity) - existing[i - 1]) }
                        refresh()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            } else {
                val input = EditText(activity).apply { hint = activity.getString(R.string.spam_phrase); isSingleLine = true }
                AlertDialog.Builder(activity).setTitle(R.string.spam_add_phrase).setView(input)
                    .setPositiveButton(R.string.spam_preview) { _, _ ->
                        val phrase = input.text.toString().trim().take(120)
                        if (phrase.length >= 3) preview(activity, phrase, refresh)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun preview(activity: AppCompatActivity, phrase: String, refresh: () -> Unit) = activity.lifecycleScope.launch {
        val matches = withContext(Dispatchers.IO) {
            val profiles = JunkProfiles(activity)
            Database.getInstance(activity).inboxPreviews(getDids(activity, onlyShowInConversationsView = true), "", false, mutableMapOf())
                .filter { row ->
                    row.otherFolder == null && !PrivateThreads.isPrivate(activity, row.message.conversationId) &&
                        !row.hasReply && row.latestIncoming?.contains(phrase, ignoreCase = true) == true &&
                        getContactName(activity, row.message.contact) == null &&
                        net.kourlas.voipms_sms.contacts.LocalContacts.find(activity, row.message.contact) == null &&
                        !InboxRules.assess(row.latestIncoming, profiles.line(row.message.did)).shouldQuarantine(false, false)
                }
        }
        val list = matches.take(12).joinToString("\n") { getFormattedPhoneNumber(it.message.contact) }
        AlertDialog.Builder(activity).setTitle(R.string.spam_preview)
            .setMessage(activity.resources.getQuantityString(R.plurals.spam_preview_count, matches.size, matches.size, phrase) + "\n\n" + list)
            .setPositiveButton(R.string.spam_apply) { _, _ ->
                prefs(activity).edit { putStringSet("phrases", phrases(activity) + phrase) }
                refresh()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
