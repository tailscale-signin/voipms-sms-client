package net.kourlas.voipms_sms.lookup

import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.kourlas.voipms_sms.R

internal object TelnyxLookup {
    fun requestKey(activity: AppCompatActivity, callback: (String) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            isSaveEnabled = false
            hint = activity.getString(R.string.telnyx_key)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.telnyx_key).setView(input)
            .setMessage(R.string.telnyx_key_notice).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.telnyx_submit) { _, _ ->
                val token = input.text.toString().trim()
                input.text.clear()
                if (token.isNotBlank()) callback(token)
            }.create()
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
