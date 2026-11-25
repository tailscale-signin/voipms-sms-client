package net.kourlas.voipms_sms.privacy

import android.content.Context
import androidx.core.content.edit
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.sms.InboxRules
import net.kourlas.voipms_sms.sms.workers.accountKey

/** A snapshot per screen load or notification batch. Corrections never cross accounts or receiving lines. */
internal class JunkProfiles(private val context: Context) {
    private val phrases by lazy { SpamReview.phrases(context) }
    private val profiles = mutableMapOf<String, InboxRules.Profile>()
    private val account by lazy { readAccountCredentials(context)?.takeIf { it.configured }?.email?.let(::accountKey) }
    private val prefs by lazy { context.getSharedPreferences("local-junk-feedback", Context.MODE_PRIVATE) }

    fun line(did: String): InboxRules.Profile = profiles.getOrPut(did) {
        val key = account?.let { "$it/$did" }
        val corrections = key?.let { prefs.getString(it, "").orEmpty() }.orEmpty().lineSequence()
            .filter { it.length == 65 }.associate { it.substring(1) to (it[0] == '+') }
        InboxRules.Profile(phrases, corrections)
    }

    fun remember(did: String, fingerprint: String, other: Boolean) {
        val key = account?.let { "$it/$did" } ?: return
        synchronized(writeLock) {
            val records = prefs.getString(key, "").orEmpty().lines()
                .filter { it.length == 65 && it.substring(1) != fingerprint }.takeLast(127)
            val updated = records + ((if (other) "+" else "-") + fingerprint)
            prefs.edit { putString(key, updated.joinToString("\n")) }
            profiles.remove(did)
        }
    }

    private companion object {
        val writeLock = Any()
    }
}
