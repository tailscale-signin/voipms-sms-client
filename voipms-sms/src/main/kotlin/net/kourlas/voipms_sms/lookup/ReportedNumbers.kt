package net.kourlas.voipms_sms.lookup

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.utils.HttpClientManager
import net.kourlas.voipms_sms.utils.awaitDecoded
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** A public aggregate is downloaded once for all senders. Matching stays on the device. */
internal object ReportedNumbers {
    private const val DAY = 86_400_000L
    private const val LIMIT = 5000
    val revision = MutableStateFlow(0L)
    private fun store(context: Context) = context.getSharedPreferences("fcc-caller-reports", Context.MODE_PRIVATE)

    fun schedule(context: Context) {
        if (BuildConfig.IS_DEMO) return
        val request = PeriodicWorkRequestBuilder<CallerReportWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("fcc-caller-reports", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun snapshot(context: Context, now: Long = System.currentTimeMillis()): Set<String> {
        val prefs = store(context)
        if (now - prefs.getLong("updated", 0L) !in 0 until 7 * DAY) return emptySet()
        return prefs.getStringSet("numbers", emptySet()).orEmpty()
    }

    internal fun request(since: LocalDate): Request {
        val url = "https://opendata.fcc.gov/resource/3xyp-aqkj.json".toHttpUrl().newBuilder()
            .addQueryParameter("\$select", "caller_id_number, count(*) as reports")
            .addQueryParameter("\$where", "issue = 'Unwanted Calls' AND date_created >= '${since}T00:00:00' AND caller_id_number IS NOT NULL")
            .addQueryParameter("\$group", "caller_id_number")
            .addQueryParameter("\$having", "count(*) >= 2")
            .addQueryParameter("\$order", "reports DESC, caller_id_number")
            .addQueryParameter("\$limit", LIMIT.toString()).build()
        return Request.Builder().url(url).build()
    }

    internal fun numbers(json: String): Set<String> {
        val rows = JSONArray(json)
        require(rows.length() <= LIMIT)
        return buildSet {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.getInt("reports") >= 2) WikidataDirectory.number(row.getString("caller_id_number"))?.let(::add)
            }
        }
    }

    suspend fun refresh(context: Context) {
        val call = HttpClientManager.getInstance().client.newBuilder().followRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).build().newCall(request(LocalDate.now(ZoneOffset.UTC).minusDays(90)))
        val numbers = call.awaitDecoded { response ->
            if (!response.isSuccessful || response.body.source().request(2L * 1024 * 1024)) {
                throw IOException("Caller report feed unavailable")
            }
            numbers(response.body.string())
        }
        store(context).edit(commit = true) {
            putStringSet("numbers", numbers)
            putLong("updated", System.currentTimeMillis())
        }
        revision.value++
    }
}

class CallerReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ReportedNumbers.refresh(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
