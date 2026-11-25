package net.kourlas.voipms_sms.sms

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kourlas.voipms_sms.utils.awaitDecoded
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDeliveryBackoffTest {
    @Test
    fun repeatedErrorsBackOffAndRecoveredNetworkRestoresRegularChecks() {
        var interval = LiveDeliveryService.CHECK_MS
        for (expected in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            interval = LiveDeliveryService.nextCheckInterval(interval, success = false)
            assertEquals(expected, interval)
        }
        assertEquals(15_000L, LiveDeliveryService.nextCheckInterval(interval, success = true))
    }

    @Test
    fun stoppingTheSessionCancelsAnInFlightProviderCall() = runBlocking {
        var enqueued = false
        val real = OkHttpClient().newCall(Request.Builder().url("https://example.invalid/").build())
        val waiting = object : Call by real {
            override fun enqueue(responseCallback: Callback) { enqueued = true }
        }
        val session = launch(start = CoroutineStart.UNDISPATCHED) {
            waiting.awaitDecoded { error("No provider response expected") }
        }
        assertTrue(enqueued)
        session.cancelAndJoin()
        assertTrue(real.isCanceled())
    }
}
