/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2021 Michael Kourlas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import androidx.work.WorkManager
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.CustomApplication
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.conversation.ConversationBubbleActivity
import net.kourlas.voipms_sms.conversations.ConversationsActivity
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.preferences.accountConfigured
import net.kourlas.voipms_sms.preferences.getDidShowNotifications
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getNotificationSound
import net.kourlas.voipms_sms.preferences.getNotificationVibrateEnabled
import net.kourlas.voipms_sms.preferences.getNotificationsEnabled
import net.kourlas.voipms_sms.preferences.removePreference
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.sms.receivers.MarkReadReceiver
import net.kourlas.voipms_sms.sms.receivers.SendMessageReceiver
import net.kourlas.voipms_sms.utils.applyCircularMask
import net.kourlas.voipms_sms.utils.getContactName
import net.kourlas.voipms_sms.utils.getContactPhotoAdaptiveBitmap
import net.kourlas.voipms_sms.utils.getContactPhotoBitmap
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid
import java.util.Date
import java.util.UUID

private const val TAG = "Notifications"

// Also used as Intent.data so Android can distinguish pending intents without a hash.
internal fun ConversationId.notificationTag(): String = "voipms-sms://notifications/conversation/${getId()}"

/**
 * Single-instance class used to send notifications when new SMS messages
 * are received.
 */
class Notifications private constructor(private val context: Context) {
    // Helper variables
    private val notificationManager = context.getSystemService(
        Context.NOTIFICATION_SERVICE
    ) as NotificationManager

    /**
     * Attempts to create a notification channel for the specified DID.
     */
    fun createDidNotificationChannel(did: String, contact: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the default notification channel if it doesn't already
            // exist
            createDefaultNotificationChannel()
            val defaultChannel = notificationManager.getNotificationChannel(
                context.getString(R.string.notifications_channel_default)
            )

            val channelGroup = NotificationChannelGroup(
                context.getString(
                    R.string.notifications_channel_group_did,
                    did
                ),
                getFormattedPhoneNumber(did)
            )
            notificationManager.createNotificationChannelGroup(channelGroup)

            val contactName = getContactName(context, contact)
            val channel = NotificationChannel(
                context.getString(
                    R.string.notifications_channel_contact,
                    did, contact
                ),
                contactName ?: getFormattedPhoneNumber(contact),
                defaultChannel.importance
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                channel.setConversationId(
                    context.getString(R.string.notifications_channel_default),
                    ConversationId(did, contact).getId()
                )
            }
            channel.enableLights(defaultChannel.shouldShowLights())
            channel.lightColor = defaultChannel.lightColor
            channel.enableVibration(defaultChannel.shouldVibrate())
            channel.vibrationPattern = defaultChannel.vibrationPattern
            channel.lockscreenVisibility = defaultChannel.lockscreenVisibility
            channel.setBypassDnd(defaultChannel.canBypassDnd())
            channel.setSound(
                defaultChannel.sound,
                defaultChannel.audioAttributes
            )
            channel.setShowBadge(defaultChannel.canShowBadge())
            channel.group = context.getString(
                R.string.notifications_channel_group_did,
                did
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                channel.setAllowBubbles(true)
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Attempts to create the default notification channel.
     */
    fun createDefaultNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createOtherNotificationChannelGroup()

            val channel = NotificationChannel(
                context.getString(R.string.notifications_channel_default),
                context.getString(R.string.notifications_channel_default_title),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.enableLights(true)
            channel.lightColor = context.getColor(R.color.notification_accent)
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 250, 250, 250)
            channel.setShowBadge(true)
            channel.group = context.getString(
                R.string.notifications_channel_group_other
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                channel.setAllowBubbles(true)
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates the notification channel for the notification displayed during
     * database synchronization.
     */
    internal fun createSyncNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createOtherNotificationChannelGroup()

            val channel = NotificationChannel(
                context.getString(R.string.notifications_channel_sync),
                context.getString(R.string.notifications_channel_sync_title),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.group = context.getString(
                R.string.notifications_channel_group_other
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates the notification channel group for non-conversation related
     * notifications.
     */
    private fun createOtherNotificationChannelGroup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelGroup = NotificationChannelGroup(
                context.getString(R.string.notifications_channel_group_other),
                context.getString(
                    R.string.notifications_channel_group_other_title
                )
            )
            notificationManager.createNotificationChannelGroup(channelGroup)
        }
    }

    /**
     * Rename notification channels for changed contact numbers.
     */
    fun renameNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Rename all channels
            for (channel in notificationManager.notificationChannels) {
                if (channel.id.startsWith(
                        context.getString(
                            R.string.notifications_channel_contact_prefix
                        )
                    )
                ) {
                    val contact = channel.id.split("_")[4]
                    val contactName = getContactName(context, contact)
                    channel.name = contactName ?: getFormattedPhoneNumber(
                        contact
                    )
                    notificationManager.createNotificationChannel(channel)
                }

            }
        }
    }

    /**
     * Delete notification channels for conversations that are no longer active.
     */
    suspend fun deleteNotificationChannelsAndGroups() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Remove any channel for which there is no conversation with
            // notifications enabled in the database
            val conversationIds = Database.getInstance(context)
                .getConversationIds(
                    getDids(context, onlyShowNotifications = true)
                )
            for (channel in notificationManager.notificationChannels) {
                if (channel.id.startsWith(
                        context.getString(
                            R.string.notifications_channel_contact_prefix
                        )
                    )
                ) {
                    val splitId = channel.id.split(":")[0].trim().split("_")
                    val conversationId = ConversationId(splitId[3], splitId[4])
                    if (conversationId !in conversationIds) {
                        notificationManager.deleteNotificationChannel(
                            channel.id
                        )
                        validateGroupNotification()
                    }
                }
            }

            // Remove any channel for which there is no conversation with
            // notifications enabled in the database
            val dids = conversationIds.map { it.did }.toSet()
            for (group in notificationManager.notificationChannelGroups) {
                if (group.id.startsWith(
                        context.getString(
                            R.string.notifications_channel_group_did, ""
                        )
                    )
                ) {
                    val did = group.id.split("_")[3]
                    if (did !in dids) {
                        notificationManager.deleteNotificationChannelGroup(
                            group.id
                        )
                        validateGroupNotification()
                    }
                }
            }
        }
    }

    /**
     * Gets the notification displayed during synchronization.
     */
    fun getSyncDatabaseNotification(id: UUID, progress: Int = 0): Notification {
        createSyncNotificationChannel()

        val builder = NotificationCompat.Builder(
            context, context.getString(R.string.notifications_channel_sync)
        )
        builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
        builder.setSmallIcon(R.drawable.ic_message_sync_toolbar_24dp)
        builder.setContentTitle(
            context.getString(
                R.string.notifications_sync_database_message
            )
        )
        builder.setContentText("$progress%")
        builder.setProgress(100, progress, false)
        builder.setOngoing(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.priority = Notification.PRIORITY_LOW
        }

        // Cancel action
        val cancelPendingIntent =
            WorkManager.getInstance(context).createCancelPendingIntent(id)
        val cancelAction = NotificationCompat.Action.Builder(
            R.drawable.ic_delete_toolbar_24dp,
            context.getString(R.string.notifications_button_cancel),
            cancelPendingIntent
        )
            .setShowsUserInterface(false)
            .build()
        builder.addAction(cancelAction)

        return builder.build()
    }

    /**
     * Gets the notification displayed during message sending.
     */
    fun getSyncMessageSendNotification(): Notification {
        createSyncNotificationChannel()

        val builder = NotificationCompat.Builder(
            context, context.getString(R.string.notifications_channel_sync)
        )
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        builder.setSmallIcon(R.drawable.ic_message_sync_toolbar_24dp)
        builder.setContentTitle(
            context.getString(
                R.string.notifications_sync_send_message_message
            )
        )
        builder.setOngoing(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.priority = Notification.PRIORITY_LOW
        }

        return builder.build()
    }

    /**
     * Gets the notification displayed during verification of credentials.
     */
    fun getSyncVerifyCredentialsNotification(): Notification {
        createSyncNotificationChannel()

        val builder = NotificationCompat.Builder(
            context, context.getString(R.string.notifications_channel_sync)
        )
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        builder.setSmallIcon(R.drawable.ic_message_sync_toolbar_24dp)
        builder.setContentTitle(
            context.getString(
                R.string.notifications_sync_verify_credentials_message
            )
        )
        builder.setOngoing(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.priority = Notification.PRIORITY_LOW
        }

        return builder.build()
    }

    /**
     * Gets the notification displayed during verification of credentials.
     */
    fun getSyncRetrieveDidsNotification(): Notification {
        createSyncNotificationChannel()

        val builder = NotificationCompat.Builder(
            context, context.getString(R.string.notifications_channel_sync)
        )
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        builder.setSmallIcon(R.drawable.ic_message_sync_toolbar_24dp)
        builder.setContentTitle(
            context.getString(
                R.string.notifications_sync_retrieve_dids_message
            )
        )
        builder.setOngoing(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.priority = Notification.PRIORITY_LOW
        }

        return builder.build()
    }

    /**
     * Gets the notification displayed during verification of credentials.
     */
    fun getSyncRegisterPushNotificationsNotification(): Notification {
        createSyncNotificationChannel()

        val builder = NotificationCompat.Builder(
            context, context.getString(R.string.notifications_channel_sync)
        )
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        builder.setSmallIcon(R.drawable.ic_message_sync_toolbar_24dp)
        builder.setContentTitle(
            context.getString(
                R.string.notifications_sync_register_push_message
            )
        )
        builder.setOngoing(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.priority = Notification.PRIORITY_LOW
        }

        return builder.build()
    }

    /**
     * Returns whether notifications are enabled globally and for the
     * conversation ID if one is specified.
     */
    fun getNotificationsEnabled(
        conversationId: ConversationId? = null
    ): Boolean {
        // Prior to Android O, check the global notification settings;
        // otherwise we can just rely on the system to block the notifications
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            if (!getNotificationsEnabled(context)) {
                return false
            }

            if (!NotificationManagerCompat.from(
                    context
                ).areNotificationsEnabled()
            ) {
                return false
            }
        } else {
            removePreference(
                context, context.getString(
                    R.string.preferences_notifications_enable_key
                )
            )
        }

        // However, we do check to see if notifications are enabled for a
        // particular DID, since the Android O interface doesn't really work
        // with this use case
        if (conversationId != null) {
            if (!getDidShowNotifications(context, conversationId.did)) {
                return false
            }
        }

        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Show notifications for new messages for the specified conversations.
     */
    suspend fun showNotifications(
        conversationIds: Set<ConversationId>,
        bubbleOnly: Boolean = false,
        autoLaunchBubble: Boolean = false,
        inlineReplyMessages: List<Message>
        = emptyList()
    ) {
        // Do not show notifications when the conversations view is open,
        // unless this is for a bubble only.
        if (CustomApplication.getApplication()
                .conversationsActivityVisible() && !bubbleOnly
        ) {
            return
        }

        val profiles = net.kourlas.voipms_sms.privacy.JunkProfiles(context)
        val db = Database.getInstance(context)
        for (conversationId in conversationIds) {
            if (net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(context, conversationId)) {
                cancelNotification(conversationId)
                continue
            }
            // Do not show notifications when notifications are disabled.
            if (!bubbleOnly && (net.kourlas.voipms_sms.tools.LineProfiles.quiet(context, conversationId.did) ||
                    net.kourlas.voipms_sms.tools.ThreadReminders.hidden(context, conversationId))) continue
            if (!getNotificationsEnabled(conversationId)) {
                continue
            }

            // Do not show notifications when the conversation view is
            // open, unless this is for a bubble only.
            if (CustomApplication.getApplication()
                    .conversationActivityVisible(conversationId)
                && !bubbleOnly
            ) {
                continue
            }

            val profile = profiles.line(conversationId.did)
            val state = db.inboxState(conversationId)
            if (state != null && !bubbleOnly) {
                val assessment = net.kourlas.voipms_sms.sms.InboxRules.assess(state.latestIncoming, profile)
                val other = state.otherFolder ?: (assessment.shouldQuarantine(state.hasReply, false) &&
                    getContactName(context, conversationId.contact) == null &&
                    net.kourlas.voipms_sms.contacts.LocalContacts.find(context, conversationId.contact) == null)
                if (other) {
                    cancelNotification(conversationId)
                    continue
                }
            }
            showNotification(
                conversationId,
                if (bubbleOnly || inlineReplyMessages.isNotEmpty()) emptyList()
                else db.getConversationMessagesUnread(conversationId),
                inlineReplyMessages, bubbleOnly, autoLaunchBubble, profile
            )
        }
    }

    /**
     * Shows a notification for the specified message, bypassing all normal
     * checks. Only used for demo purposes.
     */
    fun showDemoNotification(message: Message) = showNotification(
        message.conversationId,
        listOf(message),
        inlineReplyMessages = emptyList(),
        bubbleOnly = false,
        autoLaunchBubble = false
    )

    /**
     * Returns true if a notification for the provided DID and contact would
     * be allowed to bubble.
     */
    fun canBubble(did: String, contact: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val channel = getNotificationChannelId(did, contact)
            val notificationManager: NotificationManager =
                context.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager
            val bubblesAllowed =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    notificationManager.bubblePreference == NotificationManager.BUBBLE_PREFERENCE_ALL
                } else {
                    @Suppress("DEPRECATION")
                    notificationManager.areBubblesAllowed()
                }
            if (!bubblesAllowed) {
                val notificationChannel =
                    notificationManager.getNotificationChannel(channel)
                return notificationChannel != null
                    && notificationChannel.canBubble()
            }
            return true
        }
        return false
    }

    /**
     * Shows a notification with the specified messages.
     */
    private fun showNotification(
        conversationId: ConversationId,
        messages: List<Message>,
        inlineReplyMessages: List<Message>,
        bubbleOnly: Boolean,
        autoLaunchBubble: Boolean,
        profile: net.kourlas.voipms_sms.sms.InboxRules.Profile = net.kourlas.voipms_sms.sms.InboxRules.Profile()
    ) {
        if (net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(context, conversationId)) {
            cancelNotification(conversationId)
            return
        }
        // Do not show notification if there are no messages, unless this is
        // for a bubble notification.
        if (messages.isEmpty()
            && inlineReplyMessages.isEmpty()
            && !bubbleOnly
        ) {
            return
        }

        // However, if this is not Android R or later, there is no such thing
        // as a "bubble only" notification, so we should just return.
        if (bubbleOnly && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        // Notification metadata
        val did = conversationId.did
        val contact = conversationId.contact

        // Do not show bubble-only notifications if we're not allowed to
        // bubble.
        if (bubbleOnly && !canBubble(did, contact)) {
            return
        }

        val contactName = if (!BuildConfig.IS_DEMO) {
            net.kourlas.voipms_sms.contacts.LocalContacts.find(context, contact) ?: getContactName(context, contact) ?: getFormattedPhoneNumber(contact)
        } else {
            net.kourlas.voipms_sms.demo.getContactName(contact)
        }

        val largeIcon = applyCircularMask(
            getContactPhotoBitmap(
                context,
                contactName,
                context.resources.getDimensionPixelSize(
                    android.R.dimen.notification_large_icon_height
                )
            )
        )
        val adaptiveIcon = IconCompat.createWithAdaptiveBitmap(
            getContactPhotoAdaptiveBitmap(context, contactName)
        )

        // Notification channel
        val channel = getNotificationChannelId(did, contact)

        // General notification properties
        val notification = NotificationCompat.Builder(context, channel)
        notification.setSmallIcon(R.drawable.ic_chat_toolbar_24dp)
        notification.setLargeIcon(largeIcon)
        notification.setAutoCancel(true)
        val line = net.kourlas.voipms_sms.tools.LineProfiles.label(context, did)
        notification.setSubText(line)
        notification.addPerson(
            Person.Builder()
                .setName(contactName)
                .setKey(contact)
                .setIcon(adaptiveIcon)
                .setUri("tel:${contact}")
                .build()
        )
        notification.setCategory(Notification.CATEGORY_MESSAGE)
        val notificationAccent = net.kourlas.voipms_sms.tools.LineProfiles.accent(context, did, context.getColor(R.color.notification_accent))
        notification.color = notificationAccent
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            notification.priority = Notification.PRIORITY_HIGH
            notification.setLights(notificationAccent, 1000, 5000)
            @Suppress("DEPRECATION")
            val notificationSound = getNotificationSound(context)
            if (notificationSound != "") {
                notification.setSound(notificationSound.toUri())
            } else {
                notification.setSilent(true)
            }
            @Suppress("DEPRECATION")
            if (getNotificationVibrateEnabled(context)) {
                notification.setVibrate(longArrayOf(0, 250, 250, 250))
            }
        } else {
            removePreference(
                context, context.getString(
                    R.string.preferences_notifications_sound_key
                )
            )
            removePreference(
                context, context.getString(
                    R.string.preferences_notifications_vibrate_key
                )
            )
        }
        notification.setGroup(
            context.getString(
                R.string.notifications_group_key
            )
        )
        notification.setGroupAlertBehavior(
            NotificationCompat.GROUP_ALERT_CHILDREN
        )
        notification.setShortcutId(conversationId.getId())
        notification.setLocusId(LocusIdCompat(conversationId.getId()))
        if (bubbleOnly || inlineReplyMessages.isNotEmpty()) {
            // This means no new messages were received, so we should avoid
            // notifying the user if we can.
            notification.setOnlyAlertOnce(true)
        }

        // Notification text
        val person = Person.Builder().setName(
            context.getString(R.string.notifications_current_user)
        ).build()
        val style = NotificationCompat.MessagingStyle(person)
        val tag = conversationId.notificationTag()
        val targetUri = tag.toUri()
        if (inlineReplyMessages.isNotEmpty()) {
            // Restore replies and their context from the posted notification across process death.
            val posted = notificationManager.activeNotifications.firstOrNull {
                matchesConversation(it, conversationId)
            }
            val previous = posted?.notification?.let {
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)
            }
            previous?.historicMessages?.forEach(style::addHistoricMessage)
            previous?.messages?.forEach(style::addHistoricMessage)
            for (message in inlineReplyMessages) {
                style.addMessage(
                    message.summaryDisplayText,
                    Date().time,
                    null as Person?
                )
            }
        } else if (messages.isNotEmpty()) {
            val notificationMessages = messages.map {
                NotificationCompat.MessagingStyle.Message(
                    if (it.isIncoming && net.kourlas.voipms_sms.sms.InboxRules.assess(it.text, profile).description != null)
                        context.getString(R.string.risky_message_notification)
                    else net.kourlas.voipms_sms.privacy.CodePrivacy.notificationText(context, it),
                    it.date.time,
                    if (it.isIncoming)
                        Person.Builder()
                            .setName(contactName)
                            .setKey(contact)
                            .setIcon(adaptiveIcon)
                            .setUri("tel:${it.contact}")
                            .build()
                    else null
                )
            }
            for (message in notificationMessages) {
                style.addMessage(message)
            }
            notification.setShowWhen(true)
            notification.setWhen(messages.last().date.time)
        }
        notification.setStyle(style)

        // Mark as read button
        val markReadIntent = MarkReadReceiver.getIntent(context, did, contact)
        markReadIntent.component = ComponentName(
            context, MarkReadReceiver::class.java
        )
        markReadIntent.data = targetUri
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context, 0, markReadIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_drafts_toolbar_24dp,
            context.getString(R.string.notifications_button_mark_read),
            markReadPendingIntent
        )
            .setSemanticAction(
                NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ
            )
            .setShowsUserInterface(false)
            .build()
        notification.addAction(markReadAction)

        // Reply button
        val replyIntent = SendMessageReceiver.getIntent(
            context, did, contact
        )
        replyIntent.component = ComponentName(
            context, SendMessageReceiver::class.java
        )
        replyIntent.data = targetUri
        val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, 0,
            replyIntent, replyFlags
        )
        val remoteInput = RemoteInput.Builder(
            context.getString(
                R.string.notifications_reply_key
            )
        )
            .setLabel(context.getString(R.string.line_conversation_label,
                context.getString(R.string.notifications_button_reply), line))
            .build()
        val replyActionBuilder = NotificationCompat.Action.Builder(
            R.drawable.ic_reply_toolbar_24dp,
            context.getString(R.string.notifications_button_reply),
            replyPendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .addRemoteInput(remoteInput)

        val replyAction = replyActionBuilder.build()
        notification.addAction(replyAction)
        notification.extend(NotificationCompat.WearableExtender().addAction(replyAction).addAction(markReadAction))

        // Group notification
        val groupNotification = NotificationCompat.Builder(context, channel)
        groupNotification.setSmallIcon(R.drawable.ic_chat_toolbar_24dp)
        groupNotification.setGroup(
            context.getString(
                R.string.notifications_group_key
            )
        )
        groupNotification.setGroupSummary(true)
        groupNotification.setAutoCancel(true)
        groupNotification.setGroupAlertBehavior(
            NotificationCompat.GROUP_ALERT_CHILDREN
        )

        val groupIntent = Intent(context, ConversationsActivity::class.java)
        groupIntent.data = "voipms-sms://notifications/inbox".toUri()
        val groupStackBuilder = TaskStackBuilder.create(context)
        groupStackBuilder.addNextIntentWithParentStack(groupIntent)
        groupNotification.setContentIntent(
            groupStackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Primary notification action
        val intent = Intent(context, ConversationActivity::class.java)
        intent.putExtra(
            context.getString(
                R.string.conversation_did
            ), did
        )
        intent.putExtra(
            context.getString(
                R.string.conversation_contact
            ), contact
        )
        intent.data = targetUri
        val stackBuilder = TaskStackBuilder.create(context)
        stackBuilder.addNextIntentWithParentStack(intent)
        stackBuilder.editIntentAt(0)?.putExtra(net.kourlas.voipms_sms.conversations.OPEN_NOTIFICATION_MESSAGES, true)
        notification.setContentIntent(
            stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Bubble
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bubbleIntent = Intent(
                context,
                ConversationBubbleActivity::class.java
            )
            bubbleIntent.putExtra(
                context.getString(
                    R.string.conversation_did
                ), did
            )
            bubbleIntent.putExtra(
                context.getString(
                    R.string.conversation_contact
                ), contact
            )
            bubbleIntent.data = targetUri
            val bubbleFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else PendingIntent.FLAG_UPDATE_CURRENT
            val bubblePendingIntent = PendingIntent.getActivity(
                context, 0,
                bubbleIntent, bubbleFlags
            )
            val bubbleMetadata =
                NotificationCompat.BubbleMetadata.Builder(
                    bubblePendingIntent,
                    adaptiveIcon
                )
                    .setDesiredHeight(600)
                    .setSuppressNotification(bubbleOnly)
                    .setAutoExpandBubble(autoLaunchBubble)
                    .build()
            notification.bubbleMetadata = bubbleMetadata
        }

        synchronized(this) {
            // A sync or inline reply can outlive a sign-out or a change of selected DIDs.
            if (!BuildConfig.IS_DEMO && (!accountConfigured(context) || !getDidShowNotifications(context, did))) return
            val manager = NotificationManagerCompat.from(context)
            try {
                manager.notify(tag, MESSAGE_NOTIFICATION_ID, notification.build())
                if (manager.areNotificationsEnabled()) {
                    net.kourlas.voipms_sms.delivery.DeliveryTrace.posted(context, did, contact)
                }
                manager.notify(GROUP_NOTIFICATION_ID, groupNotification.build())
                notificationManager.activeNotifications.filter {
                    it.tag == null && matchesConversation(it, conversationId)
                }.forEach { manager.cancel(it.id) }
            } catch (e: SecurityException) {
                Log.w(TAG, "Message notifications are unavailable", e)
            }
        }
    }

    /** Matches legacy untagged children using their stored conversation shortcut, when available. */
    private fun matchesConversation(posted: StatusBarNotification, conversationId: ConversationId): Boolean {
        if (posted.tag != null) return posted.id == MESSAGE_NOTIFICATION_ID && posted.tag == conversationId.notificationTag()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isMessageChild(posted)) return false
        val shortcut = posted.notification.shortcutId ?: return false
        return normalizeVoipMsDid(shortcut.substringBefore('_')) == conversationId.did &&
            shortcut.substringAfter('_', "") == conversationId.contact
    }

    private fun isMessageChild(posted: StatusBarNotification): Boolean =
        posted.notification.group == context.getString(R.string.notifications_group_key) &&
            posted.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0

    @Synchronized
    fun cancelNotification(conversationId: ConversationId) {
        val active = notificationManager.activeNotifications
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(conversationId.notificationTag(), MESSAGE_NOTIFICATION_ID)
        for (posted in active) {
            if (posted.tag == null && matchesConversation(posted, conversationId)) manager.cancel(posted.id)
        }
        if (active.none { isMessageChild(it) && !matchesConversation(it, conversationId) }) {
            manager.cancel(GROUP_NOTIFICATION_ID)
        }
    }

    @Synchronized
    fun cancelMessageNotifications() {
        val manager = NotificationManagerCompat.from(context)
        notificationManager.activeNotifications.filter(::isMessageChild).forEach { manager.cancel(it.tag, it.id) }
        manager.cancel(GROUP_NOTIFICATION_ID)
    }

    /**
     * Gets the notification channel ID for the provided DID and contact. The
     * channel is guaranteed to exist.
     */
    private fun getNotificationChannelId(did: String, contact: String): String {
        createDefaultNotificationChannel()
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Prior to Android O, this doesn't matter.
            context.getString(R.string.notifications_channel_default)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Prior to Android R, conversations did not have a separate
            // section in the notification settings, so we only create the
            // conversation specific channel if the user requested it.
            val channel = notificationManager.getNotificationChannel(
                context.getString(
                    R.string.notifications_channel_contact,
                    did, contact
                )
            )
            if (channel == null) {
                context.getString(R.string.notifications_channel_default)
            } else {
                context.getString(
                    R.string.notifications_channel_contact,
                    did, contact
                )
            }
        } else {
            // As of Android R, conversations have a separate section in the
            // notification settings, so we always create the conversation
            // specific channel.
            createDidNotificationChannel(did, contact)
            context.getString(
                R.string.notifications_channel_contact,
                did, contact
            )
        }
    }

    /**
     * Cancels the group notification if required.
     */
    @Synchronized
    fun validateGroupNotification() {
        if (notificationManager.activeNotifications.none(::isMessageChild)) {
            NotificationManagerCompat.from(context).cancel(GROUP_NOTIFICATION_ID)
        }
    }

    companion object {
        // It is not a leak to store an instance to the application context,
        // since it has the same lifetime as the application itself
        @SuppressLint("StaticFieldLeak")
        private var instance: Notifications? = null

        // Notification ID for the database synchronization notification.
        const val SYNC_DATABASE_NOTIFICATION_ID = 1

        // Notification ID for the send message notification.
        const val SYNC_SEND_MESSAGE_NOTIFICATION_ID =
            SYNC_DATABASE_NOTIFICATION_ID + 1

        // Notification ID for the verify credentials notification.
        const val SYNC_VERIFY_CREDENTIALS_NOTIFICATION_ID =
            SYNC_SEND_MESSAGE_NOTIFICATION_ID + 1

        // Notification ID for the retrieve DIDs notification.
        const val SYNC_RETRIEVE_DIDS_NOTIFICATION_ID =
            SYNC_VERIFY_CREDENTIALS_NOTIFICATION_ID + 1

        // Notification ID for the register for push notifications notification.
        const val SYNC_REGISTER_PUSH_NOTIFICATION_ID =
            SYNC_RETRIEVE_DIDS_NOTIFICATION_ID + 1

        // Notification ID for the group notification, which contains all other
        // notifications
        const val GROUP_NOTIFICATION_ID = SYNC_REGISTER_PUSH_NOTIFICATION_ID + 1

        // The tag differentiates conversations sharing this child ID.
        internal const val MESSAGE_NOTIFICATION_ID = GROUP_NOTIFICATION_ID + 1

        /**
         * Gets the sole instance of the Notifications class. Initializes the
         * instance if it does not already exist.
         */
        fun getInstance(context: Context): Notifications =
            instance ?: synchronized(this) {
                instance ?: Notifications(
                    context.applicationContext
                ).also { instance = it }
            }
    }
}
