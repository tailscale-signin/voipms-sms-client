package net.kourlas.voipms_sms.delivery

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.conversations.ConversationsActivity
import net.kourlas.voipms_sms.sms.ConversationId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CallAlertRoutingTest {
    @Test fun sameCallerAcrossTwoLinesAndTwoKindsGetsDistinctTapTargets() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val work = ConversationId("4165550100","9055550191")
        val home = ConversationId("6475550100","9055550191")
        val missed = CallAlerts.tag(1, "123", work, false)
        val voicemail = CallAlerts.tag(1, "123", work, true)
        assertEquals(missed, CallAlerts.tag(1, "123", work, false))
        assertEquals(4, setOf(missed, voicemail, CallAlerts.tag(1,"123",home,false),
            CallAlerts.tag(2,"123",work,false)).size)
        assertFalse(missed.contains(work.contact))
        val missedTarget = CallAlerts.tapIntent(context, work, false, 1, missed)
        val voicemailTarget = CallAlerts.tapIntent(context, work, true, 1, voicemail)
        assertFalse(missedTarget.filterEquals(voicemailTarget))
        assertEquals(ConversationsActivity::class.java.name, missedTarget.component?.className)
        assertTrue(missedTarget.getBooleanExtra(CallAlerts.SHOW_CALL_HOME, false))
        assertEquals(ConversationActivity::class.java.name, voicemailTarget.component?.className)
        assertEquals(work.did, voicemailTarget.getStringExtra(context.getString(R.string.conversation_did)))
        assertEquals(work.contact, voicemailTarget.getStringExtra(context.getString(R.string.conversation_contact)))
        assertEquals(1L, voicemailTarget.getLongExtra(CallAlerts.GENERATION, -1))
    }
}
