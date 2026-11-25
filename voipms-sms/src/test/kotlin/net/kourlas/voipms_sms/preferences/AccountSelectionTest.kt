package net.kourlas.voipms_sms.preferences

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.PushRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountSelectionTest {
    @Test
    fun legacyAndCurrentSessionsKeepSelectionsUntilAnAccountTransition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val setKey = context.getString(R.string.preferences_dids_key)
        val active = context.getString(R.string.preferences_dids_active_did_key)
        val single = context.getString(R.string.preferences_dids_did_key)
        val old = "4165550111"
        val new = "6475550112"
        prefs.edit().putStringSet(setKey, setOf(old)).putString(active, old)
            .putString(single, old).putStringSet(PushRegistration.KEY, setOf("$old|READY"))
            .putStringSet("push_subscribed_topics", setOf(old)).commit()

        // First launch after upgrading an existing signed-in installation.
        reconcileAccountSelection(context, 0)
        assertEquals(setOf(old), getDids(context))

        // Includes recovery from interruption between credential and selection commits.
        reconcileAccountSelection(context, 1)
        assertEquals(emptySet<String>(), getDids(context))
        assertFalse(prefs.contains(active))
        assertFalse(prefs.contains(single))
        assertFalse(prefs.contains(PushRegistration.KEY))
        assertEquals(setOf(old), prefs.getStringSet("push_subscribed_topics", emptySet()))

        prefs.edit().putStringSet(setKey, setOf(new)).commit()
        reconcileAccountSelection(context, 1)
        assertEquals(setOf(new), getDids(context))
        reconcileAccountSelection(context, 2)
        assertEquals(emptySet<String>(), getDids(context))
    }
}
