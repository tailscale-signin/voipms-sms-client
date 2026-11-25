package net.kourlas.voipms_sms.sms

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.activities.SynchronizationPreferencesActivity
import net.kourlas.voipms_sms.sms.workers.MessageSynchronization

internal data class LiveDeliveryState(
    val running: Boolean = false,
    val lastSuccess: Long? = null,
    val message: String? = null
)

/** A user-started, visible session for checking the provider directly when a push relay is unavailable. */
class LiveDeliveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, failure ->
        Log.w("LiveDeliveryService", "Direct listening stopped", failure)
        finishListening(getString(R.string.sync_error_unknown))
    })
    private var listener: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START) {
            finishListening()
            return START_NOT_STICKY
        }
        if (listener?.isActive == true) return START_NOT_STICKY
        try {
            Notifications.getInstance(this).createSyncNotificationChannel()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            ServiceCompat.startForeground(this, Notifications.MESSAGE_NOTIFICATION_ID + 1,
                listeningNotification(), type)
        } catch (_: IllegalStateException) {
            finishListening(getString(R.string.live_start_declined))
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            finishListening(getString(R.string.live_start_declined))
            return START_NOT_STICKY
        }

        val generation = intent.getLongExtra(ACCOUNT, -1)
        current.value = LiveDeliveryState(running = true)
        listener = scope.launch {
            var interval = CHECK_MS
            while (isActive) {
                val sessionEnded = withContext(Dispatchers.IO) {
                    val credentials = readAccountCredentials(applicationContext)
                    credentials != null && (!credentials.configured || credentials.generation != generation ||
                        getDids(applicationContext, onlyRetrieveMessages = true).isEmpty())
                }
                if (sessionEnded) {
                    finishListening()
                    return@launch
                }

                val sync = MessageSynchronization(applicationContext, forceRecent = true,
                    expectedAccountGeneration = generation)
                withContext(Dispatchers.IO) { sync.run() }
                if (sync.accountRejected) {
                    finishListening(sync.error)
                    return@launch
                }
                interval = nextCheckInterval(interval, sync.error == null)
                current.value = if (sync.error == null) {
                    LiveDeliveryState(running = true, lastSuccess = System.currentTimeMillis())
                } else {
                    val seconds = (interval / 1000).toInt()
                    current.value.copy(message = resources.getQuantityString(R.plurals.live_retry,
                        seconds, sync.error, seconds))
                }
                delay(interval)
            }
        }
        return START_NOT_STICKY
    }

    internal fun listeningNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, SynchronizationPreferencesActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, LiveDeliveryService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, getString(R.string.notifications_channel_sync))
            .setSmallIcon(R.drawable.ic_message_sync_toolbar_24dp)
            .setContentTitle(getString(R.string.live_notification))
            .setContentText(getString(R.string.live_notification_detail))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_delete_toolbar_24dp, getString(R.string.live_stop), stop)
            .build()
    }

    private fun finishListening(message: String? = null) {
        listener?.cancel()
        current.value = LiveDeliveryState(message = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        finishListening(getString(R.string.live_timeout))
    }

    override fun onDestroy() {
        scope.cancel()
        if (current.value.running) current.value = LiveDeliveryState()
        super.onDestroy()
    }

    companion object {
        internal const val CHECK_MS = 15_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val START = "net.kourlas.voipms_sms.START_LIVE_LISTENING"
        private const val STOP = "net.kourlas.voipms_sms.STOP_LIVE_LISTENING"
        private const val ACCOUNT = "account_generation"
        private val current = MutableStateFlow(LiveDeliveryState())
        internal val state = current.asStateFlow()

        internal fun nextCheckInterval(previous: Long, success: Boolean): Long =
            if (success) CHECK_MS else (previous * 2).coerceAtMost(MAX_BACKOFF_MS)

        internal fun start(context: Context) {
            val credentials = readAccountCredentials(context)
            if (credentials?.configured != true || getDids(context, onlyRetrieveMessages = true).isEmpty()) {
                current.value = LiveDeliveryState(message = context.getString(R.string.push_no_dids))
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, LiveDeliveryService::class.java)
                    .setAction(START).putExtra(ACCOUNT, credentials.generation))
            } catch (_: IllegalStateException) {
                current.value = LiveDeliveryState(message = context.getString(R.string.live_start_declined))
            } catch (_: SecurityException) {
                current.value = LiveDeliveryState(message = context.getString(R.string.live_start_declined))
            }
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, LiveDeliveryService::class.java))
        }
    }
}
