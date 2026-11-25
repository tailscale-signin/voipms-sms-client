package net.kourlas.voipms_sms.notifications

import android.content.Context
import androidx.preference.PreferenceManager
import net.kourlas.voipms_sms.R

/** Last registration result for each number. A successful setup is not a delivery receipt. */
enum class PushRegistration(val description: Int) {
    READY(R.string.push_ready),
    CUSTOM_CALLBACK(R.string.push_custom_callback),
    SMS_DISABLED(R.string.push_sms_disabled),
    UNKNOWN_SETTINGS(R.string.push_unknown_settings),
    PROVIDER_REJECTED(R.string.push_provider_rejected),
    RETRYING(R.string.push_retrying),
    FAILED(R.string.push_failed);

    companion object {
        const val KEY = "push_registration_results"

        fun read(context: Context): Map<String, PushRegistration> {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { entry ->
                val value = entries.find { it.name == entry.substringAfter('|') }
                value?.let { entry.substringBefore('|') to it }
            }.toMap()
        }
    }
}

/** FCM topics match the relay's ten-digit North American destination numbers. */
fun pushTopicForDid(did: String): String? {
    val digits = when {
        did.startsWith("+1") -> did.drop(2)
        did.length == 11 && did.startsWith("1") -> did.drop(1)
        else -> did
    }
    return if (digits.length == 10 && digits.all { it in '0'..'9' }) "did-$digits" else null
}
