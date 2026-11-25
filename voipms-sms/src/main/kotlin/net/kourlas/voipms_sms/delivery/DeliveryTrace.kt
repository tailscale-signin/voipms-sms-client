package net.kourlas.voipms_sms.delivery

import android.content.Context
import androidx.core.content.edit
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.sms.workers.IncomingMessage
import java.util.UUID

/** Times belong to a line in one account session. No message text is retained. */
internal object DeliveryTrace {
    fun store(context: Context) = context.getSharedPreferences("line_delivery", Context.MODE_PRIVATE)
    fun prefix(context: Context, did: String) = "${readAccountCredentials(context)?.generation}.$did."

    fun begin(context: Context, did: String): String {
        val prefix = prefix(context, did)
        val token = "SMS-" + UUID.randomUUID().toString().take(8)
        store(context).edit {
            for (key in listOf("relay", "wake", "sync", "alert", "peer", "transport", "route")) remove(prefix + key)
            putLong(prefix + "start", System.currentTimeMillis())
            putString(prefix + "token", token)
        }
        return token
    }

    fun wake(context: Context, did: String, transport: String, relayTime: Long?) {
        val p = prefix(context, did)
        val prefs = store(context)
        if (prefs.getString(p+"token", null) == null || prefs.contains(p+"sync")) return
        val now = System.currentTimeMillis()
        prefs.edit {
            putLong(p+"wake", now)
            putString(p+"transport", transport)
            if (relayTime != null && relayTime > 0) putLong(p+"relay", relayTime)
        }
    }

    fun fetched(context: Context, did: String, rows: List<IncomingMessage>, route: String) {
        val p = prefix(context, did)
        val prefs = store(context)
        val token = prefs.getString(p+"token", null) ?: return
        if (prefs.contains(p+"sync")) return
        val row = rows.firstOrNull { it.isIncoming && it.text?.trim() == token } ?: return
        prefs.edit {
            putLong(p+"sync", System.currentTimeMillis())
            putString(p+"peer", row.contact)
            putString(p+"route", route)
        }
    }

    fun posted(context: Context, did: String, peer: String) {
        val p = prefix(context, did)
        val prefs = store(context)
        if (prefs.getString(p+"peer", null) != peer || prefs.contains(p+"alert")) return
        prefs.edit { putLong(p+"alert", System.currentTimeMillis()) }
    }
}
