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

package net.kourlas.voipms_sms.utils

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import androidx.preference.PreferenceManager
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.workers.NotificationsRegistrationWorker
import net.kourlas.voipms_sms.notifications.PushRegistration
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.setSetupCompletedForVersion

private const val TAG = "Fcm"

/**
 * Retrieves the Firebase installation ID.
 */
suspend fun getInstallationId(): String {
    return try {
        FirebaseInstallations.getInstance().id.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get Firebase installation ID", e)
        "Not available"
    }
}

/** Describes why this installation cannot use its Firebase relay, if applicable. */
fun pushUnavailableMessage(context: Context): Int? = when {
    BuildConfig.PUSH_CALLBACK_URL.isEmpty() || FirebaseApp.getApps(context).none {
        it.name == FirebaseApp.DEFAULT_APP_NAME
    } -> R.string.push_unconfigured
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) !=
        ConnectionResult.SUCCESS -> R.string.push_no_play_services
    else -> null
}

fun subscribeToDidTopics(context: Context) {
    if (pushUnavailableMessage(context) != null) return

    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val credentials = readAccountCredentials(context) ?: return
    val desired = if (credentials.configured) getDids(context, onlyRetrieveMessages = true)
        else emptySet()
    val recorded = PushRegistration.read(context).keys
    if (desired.isEmpty() && recorded.isEmpty() &&
        prefs.getStringSet(NotificationsRegistrationWorker.TOPICS_KEY, emptySet()).isNullOrEmpty()
    ) return

    val lastCheck = prefs.getLong(NotificationsRegistrationWorker.LAST_CHECK_KEY, 0L)
    if (desired != recorded || System.currentTimeMillis() - lastCheck !in 0 until 86_400_000L) {
        NotificationsRegistrationWorker.enqueue(context)
    }
}

@Suppress("UNUSED_PARAMETER")
fun enablePushNotifications(
    context: Context,
    activityToShowError: FragmentActivity? = null
) {
    val unavailable = pushUnavailableMessage(context)
    if (unavailable != null) {
        setSetupCompletedForVersion(context, BuildConfig.VERSION_CODE.toLong())
        return
    }
    if (readAccountCredentials(context)?.configured != false) {
        // A token refresh while secure storage is unavailable still needs a retry.
        NotificationsRegistrationWorker.enqueue(context, explicit = true)
    }
}
