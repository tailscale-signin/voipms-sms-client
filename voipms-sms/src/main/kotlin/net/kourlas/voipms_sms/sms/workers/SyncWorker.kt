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
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.didsConfigured
import net.kourlas.voipms_sms.preferences.getSyncInterval
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

private const val TAG = "SyncWorker"

/**
 * Worker used to synchronize the database with VoIP.ms.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private var progress = 0

    override suspend fun doWork(): Result {
        val forceRecent = inputData.getBoolean(applicationContext.getString(R.string.sync_force_recent), false)
        val did = inputData.getString(RECEIVE_DID)
        val sync = MessageSynchronization(
            applicationContext, forceRecent, targetDid = did,
            route = when {
                inputData.getString(PUSH_SLOT) != null -> "Push"
                inputData.getBoolean("periodic_recovery", false) -> "Scheduled catch-up"
                else -> "Inbox or manual refresh"
            },
            onStarted = {
                inputData.getString(PUSH_SLOT)?.let { slot -> pendingPushSlots.started(did, slot) }
            },
            onProgress = { percent ->
                progress = percent
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
        )
        net.kourlas.voipms_sms.delivery.CallFeedSync.refresh(applicationContext)
        sync.run(retryOnTransient = runAttemptCount < MAX_RETRIES)
        return when {
            sync.transientFailure && runAttemptCount < MAX_RETRIES -> Result.retry()
            sync.error == null -> Result.success()
            else -> {
                Log.w(TAG, sync.error.orEmpty())
                Result.failure()
            }
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

    companion object {
        private const val MAX_RETRIES = 4
        private const val RECEIVE_DID = "receive_did"
        private const val PUSH_SLOT = "push_slot"
        private val connected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        private val pendingPushSlots = PushWorkSlots()

        /** Reconcile the persisted recovery job when the account or interval changes. */
        fun scheduleBackgroundSync(context: Context, period: Double = getSyncInterval(context)) {
            val manager = WorkManager.getInstance(context)
            // Older releases used one name for both periodic and one-time work.
            // WorkManager cannot update one type into the other.
            val oldName = context.getString(R.string.sync_work_id)
            // An inaccessible keystore must not look like a sign-out and cancel delivery.
            val enabled = period > 0.0 && didsConfigured(context) &&
                (readAccountCredentials(context)?.configured ?: return)
            manager.cancelUniqueWork(oldName)
            val name = oldName + ".recovery"
            if (!enabled) {
                manager.cancelUniqueWork(name)
                return
            }
            val minutes = (period * 24 * 60).roundToLong().coerceAtLeast(15)
            val recovery = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(connected)
                .setInputData(workDataOf(context.getString(R.string.sync_force_recent) to true, "periodic_recovery" to true))
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
