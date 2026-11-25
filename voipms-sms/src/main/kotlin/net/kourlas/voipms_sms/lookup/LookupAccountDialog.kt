package net.kourlas.voipms_sms.lookup

import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.kourlas.voipms_sms.R
import kotlin.math.roundToInt

/** Credentials are typed for one account session. No default values, view-state or autofill copies. */
internal fun manageLookupAccounts(
    activity: AppCompatActivity,
    connected: Set<LookupAccount>,
    change: (LookupAccount, LookupCredentials?) -> Unit
) {
    val accounts = LookupAccount.entries.sortedBy { it.brand.lowercase() }
    AlertDialog.Builder(activity).setTitle(R.string.cid_accounts)
        .setItems(accounts.map {
            if (it in connected) activity.getString(R.string.cid_connected, it.brand) else it.brand
        }.toTypedArray()) { _, index ->
            val account = accounts[index]
            val margin = (24 * activity.resources.displayMetrics.density).roundToInt()
            val form = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(margin, 0, margin, 0)
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
            }
            val fields = account.fields.associateWith { field ->
                EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or if (field.secret)
                        InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    isSingleLine = true
                    isSaveEnabled = false
                    filters = arrayOf(InputFilter.LengthFilter(4096))
                    setHint(field.label)
                    form.addView(this)
                }
            }
            val dialog = AlertDialog.Builder(activity).setTitle(account.brand)
                .setMessage(R.string.cid_credential_notice)
                .setView(ScrollView(activity).apply { addView(form) })
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.cid_save, null)
                .apply {
                    if (account in connected) setNeutralButton(R.string.cid_forget) { _, _ -> change(account, null) }
                }.create()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val values = fields.mapValues { it.value.text.toString().trim() }
                    val invalid = values.entries.firstOrNull { (field, value) ->
                        value.isEmpty() || value.any { it.code !in 33..126 } ||
                            (field == AccountField.SPACE && !ProviderLookup.validSpace(value.lowercase())) ||
                            (field == AccountField.PROJECT && (value == "." || value == ".." || '/' in value))
                    }
                    if (invalid != null) {
                        fields.getValue(invalid.key).error = activity.getString(R.string.cid_invalid_field)
                    } else {
                        change(account, LookupCredentials(values))
                        dialog.dismiss()
                    }
                }
            }
            dialog.setOnDismissListener { fields.values.forEach { it.text.clear() } }
            dialog.show()
        }.setNegativeButton(android.R.string.cancel, null).show()
}
