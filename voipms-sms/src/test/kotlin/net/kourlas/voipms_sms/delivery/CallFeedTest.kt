package net.kourlas.voipms_sms.delivery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallFeedTest {
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

    @Test fun callerIdentityKeepsPrivateCallersAnonymousAndNormalizesNanp() {
        assertEquals("4165550100",callerNumber("Caller <+1 (416) 555-0100>"))
        assertNull(callerNumber("Private Number"))
        assertNull(callerNumber("acct1234567890"))
    }
}
