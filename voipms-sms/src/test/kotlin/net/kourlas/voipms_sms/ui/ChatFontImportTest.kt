package net.kourlas.voipms_sms.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatFontImportTest {
    @Test
    fun corruptReplacementLeavesPreviouslyImportedFontIntact() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val installed = File(app.noBackupFilesDir, "chat-font")
        val source = File(app.cacheDir, "picked-font.ttf")
        val valid = app.resources.openRawResource(R.font.proxima_nova_regular).use { it.readBytes() }
        source.writeBytes(valid)
        ChatFont.importFile(app, Uri.fromFile(source))
        assertArrayEquals(valid, installed.readBytes())

        // An sfnt signature alone is insufficient for a loadable font.
        source.writeBytes(byteArrayOf(0, 1, 0, 0) + ByteArray(64))
        assertThrows(IllegalArgumentException::class.java) { ChatFont.importFile(app, Uri.fromFile(source)) }
        assertArrayEquals(valid, installed.readBytes())
        assertNull(app.noBackupFilesDir.listFiles()?.firstOrNull { it.name.startsWith("font-import-") })
    }
}
