package net.kourlas.voipms_sms.sms.workers

import java.util.UUID

/** Groups wakeups for a line only until its scheduled worker starts fetching. */
internal class PushWorkSlots {
    private val pendingByDid = mutableMapOf<String?, String>()

    @Synchronized
    fun pending(did: String?): String =
        pendingByDid.getOrPut(did) { UUID.randomUUID().toString() }

    @Synchronized
    fun started(did: String?, slot: String) {
        if (pendingByDid[did] == slot) pendingByDid.remove(did)
    }
}
