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

package net.kourlas.voipms_sms.sms.workers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.network.NetworkManager
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.AccountCredentials
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.utils.httpPostWithMultipartFormData
import java.io.IOException

private const val TAG = "SendMessageWorker"

/**
 * Worker used to send an SMS message using the VoIP.ms API.
 */
class SendMessageWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private var conversationId: ConversationId? = null
    private var error: String? = null
    private var markedAsSent: Boolean = false
    private var markedAsNotSent: Boolean = false

    override suspend fun doWork(): Result {
        // Terminate quietly if we cannot find the messages we are supposed
        // to send.
        val databaseIds = inputData.getLongArray(
            applicationContext.getString(
                R.string.send_message_database_ids
            )
        ) ?: return Result.failure()

        // Work created by an older build has no verified originating session.
        val generation = inputData.getLong(ACCOUNT_GENERATION, -1)
        var success = true
        for (databaseId in databaseIds) {
            try {
                // Reset our state before sending the message.
                conversationId = null
                error = null
                markedAsSent = false
                markedAsNotSent = false

                // Send the message.
                try {
                    val applied = withCurrentAccount(applicationContext, generation) { credentials ->
                        sendMessage(databaseId, credentials)
                        true
                    }
                    if (applied != true) {
                        error = applicationContext.getString(R.string.send_message_account_changed)
                    }
                } catch (_: IOException) {
                    error = applicationContext.getString(R.string.credentials_storage_unavailable)
                }
                if (error != null) {
                    success = false
                }

                // Send a broadcast indicating that the message has been sent,
                // or that an attempt to send it was made.
                conversationId?.let {
                    val sentMessageBroadcastIntent = Intent(
                        applicationContext.getString(
                            R.string.sent_message_action, it.did, it.contact
                        )
                    )
                    if (error != null) {
                        sentMessageBroadcastIntent.putExtra(
                            applicationContext.getString(
                                R.string.sent_message_error
                            ), error
                        )
                    }
                    sentMessageBroadcastIntent.setPackage(
                        applicationContext.packageName
                    )
                    applicationContext.sendBroadcast(
                        sentMessageBroadcastIntent
                    )
                }
            } finally {
                // We are not going to try sending this message again unless the
                // user triggers it by clicking on the message, so ensure the
                // database is correctly updated even on failure.
                if (!markedAsSent && !markedAsNotSent) {
                    try {
                        Database.getInstance(
                            applicationContext
                        ).markMessageNotSent(
                            databaseId
                        )
                        markedAsNotSent = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to mark message as not sent", e)
                    }
                }
                if (markedAsSent || markedAsNotSent) {
                    val attempts = applicationContext.getSharedPreferences(ATTEMPTS, Context.MODE_PRIVATE)
                    attempts.edit().remove(attemptKey(databaseId)).apply()
                }
            }
        }

        return if (success) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    @SuppressLint("InlinedApi")
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = Notifications.getInstance(applicationContext)
            .getSyncMessageSendNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.SYNC_SEND_MESSAGE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            ForegroundInfo(
                Notifications.SYNC_SEND_MESSAGE_NOTIFICATION_ID,
                notification
            )
        }
    }

    /**
     * Sends the message we were asked to send using the VoIP.ms API and
     * updates the database accordingly.
     */
    private suspend fun sendMessage(databaseId: Long, credentials: AccountCredentials) {
        try {
            // Retrieve the message we are supposed to send. Terminate quietly
            // if it is not in a valid state.
            val message = Database.getInstance(applicationContext)
                .getMessageDatabaseId(databaseId)
            conversationId = message?.conversationId
            if (message == null || !message.isDeliveryInProgress) {
                return
            }

            // Terminate if no network connection is available.
            if (!NetworkManager.getInstance().isNetworkConnectionAvailable(
                    applicationContext
                )
            ) {
                error = applicationContext.getString(
                    R.string.send_message_error_network
                )
                Database.getInstance(
                    applicationContext
                ).markMessageNotSent(
                    message.databaseId
                )
                markedAsNotSent = true
                return
            }

            // Send the message using the VoIP.ms API. If we succeed, mark the
            // the message was sent. If not, mark it as not having been sent.
            if (message.did !in getDids(applicationContext)) {
                error = applicationContext.getString(R.string.send_message_account_changed)
                return
            }
            val attempts = applicationContext.getSharedPreferences(ATTEMPTS, Context.MODE_PRIVATE)
            val key = attemptKey(databaseId)
            if (attempts.contains(key)) {
                error = applicationContext.getString(R.string.send_message_error_api_request)
                return
            }
            if (!attempts.edit().putBoolean(key, true).commit()) {
                error = applicationContext.getString(R.string.send_message_attempt_unavailable)
                return
            }
            val voipId = sendMessageWithVoipMsApi(message, credentials)
            if (voipId != null) {
                Database.getInstance(
                    applicationContext
                ).markMessageSent(
                    message.databaseId, voipId
                )
                markedAsSent = true
            } else {
                Database.getInstance(
                    applicationContext
                ).markMessageNotSent(
                    message.databaseId
                )
                markedAsNotSent = true
            }

            // If this request came from an inline reply, refresh the
            // notification.
            val inlineReply = inputData.getBoolean(
                applicationContext.getString(
                    R.string.send_message_inline_reply
                ), false
            )
            if (inlineReply && markedAsSent) {
                val inlineReplyDid = inputData.getString(
                    applicationContext.getString(
                        R.string.send_message_inline_reply_did
                    )
                ) ?: return
                val inlineReplyContact = inputData.getString(
                    applicationContext.getString(
                        R.string.send_message_inline_reply_contact
                    )
                ) ?: return
                Notifications.getInstance(applicationContext).showNotifications(
                    setOf(ConversationId(inlineReplyDid, inlineReplyContact)),
                    inlineReplyMessages = listOf(message)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            error = applicationContext.getString(
                R.string.send_message_error_unknown
            )
        }
    }

    @JsonClass(generateAdapter = true)
    data class MessageResponse(val status: String, val sms: Long?)

    /**
     * Sends the specified message using the VoIP.ms API
     *
     * @return Null if the message could not be sent.
     */
    private suspend fun sendMessageWithVoipMsApi(message: Message, credentials: AccountCredentials): Long? {
        // Get JSON response from API
        val response: MessageResponse? = getMessageResponse(message, credentials)

        // Get VoIP.ms ID from response
        if (response == null) return null // The request already set the failure reason.
        if (response.status.isBlank()) {
            error = applicationContext.getString(
                R.string.send_message_error_api_parse
            )
            return null
        }
        if (response.status != "success") {
            error = when (response.status) {
                "invalid_credentials" -> applicationContext.getString(
                    R.string.send_message_error_api_error_invalid_credentials
                )

                "invalid_dst" -> applicationContext.getString(
                    R.string.send_message_error_api_error_invalid_dst
                )

                "invalid_sms" -> applicationContext.getString(
                    R.string.send_message_error_api_error_invalid_sms
                )

                "limit_reached" -> applicationContext.getString(
                    R.string.send_message_error_api_error_limit_reached
                )

                "message_empty" -> applicationContext.getString(
                    R.string.send_message_error_api_error_message_empty
                )

                "missing_sms" -> applicationContext.getString(
                    R.string.send_message_error_api_error_missing_sms
                )

                "sms_failed" -> applicationContext.getString(
                    R.string.send_message_error_api_error_sms_failed
                )

                "sms_toolong" -> applicationContext.getString(
                    R.string.send_message_error_api_error_sms_toolong
                )

                else -> applicationContext.getString(
                    R.string.send_message_error_api_error, response.status
                )
            }
            return null
        }
        if (response.sms == null || response.sms <= 0) {
            error = applicationContext.getString(
                R.string.send_message_error_api_parse
            )
            return null
        }
        return response.sms
    }

    private suspend fun getMessageResponse(message: Message, credentials: AccountCredentials): MessageResponse? {
        try {
            return httpPostWithMultipartFormData<MessageResponse>(
                applicationContext,
                "https://voip.ms/api/v1/rest.php",
                mapOf(
                    "api_username" to credentials.email,
                    "api_password" to credentials.password,
                    "method" to "sendSMS",
                    "did" to message.did,
                    "dst" to message.contact,
                    "message" to message.text
                ),
                allowReplay = false
            ) ?: run {
                error = applicationContext.getString(R.string.send_message_error_api_parse)
                null
            }
        } catch (e: IOException) {
            error = applicationContext.getString(R.string.send_message_error_api_request)
            return null
        } catch (e: JsonDataException) {
            Log.e(TAG, "Failed to parse send message response", e)
            error = applicationContext.getString(
                R.string.send_message_error_api_parse
            )
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message request", e)
            error = applicationContext.getString(
                R.string.send_message_error_unknown
            )
            return null
        }
    }

    // WorkManager keeps the same UUID when resuming an interrupted WorkRequest.
    // A manual resend creates a new UUID and a separately authorized attempt.
    private fun attemptKey(databaseId: Long) = "$id/$databaseId"

    companion object {
        internal const val ACCOUNT_GENERATION = "send_account_generation"
        private const val ATTEMPTS = "outbound_submission_attempts"

        /**
         * Sends the message associated with the specified database ID.
         */
        fun sendMessages(
            context: Context,
            databaseIds: List<Long>,
            generation: Long,
            inlineReplyConversationId: ConversationId? = null
        ) {
            val work = OneTimeWorkRequestBuilder<SendMessageWorker>()
                .setInputData(
                    workDataOf(
                        ACCOUNT_GENERATION to generation,
                        context.getString(
                            R.string.send_message_database_ids
                        ) to databaseIds.toLongArray(),
                        context.getString(
                            R.string.send_message_inline_reply
                        )
                            to (inlineReplyConversationId != null),
                        context.getString(
                            R.string.send_message_inline_reply_did
                        )
                            to inlineReplyConversationId?.did,
                        context.getString(
                            R.string.send_message_inline_reply_contact
                        )
                            to inlineReplyConversationId?.contact
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                context.getString(
                    R.string.send_message_work_id,
                    databaseIds.first()
                ),
                ExistingWorkPolicy.KEEP,
                work
            )
        }

        fun sendMessage(
            context: Context,
            databaseId: Long,
            generation: Long,
            inlineReplyConversationId: ConversationId? = null
        ) {
            sendMessages(context, listOf(databaseId), generation, inlineReplyConversationId)
        }
    }
}
