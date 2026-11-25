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
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.entities.SyncCheckpoint
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.accountConfigured
import net.kourlas.voipms_sms.preferences.didsConfigured
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getEmail
import net.kourlas.voipms_sms.preferences.getPassword
import net.kourlas.voipms_sms.preferences.getRetrieveDeletedMessages
import net.kourlas.voipms_sms.preferences.getRetrieveOnlyRecentMessages
import net.kourlas.voipms_sms.preferences.getStartDate
import net.kourlas.voipms_sms.preferences.getSyncInterval
import net.kourlas.voipms_sms.utils.HttpStatusException
import net.kourlas.voipms_sms.utils.httpPostWithMultipartFormData
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

private const val TAG = "SyncWorker"

/**
 * Worker used to synchronize the database with VoIP.ms.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private var error: String? = null
    private var progress: Int = 0

    private val forceRecent: Boolean
        get() = inputData.getBoolean(applicationContext.getString(R.string.sync_force_recent), false)
    private var transientFailure = false

    override suspend fun doWork(): Result {
        try {
            if (forceRecent) {
                incrementalSync.withLock {
                    val did = inputData.getString(RECEIVE_DID)
                    val slot = inputData.getString(PUSH_SLOT)
                    if (slot != null) {
                        // A wakeup arriving after the fetch starts needs its own pass.
                        pendingPushSlots.started(did, slot)
                    }
                    handleSync()
                }
            } else {
                handleSync()
            }

            return if (transientFailure && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else if (error == null) {
                Result.success()
            } else {
                Log.w(TAG, error ?: "")
                Result.failure()
            }
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

    @SuppressLint("InlinedApi")
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = Notifications.getInstance(applicationContext)
            .getSyncDatabaseNotification(id, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.SYNC_DATABASE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                Notifications.SYNC_DATABASE_NOTIFICATION_ID,
                notification
            )
        }
    }

    /**
     * Perform synchronization.
     */
    private suspend fun handleSync() {
        try {
            // Terminate quietly if it is impossible to sync due to account
            // configuration, unless it is a full sync.
            if (!accountConfigured(applicationContext)) {
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
            val email = getEmail(applicationContext)
            val password = getPassword(applicationContext)
            val retrievalRequests = createRetrievalRequests(retrieveOnlyRecentMessages, accountKey(email))
            processRequests(retrievalRequests, retrieveDeletedMessages, email, password)
        } catch (e: CancellationException) {
            // We need to propagate the exception from processRequests
            error = applicationContext.getString(R.string.sync_error_cancelled)
            throw e
        } catch (e: IOException) {
            transientFailure = true
            error = applicationContext.getString(R.string.sync_error_api_request)
        } catch (e: Exception) {
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
        val target = inputData.getString(RECEIVE_DID)
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
        email: String,
        password: String
    ) = coroutineScope {
        val rejectedDids = mutableSetOf<String>()
        val incompleteDids = mutableSetOf<String>()
        val database = Database.getInstance(applicationContext)
        for ((index, request) in retrievalRequests.withIndex()) {
            ensureActive()
            if (request.did in rejectedDids) continue
            val response = try {
                httpPostWithMultipartFormData<MessagesResponse>(
                    applicationContext,
                    "https://voip.ms/api/v1/rest.php",
                    // VoIP.ms uses -5 to return its unshifted Eastern wall clock,
                    // including summer dates. Parse those timestamps in America/New_York.
                    mapOf(
                        "api_username" to email, "api_password" to password,
                        "method" to "getMMS", "did" to request.did, "all_messages" to "1",
                        "from" to request.window.from, "to" to request.window.to,
                        "limit" to "1000000", "timezone" to "-5"
                    )
                )
            } catch (e: HttpStatusException) {
                if (e.retryable) throw e
                error = applicationContext.getString(R.string.sync_error_api_error, "HTTP " + e.statusCode)
                return@coroutineScope
            } catch (_: JsonEncodingException) {
                null
            } catch (_: JsonDataException) {
                null
            }

            val rows = when (response?.status) {
                "success" -> response.sms?.rows
                "no_sms", "no_mms" -> emptyList()
                "invalid_credentials" -> {
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
                val conversations = try {
                    database.insertMessagesVoipMsApi(
                        messages, retrieveDeletedMessages, request.checkpoint.takeIf { request.did !in incompleteDids },
                        request.previousThrough
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert synchronized messages", e)
                    error = applicationContext.getString(R.string.sync_error_database)
                    return@coroutineScope
                }
                if (conversations.isNotEmpty()) {
                    Notifications.getInstance(applicationContext).showNotifications(conversations)
                }
            }

            progress = ((index + 1) * 100) / retrievalRequests.size
            if (!forceRecent) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    setForeground(getForegroundInfo())
                } else {
                    try {
                        setForeground(getForegroundInfo())
                    } catch (_: ForegroundServiceStartNotAllowedException) {
                        // Android may decline foreground promotion for background work.
                    }
                }
            }
        }
    }

    private data class RetrievalRequest(
        val did: String, val window: RetrievalWindow, val checkpoint: SyncCheckpoint,
        val previousThrough: String?
    )

    companion object {
        private const val MAX_RETRIES = 4
        private const val RECEIVE_DID = "receive_did"
        private const val PUSH_SLOT = "push_slot"
        private val connected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        private val incrementalSync = Mutex()
        private val pendingPushSlots = PushWorkSlots()

        /** Reconcile the persisted recovery job when the account or interval changes. */
        fun scheduleBackgroundSync(context: Context, period: Double = getSyncInterval(context)) {
            val manager = WorkManager.getInstance(context)
            // Older releases used one name for both periodic and one-time work.
            // WorkManager cannot update one type into the other.
            val oldName = context.getString(R.string.sync_work_id)
            manager.cancelUniqueWork(oldName)
            val name = oldName + ".recovery"
            if (period <= 0.0 || !accountConfigured(context) || !didsConfigured(context)) {
                manager.cancelUniqueWork(name)
                return
            }
            val minutes = (period * 24 * 60).roundToLong().coerceAtLeast(15)
            val recovery = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(connected)
                .setInputData(workDataOf(context.getString(R.string.sync_force_recent) to true))
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, recovery)
        }

        /** Explicit history synchronization runs immediately even when periodic work exists. */
        fun performFullSynchronization(context: Context) {
            val work = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                context.getString(R.string.sync_work_id) + ".manual",
                ExistingWorkPolicy.KEEP, work
            )
        }

        /** A visible inbox or thread needs at most one pending background fetch. */
        fun performPartialSynchronization(context: Context) {
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connected)
                .setInputData(workDataOf(context.getString(R.string.sync_force_recent) to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                context.getString(R.string.sync_partial_work_id),
                ExistingWorkPolicy.KEEP, work
            )
        }

        /**
         * Bursts share a pending request until the worker starts its fetch. A later
         * hint gets a new request, even while that first fetch is still in flight.
         * WorkManager persists each request. After process death the in-memory map
         * may allow one extra fetch, which the database can safely deduplicate.
         */
        fun receivePush(context: Context, did: String? = null) {
            val slot = pendingPushSlots.pending(did)
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connected)
                .setInputData(workDataOf(
                    context.getString(R.string.sync_force_recent) to true,
                    RECEIVE_DID to did,
                    PUSH_SLOT to slot
                ))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                context.getString(R.string.sync_partial_work_id) + "." + slot,
                ExistingWorkPolicy.KEEP, work
            )
        }
    }
}
