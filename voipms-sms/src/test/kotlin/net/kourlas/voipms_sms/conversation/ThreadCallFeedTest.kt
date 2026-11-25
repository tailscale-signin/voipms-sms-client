package net.kourlas.voipms_sms.conversation

import net.kourlas.voipms_sms.delivery.CallFeedItem
import net.kourlas.voipms_sms.sms.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadCallFeedTest {
    @Test fun independentLinesAndUnassignedMailboxesDoNotLeakIntoContactHistory() {
        val thread = ConversationId("4165550123", "6475550124")
        val missed = CallFeedItem("cdr1",thread.did,thread.contact,200,false)
        val mail = CallFeedItem("vm1",thread.did,thread.contact,300,true)
        val events = listOf(missed, mail.copy(did=""), mail.copy(did="4165550100"), mail.copy(peer="6475550100"), mail, missed)
        assertEquals(listOf(mail,missed), ConversationRecyclerViewAdapter.matchingCalls(events,thread))
    }
}
