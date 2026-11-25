package net.kourlas.voipms_sms.utils

import android.app.Application
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AvatarColourTest {
    @Test fun phoneWithMinimumHashUsesAValidPaletteIndex() {
        val number = "4552251044"
        assertEquals(Int.MIN_VALUE, number.hashCode())
        assertEquals(Color.rgb(0x62, 0x00, 0xea), getMaterialDesignColour(number))
    }
}
