package net.kourlas.voipms_sms.delivery

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.conversations.ConversationsActivity
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.tools.LineProfiles
import net.kourlas.voipms_sms.tools.ThreadReminders
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid
import java.security.MessageDigest

internal object CallAlerts {
    const val SHOW_CALL_HOME = "SHOW_CALL_HOME"
    const val GENERATION = "call_alert_generation"
    private const val MISSED = "missed_calls"
    private const val VOICEMAIL = "voicemail_alerts"
    private const val AFFECTED_THREADS = "call_alert_thread_scopes"
    private const val PREFIX = "voipms-call-event:"

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()
    private fun threadTag(id: ConversationId) = PREFIX + hash(id.getId()) + ":"

    internal fun tag(generation: Long, providerId: String, thread: ConversationId, voicemail: Boolean): String =
        threadTag(thread) + hash("$generation|$voicemail|$providerId")

    internal fun tapIntent(context: Context, thread: ConversationId, openThread: Boolean, generation: Long, tag: String): Intent {
        val target = if (openThread) {
            Intent(context, ConversationActivity::class.java)
                .putExtra(context.getString(R.string.conversation_did), thread.did)
                .putExtra(context.getString(R.string.conversation_contact), thread.contact)
        } else Intent(context, ConversationsActivity::class.java)
            .putExtra(SHOW_CALL_HOME, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return target.setAction(context.packageName + ".call." + tag).putExtra(GENERATION, generation)
    }

    /** The authenticated feed supplies only newly observed events, after establishing its initial baseline. */
    fun post(context: Context, added: List<CallFeedItem>, expectedGeneration: Long? = null) {
        if (added.isEmpty()) return
        val account = readAccountCredentials(context)?.takeIf { it.configured } ?: return
        if (expectedGeneration != null && expectedGeneration != account.generation) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = NotificationManagerCompat.from(context)
        val lines = getDids(context, onlyShowNotifications = true)
        if (!manager.areNotificationsEnabled() || lines.isEmpty()) return
        val system = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            for ((channel, title) in listOf(MISSED to R.string.call_alerts_missed, VOICEMAIL to R.string.call_alerts_voicemail)) {
                system.createNotificationChannel(NotificationChannel(channel, context.getString(title),
                    NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
            }
        }
        for (event in added) {
            val did = normalizeVoipMsDid(event.did)
            val peer = normalizeVoipMsDid(event.peer)
            if (event.id.isBlank()) continue
            val receivingLines = when {
                did.isNotEmpty() -> setOf(did)
                event.voicemail && event.mailbox.isNotEmpty() -> event.mailboxLines
                else -> emptySet()
            }
            val routable = peer.isNotEmpty() && peer.all { it in '0'..'9' }
            val thread = ConversationId(did, if (routable) peer else "")
            val scopes = receivingLines.map { line -> thread.copy(did = line) }
            if (scopes.isEmpty() || scopes.any { scoped ->
                    scoped.did !in lines || LineProfiles.quiet(context, scoped.did) ||
                        PrivateThreads.isPrivate(context, scoped) || ThreadReminders.hidden(context, scoped) ||
                        !Notifications.getInstance(context).getNotificationsEnabled(scoped)
                }) continue
            val channel = if (event.voicemail) VOICEMAIL else MISSED
            if (Build.VERSION.SDK_INT >= 26 && system.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) continue
            val tag = tag(account.generation, event.id, thread, event.voicemail)
            val intent = tapIntent(context, thread, routable && did.isNotEmpty() && event.voicemail, account.generation, tag)
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val title = context.getString(if (event.voicemail) R.string.calls_voicemail else R.string.calls_missed)
            val redacted = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_call_toolbar_24dp)
                .setContentTitle(title).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
            val from = if (routable) getFormattedPhoneNumber(peer) else context.getString(R.string.call_alerts_unknown)
            val lineLabel = if (did.isEmpty()) context.getString(R.string.calls_shared) else LineProfiles.label(context, did)
            val metadata = android.os.Bundle().apply { putStringArray(AFFECTED_THREADS, scopes.map(::threadTag).toTypedArray()) }
            val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_call_toolbar_24dp)
                .setContentTitle(title).setContentText(context.getString(R.string.call_alerts_route, from, lineLabel)).addExtras(metadata)
                .setWhen(event.receivedAt).setShowWhen(true).setOnlyAlertOnce(true).setAutoCancel(true)
                .setColor(LineProfiles.accent(context, did, context.getColor(R.color.notification_accent)))
                .setCategory(if (event.voicemail) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_MISSED_CALL)
                .setContentIntent(pending).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(redacted).build()
            // A tag identifies each provider event even when different calls share a timestamp or caller.
            try { manager.notify(tag, if (event.voicemail) 2 else 1, notification) }
            catch (_: SecurityException) { return } // Permission may change during dispatch.
        }
    }

    fun cancel(context: Context, thread: ConversationId? = null) {
        val prefix = if (thread == null) PREFIX else threadTag(thread)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { posted ->
            val postedTag = posted.tag ?: return@filter false
            postedTag.startsWith(PREFIX) && (postedTag.startsWith(prefix) ||
                posted.notification.extras.getStringArray(AFFECTED_THREADS)?.contains(prefix) == true)
        }.forEach { manager.cancel(it.tag, it.id) }
    }
}
