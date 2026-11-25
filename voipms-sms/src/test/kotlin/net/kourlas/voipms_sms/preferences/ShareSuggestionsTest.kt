package net.kourlas.voipms_sms.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.sms.ConversationId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareSuggestionsTest {
    @Test
    fun togglingOneLinePreservesOtherConversationsWithTheSamePeer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val work = ConversationId("4165550100", "6475550123")
        val home = work.copy(did = "4165550111")

        assertTrue(ShareSuggestions.enabled(context, work))
        ShareSuggestions.setEnabled(context, work, false)
        assertFalse(ShareSuggestions.enabled(context, work))
        assertTrue(ShareSuggestions.enabled(context, home))
        ShareSuggestions.setEnabled(context, work, true)
        assertTrue(ShareSuggestions.enabled(context, work))
    }
}
