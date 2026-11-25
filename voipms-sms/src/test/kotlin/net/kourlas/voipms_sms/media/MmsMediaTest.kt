package net.kourlas.voipms_sms.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.database.entities.Sms
import net.kourlas.voipms_sms.sms.workers.MessageResponse
import net.kourlas.voipms_sms.sms.workers.messageDateFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MmsMediaTest {
    @Test fun inboundGalleryKeepsAllSixAttachmentsAndDeduplicatesLegacyColumns() {
        val refs = List(6) { "https://voip.ms/media/$it/image.jpeg" }
        val row = MessageResponse(id="81", date="2026-04-08 10:00:00", type="1",
            did="4165550123", contact="4165550145", media=refs, col_media1=refs[0],
            col_media2=refs[1], col_media3=refs[2])
        val incoming = row.incoming("4165550123", messageDateFormat())!!
        val sms = Sms(did=incoming.did, contact=incoming.contact, mediaJson=AttachmentList.encode(incoming.medias))
        assertEquals(refs, sms.toMessage().medias)
        assertEquals(refs.take(3), sms.copy(mediaJson="", media1=refs[0],media2=refs[1],media3=refs[2]).toMessage().medias)
    }
    @Test fun limitsAndPrivatePathsAreEnforcedBeforeSubmission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ref = "mms-local:12ad570c-a2a1-4eda-9217-1b932745ee0a.wav"
        val file = MmsMedia.localFile(context,ref)
        file.parentFile!!.mkdirs()
        file.writeBytes("RIFF0123WAVEaudio".toByteArray())
        try {
            val data = MmsMedia.outgoingFields(context,listOf(ref)).getValue("media1")
            assertTrue(data.startsWith("data:audio/wav;base64,"))
            assertArrayEquals(file.readBytes(),Base64.decode(data.substringAfter(','),Base64.NO_WRAP))
            assertThrows(IllegalArgumentException::class.java) { MmsMedia.outgoingFields(context,List(4){ ref }) }
            assertFalse(MmsMedia.isLocal("mms-local:../secrets"))
            assertFalse(MmsMedia.isLocal("file:///data/local/tmp/picture.jpg"))
            assertThrows(IOException::class.java) { with(MmsMedia) { ByteArray(33).inputStream().readBounded(32) } }
        } finally { file.delete() }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun outgoingJpegIsASeparateMetadataFreeCopyWithinBudget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val original = java.io.File.createTempFile("geo", ".jpg", context.cacheDir)
        original.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
        android.media.ExifInterface(original.path).apply {
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "43/1,39/1,0/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            saveAttributes()
        }
        val before = original.readBytes()
        val edited = PhotoEditor.render(MmsMedia.photo(before), listOf(android.graphics.RectF(30f,30f,70f,70f)),
            android.graphics.RectF(10f,10f,90f,90f))
        val ref = MmsMedia.savePhoto(context,edited)
        val file = MmsMedia.localFile(context,ref)
        try {
            assertTrue(file.length() in 1L..MmsMedia.MAX_BYTES.toLong())
            assertEquals("jpg", MmsMedia.detect(file.readBytes()))
            assertEquals(null, android.media.ExifInterface(file.path).getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE))
            val outgoing = MmsMedia.photo(file.readBytes())
            assertEquals(80, outgoing.width)
            assertTrue(Color.green(outgoing.getPixel(40,40)) < 8)
            assertTrue(Color.green(outgoing.getPixel(10,10)) > 240)
            assertArrayEquals(before, original.readBytes())
        } finally { file.delete(); original.delete() }
    }
}
