package net.kourlas.voipms_sms.rich

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Restores the existing SDK device and its send queue when Android next allows network work. */
class MatrixSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        if (MatrixChat.account(applicationContext) == null) return@withContext Result.success()
        try {
            val s = MatrixSessions.open(applicationContext)
            // Wait for the live sync loop rather than racing it with a second /sync cursor.
            val current = s.updates.value
            val updated = withTimeoutOrNull(65_000) { s.updates.first { it != current || it < 0 } }
            if (updated == null) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
    internal companion object {
        private const val PERIODIC = "matrix-chat-catchup"
        private const val CONNECTED = "matrix-chat-send"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(c: Context) {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MatrixSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
        }
        fun wake(c: Context) {
            WorkManager.getInstance(c).enqueueUniqueWork(CONNECTED, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MatrixSyncWorker>().setConstraints(constraints).build())
        }
        fun stop(c: Context) {
            WorkManager.getInstance(c).apply { cancelUniqueWork(CONNECTED); cancelUniqueWork(PERIODIC) }
        }
    }
}
