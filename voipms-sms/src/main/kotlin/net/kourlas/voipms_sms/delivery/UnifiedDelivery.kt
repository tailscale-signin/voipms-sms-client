package net.kourlas.voipms_sms.delivery

import android.content.Context
import androidx.core.content.edit
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.sms.workers.SyncWorker
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

internal object UnifiedLine {
    fun instance(context: Context, did: String) = "${readAccountCredentials(context)?.generation}/$did"
    fun register(context: Context, did: String, distributor: String) {
        UnifiedPush.saveDistributor(context, distributor)
        val id = instance(context, did)
        DeliveryTrace.store(context).edit { putString("up.state.$id", "Registering") }
        UnifiedPush.register(context, id, context.getString(net.kourlas.voipms_sms.R.string.app_name))
    }
    fun stop(context: Context, did: String) {
        val id = instance(context, did)
        UnifiedPush.unregister(context, id)
        DeliveryTrace.store(context).edit { remove("up.subscription.$id"); remove("up.state.$id") }
    }
}

class UnifiedDeliveryService : PushService() {
    private fun line(instance: String): String? = getDids(this, onlyRetrieveMessages = true)
        .firstOrNull { UnifiedLine.instance(this, it) == instance }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val did = line(instance) ?: return
        val keys = endpoint.pubKeySet ?: return
        val entry = JSONObject().put("endpoint", endpoint.url).put("keys", JSONObject()
            .put("p256dh", keys.pubKey).put("auth", keys.auth))
        DeliveryTrace.store(this).edit {
            putString("up.subscription.$instance", entry.toString())
            putString("up.state.$instance", "Registered")
        }
        SyncWorker.receivePush(this, did)
    }
    override fun onMessage(message: PushMessage, instance: String) {
        val did = line(instance) ?: return
        if (!message.decrypted || message.content.size > 1024) return
        val data = runCatching { JSONObject(message.content.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (data.optString("event") != "sync") return
        DeliveryTrace.wake(this, did, "UnifiedPush", data.optLong("callbackTime").takeIf { it > 0 })
        SyncWorker.receivePush(this, did)
    }
    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        DeliveryTrace.store(this).edit { putString("up.state.$instance", reason.name); remove("up.subscription.$instance") }
    }
    override fun onUnregistered(instance: String) {
        DeliveryTrace.store(this).edit { remove("up.state.$instance"); remove("up.subscription.$instance") }
    }
}
