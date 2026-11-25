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

package net.kourlas.voipms_sms

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.preferences.accountConfigured
import net.kourlas.voipms_sms.preferences.didsConfigured
import net.kourlas.voipms_sms.preferences.fragments.AppearancePreferencesFragment
import net.kourlas.voipms_sms.preferences.getAppTheme
import net.kourlas.voipms_sms.preferences.getSyncInterval
import net.kourlas.voipms_sms.preferences.setRawSyncInterval
import net.kourlas.voipms_sms.sms.LiveDeliveryService
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.preferences.migrateDidPreferences
import net.kourlas.voipms_sms.sms.workers.SyncWorker
import net.kourlas.voipms_sms.utils.subscribeToDidTopics
import net.kourlas.voipms_sms.ui.ChatFont
import kotlin.math.roundToLong

private const val TAG = "CustomApplication"

class CustomApplication : Application() {
    private var conversationsActivitiesVisible = 0
    private val conversationActivitiesVisible: MutableMap<ConversationId, Int> =
        HashMap()

    fun conversationsActivityVisible(): Boolean {
        return conversationsActivitiesVisible > 0
    }

    fun conversationsActivityIncrementCount() {
        conversationsActivitiesVisible++
    }

    fun conversationsActivityDecrementCount() {
        conversationsActivitiesVisible--
    }

    fun conversationActivityVisible(conversationId: ConversationId): Boolean {
        val count = conversationActivitiesVisible[conversationId]
        return count != null && count > 0
    }

    fun conversationActivityIncrementCount(
        conversationId: ConversationId
    ) {
        var count = conversationActivitiesVisible[conversationId]
        if (count == null) {
            count = 0
        }
        conversationActivitiesVisible[conversationId] = count + 1
    }

    fun conversationActivityDecrementCount(
        conversationId: ConversationId
    ) {
        var count = conversationActivitiesVisible[conversationId]
        if (count == null) {
            count = 0
        }
        conversationActivitiesVisible[conversationId] = count - 1
    }

    /**
     * Scope for work that must continue after the activity that started it
     * stops. If a coroutine in this scope throws an exception and does not
     * catch it, the handler logs the exception and shows a general toast
     * instead of stopping the app.
     */
    val applicationScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Uncaught exception in application scope", e)
            // The handler can run on a background thread, so show the toast
            // on the main thread.
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.toast_unexpected_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        })

    private var visibleMessagingScreens = 0
    private var visibleRefresh: Job? = null

    // Activities call these on the main thread. Multiple windows share one poller.
    fun startVisibleRefresh(immediate: Boolean = true) {
        visibleMessagingScreens++
        if (visibleMessagingScreens != 1 || BuildConfig.IS_DEMO) return
        visibleRefresh = applicationScope.launch(Dispatchers.Default) {
            if (!immediate) delay(VISIBLE_REFRESH_MS)
            while (isActive) {
                if (!LiveDeliveryService.state.value.running && accountConfigured(applicationContext) && didsConfigured(applicationContext)) {
                    SyncWorker.performPartialSynchronization(applicationContext)
                }
                delay(VISIBLE_REFRESH_MS)
            }
        }
    }

    fun stopVisibleRefresh() {
        visibleMessagingScreens--
        if (visibleMessagingScreens == 0) {
            visibleRefresh?.cancel()
            visibleRefresh = null
        }
    }

    override fun onCreate() {
        super.onCreate()

        net.kourlas.voipms_sms.privacy.PortableBackup.clearInterruptedWork(this)
        ChatFont.start(this)

        // Create static reference to self.
        self = this
        migrateDidPreferences(applicationContext)

        // Limit synchronization interval to 15 minutes. Previous versions
        // supported a shorter interval.
        val syncInterval = getSyncInterval(applicationContext)
        if (syncInterval > 0.0 && (syncInterval * 24 * 60).roundToLong() < 15) {
            setRawSyncInterval(applicationContext, "0.01041666666")
        }

        // Update theme
        when (getAppTheme(applicationContext)) {
            AppearancePreferencesFragment.SYSTEM_DEFAULT -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )

            AppearancePreferencesFragment.LIGHT -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_NO
            )

            AppearancePreferencesFragment.DARK -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_YES
            )
        }

        // Open the database.
        Database.getInstance(applicationContext)

        // Subscribe to topics for current DIDs.
        subscribeToDidTopics(applicationContext)

        // Schedule a database synchronization, if required.
        SyncWorker.scheduleBackgroundSync(applicationContext)
        net.kourlas.voipms_sms.delivery.CallFeedSync.refresh(applicationContext)

        applicationScope.launch(Dispatchers.Default) {
            net.kourlas.voipms_sms.sms.workers.Outbox.recover(applicationContext)
        }
    }

    companion object {
        private const val VISIBLE_REFRESH_MS = 30_000L
        private lateinit var self: CustomApplication

        fun getApplication(): CustomApplication {
            return self
        }
    }
}