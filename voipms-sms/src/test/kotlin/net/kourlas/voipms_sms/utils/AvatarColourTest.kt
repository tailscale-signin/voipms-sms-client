package net.kourlas.voipms_sms.utils

import android.app.Application
import net.kourlas.voipms_sms.R
import androidx.test.core.app.ApplicationProvider
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AvatarColourTest {
    @Test fun contactUsesFirstLetterOrNumberFallback() {
        assertEquals("É", getContactInitial("  Élodie Martin"))
        assertEquals("李", getContactInitial("李明"))
        assertEquals("#", getContactInitial("+1 (416) 555-0100"))
        assertEquals("#", getContactInitial(null))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun initialUsesThemeInkAndCircle() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val avatar = getContactPhotoBitmap(context, "Melo", 96)
        val pixels = IntArray(96 * 96)
        avatar.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        org.junit.Assert.assertTrue(context.getColor(R.color.primary) in pixels)
        org.junit.Assert.assertTrue(context.getColor(R.color.message_outgoing) in pixels)
        assertEquals(0, avatar.getPixel(0, 0))
    }
}
