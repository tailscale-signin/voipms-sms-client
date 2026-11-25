package net.kourlas.voipms_sms.sms

import org.junit.Assert.assertEquals
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
}
