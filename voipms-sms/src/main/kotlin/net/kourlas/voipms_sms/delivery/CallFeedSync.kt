package net.kourlas.voipms_sms.delivery

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.sms.workers.accountKey

internal object CallFeedSync {
    private const val MINUTE = 60_000L
    fun refresh(context: Context, force: Boolean = false) {
        if (BuildConfig.IS_DEMO) return
        val account = readAccountCredentials(context)?.takeIf { it.configured } ?: return
        val state = DeliveryTrace.store(context)
        if (!force && System.currentTimeMillis() - state.getLong("lastCallAttempt.${account.generation}",0) in 0 until 2 * MINUTE) return
        val job = OneTimeWorkRequestBuilder<CallFeedWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("account" to account.generation)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("provider-call-feed.${account.generation}", ExistingWorkPolicy.KEEP,job)
    }
}

class CallFeedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val account = readAccountCredentials(ctx) ?: return@withContext Result.retry()
        if (!account.configured || account.generation != inputData.getLong("account",-1)) return@withContext Result.success()
        val dids = getDids(ctx, onlyRetrieveMessages = true)
        if (dids.isEmpty()) return@withContext Result.success()
        val key = accountKey(account.email)
        val now = System.currentTimeMillis()
        val prefs = DeliveryTrace.store(ctx)
        prefs.edit { putLong("lastCallAttempt.${account.generation}",now) }
        var failed = false
        suspend fun fetch(source: String, get: suspend () -> List<CallFeedItem>) {
            try {
                val rows = get()
                withCurrentAccount(ctx,account.generation) {
                    if (dids == getDids(ctx, onlyRetrieveMessages = true)) {
                        val added = CallFeedStore.save(ctx,key,source,rows,now)
                        CallAlerts.post(ctx,added,account.generation)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        val source = "cdr-" + dids.sorted().joinToString(",")
        val last = CallFeedStore.last(ctx,key,source)
        fetch(source) { CallHistory.missed(account,dids,if(last == 0L) now - 30 * 86_400_000L else minOf(last,now) - 86_400_000L) }
        try {
            for ((box, mapping) in CallHistory.mailboxes(account,dids)) {
                fetch("mailbox-$box") { CallHistory.voicemail(account,box,mapping.first,mapping.second) }
            }
        } catch(e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        prefs.edit { putBoolean("callFeedError.${account.generation}",failed) }
        if (failed && runAttemptCount < 2) Result.retry() else if(failed) Result.failure() else Result.success()
    }
}
