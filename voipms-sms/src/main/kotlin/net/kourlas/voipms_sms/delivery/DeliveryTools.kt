package net.kourlas.voipms_sms.delivery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.preferences.getDids
import org.unifiedpush.android.connector.UnifiedPush
import java.text.DateFormat
import java.util.Date

internal class DeliveryTools(private val activity: FragmentActivity) {
    private val prefs get() = DeliveryTrace.store(activity)

    fun selectLine(callback: (String) -> Unit) {
        val lines = getDids(activity, onlyRetrieveMessages = true).sorted()
        when (lines.size) {
            0 -> Toast.makeText(activity, R.string.push_no_dids, Toast.LENGTH_LONG).show()
            1 -> callback(lines.first())
            else -> AlertDialog.Builder(activity).setTitle(R.string.delivery_choose_line)
                .setItems(lines.toTypedArray()) { _, i -> callback(lines[i]) }.show()
        }
    }

    fun trace(did: String) {
        val p = DeliveryTrace.prefix(activity, did)
        fun time(key: String) = prefs.getLong(p + key, 0).takeIf { it > 0 }
            ?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: activity.getString(R.string.delivery_waiting)
        val token = prefs.getString(p + "token", null)
        val message = if (token == null) activity.getString(R.string.delivery_explain, did)
        else activity.getString(R.string.delivery_trace, token, did, time("relay"),
            time("wake"), prefs.getString(p + "transport", "—"), time("sync"),
            prefs.getString(p + "route", "—"), time("alert"))
        AlertDialog.Builder(activity).setTitle(R.string.delivery_test_title).setMessage(message)
            .setPositiveButton(if (token == null) R.string.delivery_start else R.string.delivery_refresh) { _, _ ->
                if (token == null) DeliveryTrace.begin(activity, did)
                trace(did)
            }.setNegativeButton(android.R.string.cancel, null).apply {
                if (token != null) setNeutralButton(R.string.delivery_restart) { _, _ -> DeliveryTrace.begin(activity, did); trace(did) }
            }.show()
    }

    fun distributor(did: String) {
        val id = UnifiedLine.instance(activity, did)
        val entry = prefs.getString("up.subscription.$id", null)
        val packages = UnifiedPush.getDistributors(activity)
        val names = packages.map { name ->
            runCatching { activity.packageManager.getApplicationLabel(
                activity.packageManager.getApplicationInfo(name, 0)).toString() }.getOrDefault(name)
        }
        val actions = names + listOfNotNull(if (entry != null) activity.getString(R.string.delivery_copy_config) else null) +
            if (prefs.contains("up.state.$id")) listOf(activity.getString(R.string.delivery_disconnect)) else emptyList()
        AlertDialog.Builder(activity).setTitle(R.string.delivery_google_free)
            .apply {
                if (actions.isEmpty()) setMessage(R.string.delivery_no_distributor)
                else setItems(actions.toTypedArray()) { _, i ->
                    when {
                        i < packages.size -> {
                            UnifiedLine.register(activity, did, packages[i])
                            Toast.makeText(activity, R.string.delivery_registration_started, Toast.LENGTH_LONG).show()
                        }
                        entry != null && i == packages.size -> {
                            val clip = ClipData.newPlainText("UnifiedPush", "{\"$did\":[$entry]}")
                            clip.description.extras = PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                            Toast.makeText(activity, R.string.delivery_config_copied, Toast.LENGTH_LONG).show()
                        }
                        else -> UnifiedLine.stop(activity, did)
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    fun companion() {
        val saved = prefs.getString("companion_origin", "").orEmpty()
        val field = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(); setText(saved); hint = "https://sms.example.org" }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.delivery_companion).setView(field)
            .setPositiveButton(R.string.delivery_open, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = field.text.toString().trim().toUri()
                if (url.scheme != "https" || url.host.isNullOrBlank() || url.userInfo != null ||
                    url.query != null || url.fragment != null) { field.error = activity.getString(R.string.delivery_https); return@setOnClickListener }
                val launched = runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, url)) }.isSuccess
                if (launched) { prefs.edit { putString("companion_origin", url.toString()) }; dialog.dismiss() }
                else field.error = activity.getString(R.string.delivery_browser_missing)
            }
        }
        dialog.show()
    }
}
