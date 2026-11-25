package net.kourlas.voipms_sms.carrier

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import net.kourlas.voipms_sms.R

/** Sends a draft to a handset composer. Only the person using that composer can send it. */
internal object CarrierMessages {
    private val dialString = Regex("""\+?[0-9]{2,15}""")

    internal fun draftIntent(recipient: String, draft: String): Intent? {
        val digits = recipient.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }
        if (!dialString.matches(digits)) return null
        // The VoIP.ms conversation store represents NANP numbers as ten digits.
        val number = when {
            digits.length == 10 && !digits.startsWith('+') -> "+1$digits"
            digits.length in 11..15 && digits.first() in '1'..'9' -> "+$digits"
            else -> digits
        }
        return Intent(Intent.ACTION_SENDTO, "smsto:$number".toUri()).apply {
            if (draft.isNotEmpty()) putExtra("sms_body", draft)
        }
    }

    fun open(activity: Activity, recipient: String, draft: String, hasAttachments: Boolean = false) {
        val intent = draftIntent(recipient, draft)
        if (intent == null) {
            Toast.makeText(activity, R.string.carrier_messages_invalid_number, Toast.LENGTH_LONG).show()
            return
        }

        val manager = activity.packageManager
        val handlers = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.packageName != activity.packageName }
        if (handlers.isEmpty()) {
            Toast.makeText(activity, R.string.carrier_messages_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(activity)
        val target = handlers.firstOrNull { it.activityInfo.packageName == defaultPackage }
            ?: handlers.singleOrNull()
        val name = target?.loadLabel(manager)?.toString()
            ?: activity.getString(R.string.carrier_messages_app_choice)
        val description = activity.getString(R.string.carrier_messages_disclosure, name, intent.data?.schemeSpecificPart)
        AlertDialog.Builder(activity)
            .setTitle(R.string.carrier_messages_action)
            .setMessage(if (hasAttachments) description + "\n\n" +
                activity.getString(R.string.carrier_messages_attachments) else description)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.carrier_messages_open) { _, _ ->
                val launch = if (target != null) intent.setPackage(target.activityInfo.packageName)
                else Intent.createChooser(intent, activity.getString(R.string.carrier_messages_choose))
                try {
                    activity.startActivity(launch)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.carrier_messages_unavailable, Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(activity, R.string.carrier_messages_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }
}
