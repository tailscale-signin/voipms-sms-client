package net.kourlas.voipms_sms.sms.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PushWorkSlotsTest {
    @Test
    fun wakeupsBeforeFetchStartsShareWorkButMidFetchWakeupsStartAnotherPass() {
        val slots = PushWorkSlots()
        val did = "4165550100"
        val first = slots.pending(did)
        assertEquals(first, slots.pending(did))

        slots.started(did, first)
        val next = slots.pending(did)
        assertNotEquals(first, next)
        // A WorkManager retry of the old request cannot clear newer pending work.
        slots.started(did, first)
        assertEquals(next, slots.pending(did))
    }

    @Test
    fun differentLinesAndDeletedMessageCatchUpHaveIndependentWork() {
        val slots = PushWorkSlots()
        val first = slots.pending("4165550100")
        val second = slots.pending("4165550199")
        val catchUp = slots.pending(null)
        assertEquals(3, setOf(first, second, catchUp).size)
        slots.started("4165550100", first)
        assertEquals(second, slots.pending("4165550199"))
        assertEquals(catchUp, slots.pending(null))
    }
}
