package net.kourlas.voipms_sms.delivery

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallProviderSemanticsTest {
    @Test fun inboundDirectionAndExplicitVoicemailRouting() {
        assertTrue(CallHistory.isInbound(JSONObject().put("destination_type","IN:CAN")))
        assertTrue(CallHistory.isInbound(JSONObject().put("description","Inbound - Local Caller")))
        assertFalse(CallHistory.isInbound(JSONObject().put("destination_type","OUT:USA").put("description","Inbound - Local Caller")))
        assertFalse(CallHistory.isInbound(JSONObject().put("destination","4165550100")))
        assertEquals(setOf("1001","2001"), CallHistory.mailboxRoutes(JSONObject().put("voicemail",1001)
            .put("routing","vm:2001").put("failover_busy","vm:1001").put("failover_noanswer","fwd:301")))
    }

    @Test fun documentedDateOnlyAndFullVoicemailTimestampsAreRecognized() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(1454112000000L, CallHistory.instant(JSONObject().put("date","2016-01-30"), utc))
        assertEquals(1454145342000L, CallHistory.instant(JSONObject().put("date","2016-01-30 09:15:42"), utc))
        assertNull(CallHistory.instant(JSONObject().put("date","2016-01-30 09:15:42garbage"), utc))
    }

    @Test fun mailboxSlotCanMoveButIndistinguishableRecordingsRequireFreshEvidence() {
        val date = "2026-11-25 11:25:00"
        val peer = "4165550100"
        val item = CallFeedItem("vm:1001:$date:$peer:00:00:18:0", "6475550100", peer, 1, true, "1001", "INBOX", "1", peer, date)
        fun row(slot: Int, time: String = "00:00:18") = JSONObject().put("mailbox",1001)
            .put("folder","INBOX").put("message_num",slot).put("callerid",peer).put("date",date).put("duration",time)
        assertEquals("2", CallHistory.locateSlot(listOf(row(1,"00:00:06"),row(2)), item))
        assertNull(CallHistory.locateSlot(listOf(row(1),row(2)), item))
        assertNull(CallHistory.locateSlot(listOf(row(1).put("folder","Old")), item))
    }

    @Test fun originEpochIdentifiesTwoSameDayRecordingsAfterFolderRenumbering() {
        val zone = TimeZone.getTimeZone("America/Toronto")
        val firstEpoch = 1795636800L
        val secondEpoch = firstEpoch + 3600
        fun row(slot: Int, epoch: Long?) = JSONObject().put("mailbox",1001).put("folder","INBOX")
            .put("message_num",slot).put("callerid","4165550100").put("date","2026-11-25")
            .put("duration","00:00:18").apply { if (epoch != null) put("origtime",epoch) }
        val oldRows = listOf(row(0,firstEpoch), row(1,secondEpoch))
        val (early,late) = CallHistory.voicemailItems(oldRows,"1001","6475550100",zone)
        assertNotEquals(early.id,late.id)
        assertFalse(early.dateOnly)
        assertEquals(firstEpoch * 1000, early.receivedAt)
        assertEquals(firstEpoch, early.providerEpoch)
        val moved = listOf(row(3,secondEpoch), row(5,firstEpoch))
        assertEquals("5",CallHistory.locateSlot(moved,early))
        assertEquals("3",CallHistory.locateSlot(moved,late))
        assertNull(CallHistory.locateSlot(listOf(row(3,null)),early))
        assertNull(CallHistory.locateSlot(listOf(row(3,firstEpoch), row(5,firstEpoch)),early))
        val dateOnly = CallHistory.voicemailItems(listOf(row(0,null)),"1001","6475550100",zone).single()
        assertTrue(dateOnly.dateOnly)
        assertNull(dateOnly.providerEpoch)
        assertNull(CallHistory.locateSlot(moved,dateOnly))
    }

    @Test fun stopReleasesInMemoryRecordingAndDecoderReadsHaveBounds() {
        val source = VoicemailDataSource(byteArrayOf(1,2,3,4))
        val buffer = ByteArray(5)
        assertEquals(2, source.readAt(2,buffer,1,4))
        assertArrayEquals(byteArrayOf(0,3,4,0,0),buffer)
        assertEquals(-1,source.readAt(4,buffer,0,1))
        assertEquals(0,source.readAt(4,buffer,0,0))
        source.close()
        source.close()
        assertThrows(IOException::class.java) { source.readAt(0,buffer,0,1) }
    }
}
