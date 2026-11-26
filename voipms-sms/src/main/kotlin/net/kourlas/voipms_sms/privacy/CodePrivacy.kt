package net.kourlas.voipms_sms.privacy

import androidx.core.content.edit
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.sms.VerificationCode
import java.util.concurrent.TimeUnit

internal object CodePrivacy {
    private fun prefs(c: Context) = c.getSharedPreferences("code-privacy", 0)
    private fun receipts(c: Context) = c.getSharedPreferences("code-receipt-lines", Context.MODE_PRIVATE)
    fun days(c: Context) = prefs(c).getInt("days", 0)
    fun copyCode(context: Context, code: String): Boolean {
        val clip = ClipData.newPlainText(context.getString(R.string.clipboard_code), code)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        return try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            true
        } catch (_: RuntimeException) {
            // A device clipboard policy must not interrupt provider sync or notifications.
            false
        }
    }

    /** Keep a line's initial history cutoff across retries, process restarts and offline delivery. */
    @Synchronized
    fun watchLine(context: Context, generation: Long, did: String, selectedAt: Long? = null): Long {
        val lines = receipts(context)
        val sameAccount = lines.getLong("generation", -1) == generation
        if (sameAccount && lines.contains(did)) return lines.getLong(did, 0)
        // Already-configured lines can receive a code after an upgrade, before the first sync runs.
        @Suppress("DEPRECATION")
        val began = selectedAt ?: context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            .takeIf { it > 0 } ?: System.currentTimeMillis()
        val start = began / 1000 * 1000
        lines.edit {
            if (!sameAccount) clear()
            putLong("generation", generation)
            putLong(did, start)
        }
        return start
    }

    /** The provider insert transaction has committed, and the sync still owns this account generation. */
    @Synchronized
    fun copyReceived(context: Context, generation: Long, messages: List<Message>, since: Long,
                     now: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        if (messages.isEmpty() || !prefs.getBoolean("autoCopy", true)) return
        val after = maxOf(since, prefs.getLong("enabledSince", 0))
        val receipts = receipts(context)
        val sameAccount = receipts.getLong("lastGeneration", -1) == generation
        val lastDate = if (sameAccount) receipts.getLong("lastDate", -1) else -1
        val lastId = if (sameAccount) receipts.getLong("lastId", -1) else -1
        val newest = messages.asSequence().filter {
            it.isIncoming && it.date.time in after..now &&
                (it.date.time > lastDate || it.date.time == lastDate && it.databaseId > lastId)
        }.sortedWith(compareByDescending<Message> { it.date.time }.thenByDescending { it.databaseId })
            .mapNotNull { message ->
                VerificationCode.extract(message.text)?.takeUnless { PrivateThreads.isPrivate(context, message.conversationId) }
                    ?.let { code -> message to code }
            }.firstOrNull() ?: return
        if (copyCode(context, newest.second)) receipts.edit {
            putLong("lastGeneration", generation)
            putLong("lastDate", newest.first.date.time)
            putLong("lastId", newest.first.databaseId)
        }
    }

    fun notificationText(c: Context, message: Message): String {
        return if (prefs(c).getBoolean("mask", true) && message.isIncoming && VerificationCode.shouldMaskNotification(message.text))
            c.getString(R.string.code_masked) else message.summaryDisplayText
    }

    fun addSettings(fragment: PreferenceFragmentCompat) {
        val c = fragment.requireContext()
        fragment.preferenceScreen.addPreference(SwitchPreferenceCompat(c).apply {
            key = "auto_copy_verification_codes"
            title = c.getString(R.string.code_auto_copy)
            summary = c.getString(R.string.code_auto_copy_summary)
            isPersistent = false
            isChecked = prefs(c).getBoolean("autoCopy", true)
            setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                prefs(c).edit {
                    putBoolean("autoCopy", enabled)
                    if (enabled) putLong("enabledSince", System.currentTimeMillis() / 1000 * 1000)
                }
                true
            }
        })
        fragment.preferenceScreen.addPreference(SwitchPreferenceCompat(c).apply {
            title = c.getString(R.string.code_mask)
            isPersistent = false
            isChecked = prefs(c).getBoolean("mask", true)
            setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                prefs(c).edit { putBoolean("mask", enabled) }
                if (enabled) Notifications.getInstance(c).cancelMessageNotifications()
                true
            }
        })
        fragment.preferenceScreen.addPreference(ListPreference(c).apply {
            title = c.getString(R.string.code_retention)
            isPersistent = false
            entries = c.resources.getStringArray(R.array.code_retention_labels)
            entryValues = arrayOf("0", "1", "7", "30")
            value = days(c).toString()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, raw ->
                val value = (raw as String).toInt()
                prefs(c).edit { putInt("days", value) }
                val manager = WorkManager.getInstance(c)
                if (value == 0) manager.cancelUniqueWork("code-retention")
                else manager.enqueueUniquePeriodicWork("code-retention", ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<CodeRetentionWorker>(12, TimeUnit.HOURS).build())
                true
            }
        })
    }
}

class CodeRetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val days = CodePrivacy.days(applicationContext)
        if (days > 0) Database.getInstance(applicationContext).purgeExpiredCodes(
            System.currentTimeMillis() / 1000 - TimeUnit.DAYS.toSeconds(days.toLong()))
        return Result.success()
    }
}
