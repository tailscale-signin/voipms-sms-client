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
        assertEquals(CallHistory.RecordingLocation("INBOX","2"), CallHistory.locateRecording(listOf(row(1,"00:00:06"),row(2)), item))
        assertNull(CallHistory.locateRecording(listOf(row(1),row(2)), item))
        assertEquals(CallHistory.RecordingLocation("Old","1"), CallHistory.locateRecording(listOf(row(1).put("folder","Old")), item))
        assertNull(CallHistory.locateRecording(listOf(row(1), row(1).put("folder", "Old")), item))
        assertNull(CallHistory.locateRecording(listOf(row(1).put("mailbox", "2002")), item))
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
        assertEquals(CallHistory.RecordingLocation("INBOX","5"), CallHistory.locateRecording(moved,early))
        assertEquals(CallHistory.RecordingLocation("INBOX","3"), CallHistory.locateRecording(moved,late))
        assertNull(CallHistory.locateRecording(listOf(row(3,null)),early))
        assertNull(CallHistory.locateRecording(listOf(row(3,firstEpoch), row(5,firstEpoch)),early))
        val dateOnly = CallHistory.voicemailItems(listOf(row(0,null)),"1001","6475550100",zone).single()
        assertTrue(dateOnly.dateOnly)
        assertNull(dateOnly.providerEpoch)
        assertNull(CallHistory.locateRecording(moved,dateOnly))
    }

    @Test fun historicalRecordingResolvesAcrossFoldersBeforeAndAfterDownload(): Unit = kotlinx.coroutines.runBlocking {
        val row = JSONObject().put("mailbox", "1001").put("folder", "INBOX").put("message_num", 0)
            .put("callerid", "4165550142").put("date", "2025-09-03 10:15:00").put("duration", 3)
        val event = CallHistory.voicemailItems(listOf(row), "1001", "6475550100", TimeZone.getTimeZone("UTC")).single()
        row.put("folder", "Old").put("message_num", 7)
        val audio = byteArrayOf(1, 2, 3, 4)
        val methods = mutableListOf<String>()
        val account = net.kourlas.voipms_sms.preferences.AccountCredentials("fixture@example.invalid", "fixture", 1)
        val result = CallHistory.audio(account, event) { _, method, args ->
            methods.add(method)
            if (method == "getVoicemailMessages") {
                assertEquals(mapOf("mailbox" to "1001"), args)
                JSONObject().put("messages", row)
            } else {
                assertEquals(mapOf("mailbox" to "1001", "folder" to "Old", "message_num" to "7"), args)
                JSONObject().put("message", JSONObject(args).put("data", android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)))
            }
        }
        assertArrayEquals(audio, result)
        assertEquals(listOf("getVoicemailMessages", "getVoicemailMessageFile", "getVoicemailMessages"), methods)
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
