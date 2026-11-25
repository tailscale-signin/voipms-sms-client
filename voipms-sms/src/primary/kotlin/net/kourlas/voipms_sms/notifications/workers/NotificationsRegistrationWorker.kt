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

package net.kourlas.voipms_sms.notifications.workers

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.PushRegistration
import net.kourlas.voipms_sms.notifications.pushTopicForDid
import net.kourlas.voipms_sms.preferences.accountConfigured
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getEmail
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.utils.httpPostWithMultipartFormData
import net.kourlas.voipms_sms.utils.pushUnavailableMessage
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotificationsRegistrationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val retryStatus: PushRegistration
        get() = if (runAttemptCount < 4) PushRegistration.RETRYING else PushRegistration.FAILED

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (pushUnavailableMessage(context) != null) return Result.success()
        val credentials = readAccountCredentials(context) ?: return Result.retry()
        val email = credentials.email
        val desired = if (credentials.configured) getDids(context, onlyRetrieveMessages = true)
            else emptySet()
        val auth = mapOf("api_username" to email, "api_password" to credentials.password)
        val topics = prefs.getStringSet(TOPICS_KEY, emptySet()).orEmpty().toMutableSet()
        val outcomes = mutableMapOf<String, PushRegistration>()
        var cleanupFailed = false
        for (did in topics - desired) {
            if (changeTopic(did, subscribe = false)) {
                topics.remove(did)
                prefs.edit { putStringSet(TOPICS_KEY, topics.toSet()) }
            } else {
                cleanupFailed = true
            }
        }

        for (did in desired) {
            val topic = pushTopicForDid(did)
            if (topic == null) {
                outcomes[did] = PushRegistration.UNKNOWN_SETTINGS
                continue
            }
            if (!changeTopic(did, subscribe = true)) {
                outcomes[did] = retryStatus
                continue
            }
            topics.add(did)
            prefs.edit { putStringSet(TOPICS_KEY, topics.toSet()) }
            if (getEmail(context) != email || did !in getDids(context, onlyRetrieveMessages = true)) {
                return Result.retry()
            }
            outcomes[did] = checkCallback(auth, did, topic)
        }

        prefs.edit {
            putStringSet(PushRegistration.KEY, outcomes.map { (did, state) -> "$did|${state.name}" }.toSet())
            putLong(LAST_CHECK_KEY, System.currentTimeMillis())
        }
        if ((cleanupFailed && runAttemptCount < 4) || PushRegistration.RETRYING in outcomes.values ||
            desired != getDids(context, onlyRetrieveMessages = true) && accountConfigured(context)) {
            return Result.retry()
        }

        val failed = ArrayList(outcomes.filterValues { it != PushRegistration.READY }.keys)
        context.sendBroadcast(Intent(context.getString(R.string.push_notifications_reg_complete_action))
            .setPackage(context.packageName)
            .putStringArrayListExtra(context.getString(R.string.push_notifications_reg_complete_failed_dids), failed))
        return if (failed.isEmpty() && !cleanupFailed) Result.success() else Result.failure()
    }

    private suspend fun changeTopic(did: String, subscribe: Boolean): Boolean = try {
        withTimeoutOrNull(30_000) {
            val topic = pushTopicForDid(did) ?: return@withTimeoutOrNull false
            val messaging = FirebaseMessaging.getInstance()
            val task = if (subscribe) messaging.subscribeToTopic(topic)
                else messaging.unsubscribeFromTopic(topic)
            task.await()
            true
        } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun checkCallback(
        auth: Map<String, String>,
        did: String,
        topic: String
    ): PushRegistration {
        return try {
            // Read this number immediately before any write. Omitted callback fields
            // and a saved callback for another integration both block registration.
            val info = httpPostWithMultipartFormData<DidsResponse>(
                applicationContext, API, auth + mapOf("method" to "getDIDsInfo", "did" to did)
            )
            if (info?.status != "success") return providerFailure(info?.status)
            val settings = info.dids?.singleOrNull { pushTopicForDid(it.did) == topic }
                ?: return PushRegistration.UNKNOWN_SETTINGS
            settings.registrationBlock(BuildConfig.PUSH_CALLBACK_URL)?.let { return it }
            if (settings.callbackEnabled == "1") return PushRegistration.READY
            val response = httpPostWithMultipartFormData<RegisterResponse>(
                applicationContext, API, auth + mapOf(
                    "method" to "setSMS", "did" to settings.did, "enable" to "1",
                    "url_callback_enable" to "1", "url_callback" to BuildConfig.PUSH_CALLBACK_URL
                )
            )
            if (response?.status == "success") PushRegistration.READY else providerFailure(response?.status)
        } catch (e: IOException) {
            retryStatus
        } catch (e: JsonDataException) {
            PushRegistration.UNKNOWN_SETTINGS
        }
    }

    private fun providerFailure(status: String?): PushRegistration = when (status) {
        null, "sms_wait_message", "api_limit_exceeded", "internal_error" -> retryStatus
        else -> PushRegistration.PROVIDER_REJECTED
    }

    @JsonClass(generateAdapter = true)
    data class RegisterResponse(val status: String)

    @JsonClass(generateAdapter = true)
    data class DidsResponse(val status: String, val dids: List<ProviderDid>? = null)

    companion object {
        const val TOPICS_KEY = "push_subscribed_topics"
        const val LAST_CHECK_KEY = "push_registration_last_check"
        private const val API = "https://voip.ms/api/v1/rest.php"

        fun enqueue(context: Context, explicit: Boolean = false) {
            val work = OneTimeWorkRequestBuilder<NotificationsRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                context.getString(R.string.push_notifications_work_id),
                if (explicit) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work
            )
        }
    }
}

@JsonClass(generateAdapter = true)
data class ProviderDid(
    val did: String,
    @param:Json(name = "sms_enabled") val smsEnabled: String? = null,
    @param:Json(name = "sms_url_callback_enabled") val callbackEnabled: String? = null,
    @param:Json(name = "sms_url_callback") val callback: String? = MISSING_CALLBACK
) {
    /** A missing field is different from a provider-confirmed empty callback. */
    fun registrationBlock(relay: String): PushRegistration? = when {
        smsEnabled == "0" -> PushRegistration.SMS_DISABLED
        smsEnabled != "1" || callbackEnabled !in setOf("0", "1") || callback == MISSING_CALLBACK ->
            PushRegistration.UNKNOWN_SETTINGS
        !callback.isNullOrBlank() && callback != relay -> PushRegistration.CUSTOM_CALLBACK
        callbackEnabled == "1" && callback != relay -> PushRegistration.UNKNOWN_SETTINGS
        else -> null
    }

    companion object {
        private const val MISSING_CALLBACK = "<missing>"
    }
}
