/*
 * VoIP.ms SMS
 * Copyright (C) 2015-2020 Michael Kourlas
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

package net.kourlas.voipms_sms.notifications.services

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import net.kourlas.voipms_sms.notifications.pushTopicForDid
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.sms.workers.SyncWorker
import net.kourlas.voipms_sms.utils.enablePushNotifications

/** Treat the relay's data message as a wakeup. Message content comes from VoIP.ms. */
class FcmListenerService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val topic = message.from?.takeIf { it.startsWith("/topics/") }
            ?.removePrefix("/topics/") ?: return
        val did = getDids(this, onlyRetrieveMessages = true).firstOrNull {
            pushTopicForDid(it) == topic
        }
        if (did != null) {
            SyncWorker.receivePush(this, did)
        } else if (topic.startsWith("did-") && pushTopicForDid(topic.removePrefix("did-")) == topic) {
            // Older installations subscribed without recording their memberships.
            FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
        }
    }

    override fun onDeletedMessages() {
        // FCM can discard pending messages after an extended disconnection.
        SyncWorker.receivePush(this)
    }

    override fun onNewToken(token: String) {
        enablePushNotifications(applicationContext)
    }
}
