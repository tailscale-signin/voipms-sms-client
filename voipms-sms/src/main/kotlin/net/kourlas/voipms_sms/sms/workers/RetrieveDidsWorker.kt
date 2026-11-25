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
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.AccountCredentials
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.preferences.setDids
import net.kourlas.voipms_sms.utils.enablePushNotifications
import net.kourlas.voipms_sms.utils.httpPostWithMultipartFormData
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid
import java.io.IOException

private const val TAG = "RetrieveDidsWorker"

/**
 * Worker used to retrieve DIDs for a particular account from VoIP.ms.
 */
class RetrieveDidsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private var error: String? = null

    override suspend fun doWork(): Result {
        val credentials = readAccountCredentials(applicationContext) ?: return Result.retry()
        val expected = inputData.getLong(ACCOUNT_GENERATION, credentials.generation)
        if (credentials.generation != expected) return Result.success()
        val dids = if (credentials.configured) retrieveDids(credentials) else null
        val current = readAccountCredentials(applicationContext) ?: return Result.retry()
        if (current.generation != expected) return Result.success()

        // Send broadcast with DIDs.
        val retrieveDidsCompleteIntent = Intent(
            applicationContext.getString(
                R.string.retrieve_dids_complete_action
            )
        )
        retrieveDidsCompleteIntent.putExtra(ACCOUNT_GENERATION, expected)
        retrieveDidsCompleteIntent.putExtra(
            applicationContext.getString(
                R.string.retrieve_dids_complete_error
            ), error
        )
        retrieveDidsCompleteIntent.putStringArrayListExtra(
            applicationContext.getString(R.string.retrieve_dids_complete_dids),
            if (dids != null) ArrayList<String>(dids.toList()) else null
        )
        retrieveDidsCompleteIntent.setPackage(
            applicationContext.packageName
        )
        applicationContext.sendBroadcast(retrieveDidsCompleteIntent)

        return if (error == null) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    @SuppressLint("InlinedApi")
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = Notifications.getInstance(applicationContext)
            .getSyncRetrieveDidsNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.SYNC_RETRIEVE_DIDS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            ForegroundInfo(
                Notifications.SYNC_RETRIEVE_DIDS_NOTIFICATION_ID,
                notification
            )
        }
    }

    /**
     * Retrieves and returns the DIDs associated with the configured VoIP.ms
     * account using the parameters from the specified intent.
     *
     * @return Null if an error occurred.
     */
    private suspend fun retrieveDids(credentials: AccountCredentials): Set<String>? = coroutineScope {
        // Retrieve DIDs from VoIP.ms API
        var dids: Set<String>? = null
        try {
            val response = getApiResponse(credentials)
            if (response != null) {
                dids = getDidsFromResponse(response)
            }

            val autoAdd = inputData.getBoolean(
                applicationContext.getString(R.string.retrieve_dids_auto_add),
                false
            )
            val fetched = dids
            if (autoAdd && fetched != null) {
                withCurrentAccount(applicationContext, credentials.generation) {
                    setDids(applicationContext, fetched)
                    enablePushNotifications(applicationContext)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve DIDs", e)
        }
        return@coroutineScope dids
    }

    @JsonClass(generateAdapter = true)
    data class DidResponse(
        @param:Json(name = "sms_enabled") val smsEnabled: String?,
        val did: String
    )

    @JsonClass(generateAdapter = true)
    data class DidsResponse(
        val status: String,
        val dids: List<DidResponse>?
    )

    /**
     * Gets the response of a getDIDsInfo call to the VoIP.ms API.
     *
     * @return Null if an error occurred.
     */
    private suspend fun getApiResponse(credentials: AccountCredentials): DidsResponse? {
        try {
            repeat(3) {
                try {
                    return httpPostWithMultipartFormData(
                        applicationContext,
                        "https://voip.ms/api/v1/rest.php",
                        mapOf(
                            "api_username" to credentials.email,
                            "api_password" to credentials.password,
                            "method" to "getDIDsInfo"
                        )
                    )
                } catch (e: IOException) {
                    // Try again...
                }
            }
            error = applicationContext.getString(
                R.string.preferences_dids_error_api_request
            )
            return null
        } catch (e: JsonDataException) {
            Log.e(TAG, "Failed to parse DID retrieval response", e)
            error = applicationContext.getString(
                R.string.preferences_dids_error_api_parse
            )
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send DID retrieval request", e)
            error = applicationContext.getString(
                R.string.preferences_dids_error_unknown
            )
            return null
        }
    }

    /**
     * Parses the response of a getDIDsInfo from the VoIP.ms API to
     * extract all DIDs with SMS enabled.
     *
     * @return Null if an error occurred.
     */
    private fun getDidsFromResponse(response: DidsResponse): Set<String>? {
        if (response.status != "success") {
            if (response.status == "no_did") {
                return emptySet()
            }
            error = when (response.status) {
                "invalid_credentials" -> applicationContext.getString(
                    R.string.preferences_dids_error_api_error_invalid_credentials
                )

                else -> applicationContext.getString(
                    R.string.preferences_dids_error_api_error,
                    response.status
                )
            }
            return null
        }

        return response.dids
            ?.filter { it.smsEnabled == "1" }
            ?.map { normalizeVoipMsDid(it.did) }
            ?.toSet()
    }

    companion object {
        private const val ACCOUNT_GENERATION = "retrieve_account_generation"

        fun isCurrentResult(context: Context, intent: Intent?): Boolean {
            val current = readAccountCredentials(context) ?: return false
            return intent?.getLongExtra(ACCOUNT_GENERATION, -1L) == current.generation
        }


        /**
         * Retrieve DIDs for a particular account from VoIP.ms.
         */
        fun retrieveDids(context: Context, autoAdd: Boolean = false) {
            val work = OneTimeWorkRequestBuilder<RetrieveDidsWorker>()
                .setInputData(
                    workDataOf(
                        context.getString(
                            R.string.retrieve_dids_auto_add
                        ) to autoAdd,
                        ACCOUNT_GENERATION to (readAccountCredentials(context)?.generation ?: -1L)
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                context.getString(R.string.retrieve_dids_work_id),
                ExistingWorkPolicy.REPLACE, work
            )
        }
    }
}