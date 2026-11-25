package net.kourlas.voipms_sms.conversation

import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.sms.ConversationId
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date

internal object SendPreflight {
    private fun key(generation: Long, thread: ConversationId, mms: Boolean) =
        "$generation/${thread.did}/${thread.contact}/" + if (mms) "MMS" else "SMS"

    fun estimate(context: Context, generation: Long, thread: ConversationId, mms: Boolean, count: Int): String {
        val prefs = context.getSharedPreferences("message_rate_estimates", Context.MODE_PRIVATE)
        val key = key(generation, thread, mms)
        val rate = prefs.getString(key, null)?.toBigDecimalOrNull() ?: return context.getString(R.string.outbox_rate_unknown)
        val total = rate.multiply(BigDecimal.valueOf(count.toLong())).stripTrailingZeros().toPlainString()
        val checked = DateFormat.getDateInstance().format(Date(prefs.getLong(key + "/date", 0)))
        return context.getString(R.string.outbox_estimated_charge, total, checked)
    }

    fun editRate(activity: FragmentActivity, generation: Long, thread: ConversationId, mms: Boolean, complete: () -> Unit) {
        val prefs = activity.getSharedPreferences("message_rate_estimates", Context.MODE_PRIVATE)
        val key = key(generation, thread, mms)
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setText(prefs.getString(key, ""))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.outbox_rate_title)
            .setMessage(R.string.outbox_rate_prompt).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.outbox_rate_remove) { _, _ ->
                prefs.edit {
                    remove(key)
                    remove(key + "/date")
                }
                complete()
            }.setPositiveButton(android.R.string.ok, null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim().replace(',', '.')
            val decimal = value.takeIf { it.matches(Regex("""[0-9]{1,5}(?:\.[0-9]{1,8})?""")) }?.toBigDecimalOrNull()
            if (decimal == null) input.error = activity.getString(R.string.outbox_rate_invalid)
            else {
                prefs.edit {
                    putString(key, decimal.toPlainString())
                    putLong(key + "/date", System.currentTimeMillis())
                }
                dialog.dismiss()
                complete()
            }
        }
    }
}
