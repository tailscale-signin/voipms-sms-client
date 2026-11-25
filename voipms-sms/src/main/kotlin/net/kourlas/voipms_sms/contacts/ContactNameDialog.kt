package net.kourlas.voipms_sms.contacts

import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber

internal object ContactNameDialog {
    fun show(activity: FragmentActivity, raw: String, onSaved: () -> Unit) {
        val number = LocalContacts.number(raw) ?: return
        val field = EditText(activity).apply {
            id = R.id.local_contact_name_input
            setSingleLine()
            hint = activity.getString(R.string.local_contact_title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(LocalContacts.MAX_NAME))
            imeOptions = EditorInfo.IME_ACTION_DONE
            if (Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            isSaveEnabled = false
        }
        val margin = activity.resources.getDimensionPixelSize(R.dimen.double_margin)
        val panel = FrameLayout(activity).apply { setPadding(margin, 0, margin, 0); addView(field) }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.local_contact_title)
            .setMessage(getFormattedPhoneNumber(number)).setView(panel)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        field.requestFocus()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val host = activity.applicationContext
        activity.lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { LocalContacts.find(host, number) }
            if (dialog.isShowing && field.text.isEmpty() && existing != null) {
                field.setText(existing)
                field.selectAll()
            }
        }
        ok.setOnClickListener {
            val name = field.text.toString().trim()
            if (!LocalContacts.validateEntry(number, name)) {
                field.error = activity.getString(R.string.local_contact_invalid)
                return@setOnClickListener
            }
            ok.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { LocalContacts.save(host, number, name) }
                    dialog.dismiss()
                    onSaved()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    field.error = activity.getString(R.string.local_contact_error)
                    ok.isEnabled = true
                }
            }
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE && ok.isEnabled) { ok.performClick(); true } else false
        }
    }
}
