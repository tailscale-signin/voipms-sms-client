package net.kourlas.voipms_sms.privacy

import androidx.core.content.edit
import android.content.Context
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
    fun days(c: Context) = prefs(c).getInt("days", 0)
    fun notificationText(c: Context, message: Message): String {
        return if (prefs(c).getBoolean("mask", true) && message.isIncoming && VerificationCode.shouldMaskNotification(message.text))
            c.getString(R.string.code_masked) else message.summaryDisplayText
    }

    fun addSettings(fragment: PreferenceFragmentCompat) {
        val c = fragment.requireContext()
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
