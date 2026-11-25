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
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.database.entities.SyncCheckpoint
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DebugTrace
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.AccountCredentials
import net.kourlas.voipms_sms.preferences.didsConfigured
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getRetrieveDeletedMessages
import net.kourlas.voipms_sms.preferences.getRetrieveOnlyRecentMessages
import net.kourlas.voipms_sms.preferences.getStartDate
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.utils.HttpStatusException
import net.kourlas.voipms_sms.utils.httpPostWithMultipartFormData
import java.io.IOException
import java.util.Date

private const val TAG = "MessageSynchronization"

/** One provider sync pass, shared by scheduled work, push and a user-started listener. */
internal class MessageSynchronization(
    private val applicationContext: Context,
    private val forceRecent: Boolean,
    private val targetDid: String? = null,
    private val expectedAccountGeneration: Long? = null,
    private val route: String = "Live listener",
    private val onStarted: () -> Unit = {},
    private val onProgress: suspend (Int) -> Unit = {}
) {
    var error: String? = null
        private set
    var transientFailure: Boolean = false
        private set
    var accountRejected: Boolean = false
        private set

    // One synchronization instance represents one provider pass, including any number of retrieval windows.
    private var appliedMessages: Long? = null
    private var failureCause: Exception? = null

    suspend fun run(retryOnTransient: Boolean = false) {
        val trace = DebugJournal.begin(applicationContext, DiagnosticAction.SMS_SYNC)
        try {
            if (forceRecent) {
                incrementalSync.withLock {
                    onStarted()
                    handleSync(trace)
                }
            } else {
                handleSync(trace)
            }
            val outcome = when {
                transientFailure && retryOnTransient -> DiagnosticStep.RETRY
                error != null -> DiagnosticStep.FAILED
                appliedMessages == null -> DiagnosticStep.SKIPPED
                else -> DiagnosticStep.COMPLETED
            }
            trace.step(outcome, count = appliedMessages, error = failureCause)
        } catch (e: CancellationException) {
            trace.step(DiagnosticStep.CANCELLED)
            throw e
        } catch (e: Exception) {
            trace.step(DiagnosticStep.FAILED, error = e)
            throw e
        } finally {
            // Send a broadcast indicating that the database has been
            // synchronized or an attempt has been made to synchronize it.
            val syncCompleteBroadcastIntent = Intent(
                applicationContext.getString(
                    R.string.sync_complete_action
                )
            )
            syncCompleteBroadcastIntent.putExtra(
                applicationContext.getString(
                    R.string.sync_complete_error
                ), error
            )
            if (!forceRecent) {
                syncCompleteBroadcastIntent.putExtra(
                    applicationContext.getString(
                        R.string.sync_complete_full
                    ), true
                )
            }
            syncCompleteBroadcastIntent.setPackage(
                applicationContext.packageName
            )
            applicationContext.sendBroadcast(syncCompleteBroadcastIntent)
        }
    }

    /**
     * Perform synchronization.
     */
    private suspend fun handleSync(trace: DebugTrace) {
        trace.step(DiagnosticStep.VALIDATING)
        try {
            // Terminate quietly if it is impossible to sync due to account
            // configuration, unless it is a full sync.
            val credentials = readAccountCredentials(applicationContext)
            if (credentials == null) {
                transientFailure = true
                error = applicationContext.getString(R.string.credentials_storage_unavailable)
                return
            }
            if (expectedAccountGeneration != null && credentials.generation != expectedAccountGeneration) return
            if (!credentials.configured) {
                if (!forceRecent) {
                    error = applicationContext.getString(
                        R.string.sync_error_account
                    )
                }
                return
            }
            if (!didsConfigured(applicationContext)) {
                if (!forceRecent) {
                    error = applicationContext.getString(
                        R.string.sync_error_dids
                    )
                }
                return
            }

            // Retrieve all messages from VoIP.ms, or only those messages
            // dated after the most recent message stored locally
            val retrieveOnlyRecentMessages =
                forceRecent || getRetrieveOnlyRecentMessages(applicationContext)
            // Retrieve messages from VoIP.ms that were deleted locally
            val retrieveDeletedMessages =
                !forceRecent && getRetrieveDeletedMessages(applicationContext)

            // Process retrieval requests separately
            val email = credentials.email
            val retrievalRequests = withCurrentAccount(applicationContext, credentials.generation) {
                createRetrievalRequests(retrieveOnlyRecentMessages, accountKey(email))
            } ?: return
            if (retrievalRequests.isEmpty()) return
            trace.step(DiagnosticStep.READING, count = retrievalRequests.size.toLong())
            processRequests(retrievalRequests, retrieveDeletedMessages, credentials, trace)
        } catch (e: CancellationException) {
            // We need to propagate the exception from processRequests
            error = applicationContext.getString(R.string.sync_error_cancelled)
            throw e
        } catch (e: IOException) {
            failureCause = e
            transientFailure = true
            error = applicationContext.getString(R.string.sync_error_api_request)
        } catch (e: Exception) {
            failureCause = e
            Log.e(TAG, "Failed to synchronize messages", e)
            error = applicationContext.getString(
                R.string.sync_error_unknown
            )
        }
    }

    private suspend fun createRetrievalRequests(
        retrieveOnlyRecentMessages: Boolean,
        accountKey: String
    ): List<RetrievalRequest> {
        val dids = getDids(applicationContext, onlyRetrieveMessages = true)
        val target = targetDid
        val start = preferenceDay(getStartDate(applicationContext))
        val now = Date()
        val database = Database.getInstance(applicationContext)
        return dids.filter { target == null || it == target }.flatMap { did ->
            var previous = if (retrieveOnlyRecentMessages) {
                database.checkedThrough(accountKey, did, start)
            } else null
            retrievalWindows(start, previous, now).map { window ->
                val checkpoint = SyncCheckpoint(accountKey, did, start, window.to)
                RetrievalRequest(did, window, checkpoint, previous).also { previous = window.to }
            }
        }
    }

    private suspend fun processRequests(
        retrievalRequests: List<RetrievalRequest>,
        retrieveDeletedMessages: Boolean,
        credentials: AccountCredentials,
        trace: DebugTrace
    ) = coroutineScope {
        val rejectedDids = mutableSetOf<String>()
        val incompleteDids = mutableSetOf<String>()
        val database = Database.getInstance(applicationContext)
        for ((index, request) in retrievalRequests.withIndex()) {
            ensureActive()
            if (request.did in rejectedDids) continue
            val current = readAccountCredentials(applicationContext) ?: throw IOException("Account storage unavailable")
            if (current.generation != credentials.generation) return@coroutineScope
            if (request.did !in getDids(applicationContext, onlyRetrieveMessages = true)) continue
            val response = try {
                httpPostWithMultipartFormData<MessagesResponse>(
                    applicationContext,
                    "https://voip.ms/api/v1/rest.php",
                    // VoIP.ms uses -5 to return its unshifted Eastern wall clock,
                    // including summer dates. Parse those timestamps in America/New_York.
                    mapOf(
                        "api_username" to credentials.email, "api_password" to credentials.password,
                        "method" to "getMMS", "did" to request.did, "all_messages" to "1",
                        "from" to request.window.from, "to" to request.window.to,
                        "limit" to "1000000", "timezone" to "-5"
                    )
                )
            } catch (e: HttpStatusException) {
                if (e.retryable) throw e
                failureCause = e
                accountRejected = e.statusCode == 401 || e.statusCode == 403
                error = applicationContext.getString(R.string.sync_error_api_error, "HTTP " + e.statusCode)
                return@coroutineScope
            } catch (e: JsonEncodingException) {
                failureCause = e
                null
            } catch (e: JsonDataException) {
                failureCause = e
                null
            }

            ensureActive()
            val rows = when (response?.status) {
                "success" -> response.sms?.rows
                "no_sms", "no_mms" -> emptyList()
                "invalid_credentials" -> {
                    accountRejected = true
                    error = applicationContext.getString(R.string.sync_error_api_error_invalid_credentials)
                    return@coroutineScope
                }
                null -> null
                else -> {
                    error = applicationContext.getString(R.string.sync_error_api_error, response.status)
                    if (response.status == "invalid_did") rejectedDids.add(request.did)
                    incompleteDids.add(request.did)
                    continue
                }
            }
            if (rows == null) {
                error = applicationContext.getString(R.string.sync_error_api_parse)
                incompleteDids.add(request.did)
                continue
            }

            val formatter = messageDateFormat()
            val messages = rows.mapNotNull { it?.incoming(request.did, formatter) }
            val complete = rows.size == messages.size
            if (!complete) {
                error = applicationContext.getString(R.string.sync_error_api_parse)
                incompleteDids.add(request.did)
            }
            if (messages.isNotEmpty() || complete) {
                // Commit each inclusive window before starting another network request.
                // A later failure leaves earlier messages both visible and notified.
                try {
                    val applied = withCurrentAccount(applicationContext, credentials.generation) {
                        if (request.did !in getDids(applicationContext, onlyRetrieveMessages = true)) {
                            return@withCurrentAccount true
                        }
                        if (appliedMessages == null) trace.step(DiagnosticStep.WRITING)
                        val conversations = database.insertMessagesVoipMsApi(
                            messages, retrieveDeletedMessages, request.checkpoint.takeIf { request.did !in incompleteDids },
                            request.previousThrough
                        )
                        appliedMessages = (appliedMessages ?: 0L) + messages.size
                        net.kourlas.voipms_sms.delivery.DeliveryTrace.fetched(applicationContext, request.did, messages, route)
                        if (conversations.isNotEmpty()) {
                            Notifications.getInstance(applicationContext).showNotifications(conversations)
                        }
                        true
                    }
                    if (applied != true) return@coroutineScope
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    failureCause = e
                    Log.e(TAG, "Failed to apply synchronized messages", e)
                    error = applicationContext.getString(R.string.sync_error_database)
                    return@coroutineScope
                }
            }

            if (!forceRecent) onProgress(((index + 1) * 100) / retrievalRequests.size)
        }
    }

    private data class RetrievalRequest(
        val did: String, val window: RetrievalWindow, val checkpoint: SyncCheckpoint,
        val previousThrough: String?
    )

    companion object {
        private val incrementalSync = Mutex()
    }
}
