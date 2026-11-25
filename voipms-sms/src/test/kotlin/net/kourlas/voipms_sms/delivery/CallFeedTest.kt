package net.kourlas.voipms_sms.delivery

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CallFeedTest {
    @Before
    @After
    fun closeSandboxDatabase() {
        // Robolectric creates a new app data directory between methods in the same JVM.
        val storage = CallFeedStore::class.java.getDeclaredField("storage")
        storage.isAccessible = true
        (storage.get(null) as? SQLiteOpenHelper)?.close()
        storage.set(null, null)
    }

    @Test fun initialHistoryIsQuietAndRepeatingAProviderEventNeverAlertsTwice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val did = "4165550100"
        val call = CallFeedItem("call-one",did,"6475550100",1000L,false)
        val voice = CallFeedItem("voice-one",did,call.peer,2200L,true,"1001","INBOX","1")
        assertTrue(CallFeedStore.save(context,"test-first","cdr",listOf(call),2000L).isEmpty())
        assertTrue(CallFeedStore.save(context,"test-first","box",emptyList(),2000L).isEmpty())
        assertEquals(listOf(voice),CallFeedStore.save(context,"test-first","box",listOf(voice),2400L))
        assertTrue(CallFeedStore.save(context,"test-first","box",listOf(voice.copy(slot="2")),2500L).isEmpty())
        assertEquals(2500L,CallFeedStore.last(context,"test-first","box"))
    }

    @Test fun changingMailboxRoutingPreservesHistoricalScopeAndUpdatesOnlyThePlaybackSlot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "route-history"
        val first = "4165550100"
        val second = "6475550100"
        val shared = CallFeedItem("shared-recording","","9055550100",1000L,true,"1001","INBOX","1",
            mailboxLines = setOf(first, second))
        val single = shared.copy(id="single-recording", did=first, mailboxLines=setOf(first))
        CallFeedStore.save(context,account,"box",listOf(shared,single),2000L)
        assertTrue(CallFeedStore.save(context,account,"box",listOf(shared.copy(did=second,slot="2",mailboxLines=setOf(second)),
            single.copy(did=second,slot="3",mailboxLines=setOf(second))),3000L).isEmpty())
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("call_trail.db").absolutePath,
            null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT did,slot,mailboxLines FROM events WHERE account=? ORDER BY id",arrayOf(account)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("",c.getString(0))
                assertEquals("2",c.getString(1))
                assertEquals(setOf(first,second),c.getString(2).split(',').toSet())
                assertTrue(c.moveToNext())
                assertEquals(first,c.getString(0))
                assertEquals("3",c.getString(1))
                assertEquals(first,c.getString(2))
            }
        }
    }

    @Test fun anEpochBackedLegacyRecordingIsPromotedWithoutASecondNotificationOrNewLineAttribution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = "legacy-epoch"
        val epoch = 1795636800L
        val date = "2026-11-25"
        val peer = "4165550100"
        val original = CallFeedItem("vm:1001:$date:$peer:00:00:18:0","6475550100",peer,epoch*1000L,
            true,"1001","INBOX","1",peer,date)
        val promoted = original.copy(id="vme:1001:$epoch:$peer:00:00:18:0", did="9055550100", slot="7")
        CallFeedStore.save(context,key,"mailbox",listOf(original),epoch*1000L)
        assertTrue(CallFeedStore.save(context,key,"mailbox",listOf(promoted),epoch*1000L+200).isEmpty())
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("call_trail.db").absolutePath,
            null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,did,slot FROM events WHERE account=?",arrayOf(key)).use { rows ->
                assertEquals(1,rows.count)
                assertTrue(rows.moveToFirst())
                assertEquals(promoted.id,rows.getString(0))
                assertEquals(original.did,rows.getString(1))
                assertEquals("7",rows.getString(2))
            }
        }
    }

    @Test fun callerIdentityKeepsPrivateCallersAnonymousAndNormalizesNanp() {
        assertEquals("4165550100",callerNumber("Caller <+1 (416) 555-0100>"))
        assertNull(callerNumber("Private Number"))
        assertNull(callerNumber("acct1234567890"))
    }
}
