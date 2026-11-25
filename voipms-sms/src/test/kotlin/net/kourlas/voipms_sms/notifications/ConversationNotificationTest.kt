package net.kourlas.voipms_sms.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.receivers.MarkReadReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConversationNotificationTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val first = ConversationId("4165550100", "6475550123")
    private val second = ConversationId("4165550199", "6475550123")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun build(summary: Boolean = false, shortcut: String? = null): Notification =
        NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setGroup(context.getString(R.string.notifications_group_key))
            .setGroupSummary(summary)
            .setShortcutId(shortcut)
            .build()

    @Test
    fun coldProcessCancelsOnlyItsConversationAndRemovesSummaryAfterLastChild() {
        // Android already holds both children before the app ever creates its Notifications instance.
        val id = Notifications.MESSAGE_NOTIFICATION_ID
        manager.notify(first.notificationTag(), id, build())
        manager.notify(second.notificationTag(), id, build())
        manager.notify(Notifications.GROUP_NOTIFICATION_ID, build(summary = true))
        val notifications = Notifications.getInstance(context)
        notifications.cancelNotification(first)
        assertEquals(setOf(second.notificationTag(), null), manager.activeNotifications.map { it.tag }.toSet())
        notifications.cancelNotification(second)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun legacyChildWithAnOldPhonePrefixCanBeDismissedWithoutTouchingSyncWork() {
        manager.notify(42, build(shortcut = "1${first.did}_${first.contact}"))
        manager.notify(Notifications.GROUP_NOTIFICATION_ID, build(summary = true))
        manager.notify(Notifications.SYNC_DATABASE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        Notifications.getInstance(context).cancelNotification(first)
        assertEquals(listOf(Notifications.SYNC_DATABASE_NOTIFICATION_ID), manager.activeNotifications.map { it.id })
    }

    @Test
    fun identicalContactOnDifferentLinesHasIndependentPendingIntents() {
        fun pending(id: ConversationId): PendingIntent {
            val intent = Intent(context, MarkReadReceiver::class.java).setData(id.notificationTag().toUri())
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        assertEquals(pending(first), pending(first.copy()))
        assertNotEquals(pending(first), pending(second))
    }
}
