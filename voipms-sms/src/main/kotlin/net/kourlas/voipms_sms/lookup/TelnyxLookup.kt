package net.kourlas.voipms_sms.lookup

import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.kourlas.voipms_sms.R
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

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

    suspend fun search(number: String, key: String, identity: Boolean): String? {
        val url = "https://api.telnyx.com/v2/number_lookup".toHttpUrl().newBuilder()
            .addPathSegment(number).addQueryParameter("type", if (identity) "caller-identity" else "carrier").build()
        return parse(number, identity, lookupResponse(url, "application/json", key))
    }

    internal fun parse(number: String, identity: Boolean, body: String): String? {
        val data = JSONObject(body).getJSONObject("data")
        require(data.optString("phone_number") == number)
        val details = data.optJSONObject(if (identity) "caller_identity" else "carrier") ?: return null
        val name = (details.opt("name") as? String)?.trim()?.take(120).orEmpty()
        val type = (details.opt("type") as? String)?.trim()?.take(60).orEmpty()
        val score = details.optInt("confidence_score", 0)
        val result = if (identity) {
            if (name.isEmpty() || score !in 50..90) return null
            "$name · confidence $score"
        } else listOf(name, type).filter { it.isNotBlank() }.joinToString(" · ")
        return result.takeIf { it.isNotBlank() }
    }
}
