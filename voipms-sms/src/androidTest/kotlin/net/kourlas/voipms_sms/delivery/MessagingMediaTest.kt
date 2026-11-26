package net.kourlas.voipms_sms.delivery

import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.kourlas.voipms_sms.database.entities.Sms
import net.kourlas.voipms_sms.preferences.AccountCredentials
import net.kourlas.voipms_sms.preferences.activities.AboutPreferencesActivity
import net.kourlas.voipms_sms.privacy.CodePrivacy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MessagingMediaTest {
    @Test fun aCodeReceivedInTheBackgroundIsAvailableWhenAnotherScreenOpens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use { it.readBytes() }
        instrumentation.waitForIdleSync()
        val now = System.currentTimeMillis()
        val message = Sms(databaseId = 22, did = "4165550100", contact = "4165550142", incoming = 1,
            date = now / 1000, text = "Verification code: 001234").toMessage()
        CodePrivacy.copyReceived(context, 412L, listOf(message), 0, now)
        val screen = instrumentation.startActivitySync(Intent(context, AboutPreferencesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!screen.hasWindowFocus() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue(screen.hasWindowFocus())
            val manager = screen.getSystemService(ClipboardManager::class.java)
            assertEquals("001234", manager.primaryClip?.getItemAt(0)?.text?.toString())
            assertTrue(manager.primaryClipDescription?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
        } finally {
            instrumentation.runOnMainSync { screen.finish() }
        }
    }

    @Test fun androidDecodesAndPlaysTheRecordingAfterItsFolderAndSlotChange(): Unit = runBlocking {
        val sampleRate = 8000
        val wav = ByteBuffer.allocate(44 + sampleRate * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(sampleRate * 2)
            repeat(sampleRate) { putShort(if (it % 40 < 20) 512 else -512) }
        }.array()
        val account = AccountCredentials("fixture@example.invalid", "fixture", 1)
        val row = JSONObject().put("mailbox", "1001").put("folder", "INBOX").put("message_num", "0")
            .put("callerid", "4165550142").put("date", "2025-09-03 10:15:00").put("duration", 1)
        val event = CallHistory.voicemailItems(listOf(row), "1001", "4165550100", TimeZone.getTimeZone("UTC")).single()
        row.put("folder", "Old").put("message_num", "7")
        val bytes = CallHistory.audio(account, event) { _, method, args ->
            if (method == "getVoicemailMessages") JSONObject().put("messages", row)
            else JSONObject().put("message", JSONObject(args).put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
        }
        assertArrayEquals(wav, bytes)
        VoicemailDataSource(bytes).use { source ->
            val player = MediaPlayer()
            try {
                player.setDataSource(source)
                player.prepare()
                val completed = CountDownLatch(1)
                player.setOnCompletionListener { completed.countDown() }
                player.start()
                SystemClock.sleep(100)
                player.pause()
                assertFalse(player.isPlaying)
                player.start()
                assertTrue("Voicemail did not finish", completed.await(5, TimeUnit.SECONDS))
                player.stop()
            } finally { player.release() }
        }
    }
}
