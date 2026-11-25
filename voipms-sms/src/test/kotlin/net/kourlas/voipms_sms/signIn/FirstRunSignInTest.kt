package net.kourlas.voipms_sms.signIn

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.preferences.setFirstRun
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FirstRunSignInTest {
    @Test
    fun skippingOnboardingClosesAnotherOnboardingInstanceAndAllowsManualSignIn() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manual = Intent(app, SignInActivity::class.java)
        val onboarding = Intent(manual).putExtra(SignInActivity.EXTRA_FIRST_RUN, true)
        val pending = Robolectric.buildActivity(SignInActivity::class.java, onboarding).setup()
        assertFalse(pending.get().isFinishing)
        pending.pause()

        setFirstRun(app, false)
        pending.resume()
        assertTrue(pending.get().isFinishing)
        pending.pause().stop().destroy()

        val explicit = Robolectric.buildActivity(SignInActivity::class.java, manual).setup()
        assertFalse(explicit.get().isFinishing)
        explicit.pause().stop().destroy()
    }
}
