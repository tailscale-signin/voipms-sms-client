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

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.database.entities.OutboxPart
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DebugTrace
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.network.NetworkManager
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.AccountCredentials
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.sms.destinationSupport
import net.kourlas.voipms_sms.sms.DestinationSupport
import net.kourlas.voipms_sms.utils.httpPostWithMultipartFormData
import java.io.IOException

private const val TAG = "SendMessageWorker"

/**
 * Worker used to send an SMS message using the VoIP.ms API.
 */
class SendMessageWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private var error: String? = null
    private var rejected = false
    private var failureCause: Exception? = null

    override suspend fun doWork(): Result {
        val trace = DebugJournal.begin(applicationContext, DiagnosticAction.OUTBOX_SEND)
        try {
            return sendBatch(trace)
        } catch (e: CancellationException) {
            trace.step(DiagnosticStep.CANCELLED)
            throw e
        } catch (e: Exception) {
            trace.step(DiagnosticStep.FAILED, error = e)
            throw e
        }
    }

    private suspend fun sendBatch(trace: DebugTrace): Result {
        trace.step(DiagnosticStep.VALIDATING)
        val batch = inputData.getString(Outbox.BATCH)
        if (batch == null) {
            trace.step(DiagnosticStep.SKIPPED)
            return Result.success()
        }
        val generation = inputData.getLong(ACCOUNT_GENERATION, -1)
        val context = applicationContext
        val db = Database.getInstance(context)
        val parts = db.outboxTransaction { outboxDao().parts(batch) }
        var accepted = 0L
        var outcome = DiagnosticStep.SKIPPED
        for (part in parts) {
            if (part.generation != generation || part.state == OutboxPart.ACCEPTED) continue
            if (part.state == OutboxPart.UNKNOWN || part.state == OutboxPart.REJECTED) {
                outcome = DiagnosticStep.SKIPPED
                break
            }
            val message = db.getMessageDatabaseId(part.messageId) ?: continue
            if (part.state == OutboxPart.ATTEMPTING) {
                Outbox.failed(context, part, true, context.getString(R.string.outbox_unconfirmed))
                notifyConversation(message, context.getString(R.string.outbox_unconfirmed))
                outcome = DiagnosticStep.FAILED
                break
            }
            if (part.dueAt > System.currentTimeMillis() ||
                !NetworkManager.getInstance().isNetworkConnectionAvailable(context)) {
                trace.step(DiagnosticStep.RETRY, count = accepted)
                return Result.retry()
            }
            error = null
            rejected = false
            val finished = try {
                withCurrentAccount(context, generation) { account ->
                    if (message.did !in getDids(context)) {
                        error = context.getString(R.string.send_message_account_changed)
                        Outbox.failed(context, part, false, error!!)
                        return@withCurrentAccount false
                    }
                    if (destinationSupport(message.did, message.contact) == DestinationSupport.UNSUPPORTED) {
                        error = context.getString(R.string.outbox_destination_unsupported)
                        Outbox.failed(context, part, false, error!!)
                        return@withCurrentAccount false
                    }
                    if (db.outboxTransaction { outboxDao().claim(batch, part.messageId, System.currentTimeMillis()) } != 1)
                        return@withCurrentAccount false
                    // A cancelled process after this point leaves a durable attempted state.
                    if (accepted == 0L) trace.step(DiagnosticStep.SENDING)
                    val id = sendMessageWithVoipMsApi(message, account, part.isMms)
                    if (id == null) {
                        Outbox.failed(context, part, !rejected, error ?: context.getString(R.string.outbox_unconfirmed))
                        false
                    } else {
                        Outbox.accept(context, part, id, System.currentTimeMillis())
                        if (part.inlineReply) Notifications.getInstance(context).showNotifications(
                            setOf(message.conversationId), inlineReplyMessages = listOf(message))
                        true
                    }
                } ?: run {
                    error = context.getString(R.string.send_message_account_changed)
                    Outbox.failed(context, part, false, error!!)
                    false
                }
            } catch (e: IOException) {
                trace.step(DiagnosticStep.RETRY, count = accepted, error = e)
                return Result.retry()
            }
            notifyConversation(message, error)
            if (!finished) {
                outcome = if (error == null) DiagnosticStep.SKIPPED else DiagnosticStep.FAILED
                break
            }
            accepted++
            outcome = DiagnosticStep.COMPLETED
        }
        trace.step(outcome, count = accepted, error = failureCause)
        return Result.success()
    }

    private fun notifyConversation(message: Message, error: String?) {
        val intent = Intent(applicationContext.getString(R.string.sent_message_action, message.did, message.contact))
            .setPackage(applicationContext.packageName)
        if (error != null) intent.putExtra(applicationContext.getString(R.string.sent_message_error), error)
        applicationContext.sendBroadcast(intent)
    }

    @JsonClass(generateAdapter = true)
    data class MessageResponse(val status: String, val sms: Long? = null, val mms: Long? = null)

    /**
     * Sends the specified message using the VoIP.ms API
     *
     * @return Null if the message could not be sent.
     */
    private suspend fun sendMessageWithVoipMsApi(message: Message, credentials: AccountCredentials, asMms: Boolean): Long? {
        // Get JSON response from API
        val response: MessageResponse? = getMessageResponse(message, credentials, asMms)

        // Get VoIP.ms ID from response
        if (response == null) return null // The request already set the failure reason.
        if (response.status.isBlank()) {
            error = applicationContext.getString(
                R.string.send_message_error_api_parse
            )
            return null
        }
        if (response.status != "success") {
            rejected = true
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
        val providerId = response.mms ?: response.sms
        if (providerId == null || providerId <= 0) {
            error = applicationContext.getString(
                R.string.send_message_error_api_parse
            )
            return null
        }
        return providerId
    }

    private suspend fun getMessageResponse(message: Message, credentials: AccountCredentials, asMms: Boolean): MessageResponse? {
        try {
            return httpPostWithMultipartFormData<MessageResponse>(
                applicationContext,
                "https://voip.ms/api/v1/rest.php",
                mapOf(
                    "api_username" to credentials.email,
                    "api_password" to credentials.password,
                    "method" to if (asMms || message.medias.isNotEmpty()) "sendMMS" else "sendSMS",
                    "did" to message.did,
                    "dst" to message.contact,
                    "message" to message.text
                ) + net.kourlas.voipms_sms.media.MmsMedia.outgoingFields(applicationContext, message.medias),
                allowReplay = false
            ) ?: run {
                error = applicationContext.getString(R.string.send_message_error_api_parse)
                null
            }
        } catch (e: IOException) {
            failureCause = e
            error = applicationContext.getString(R.string.send_message_error_api_request)
            return null
        } catch (e: JsonDataException) {
            failureCause = e
            Log.e(TAG, "Failed to parse send message response", e)
            error = applicationContext.getString(
                R.string.send_message_error_api_parse
            )
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failureCause = e
            Log.e(TAG, "Failed to send message request", e)
            error = applicationContext.getString(
                R.string.send_message_error_unknown
            )
            return null
        }
    }

    companion object {
        internal const val ACCOUNT_GENERATION = "send_account_generation"
    }
}
