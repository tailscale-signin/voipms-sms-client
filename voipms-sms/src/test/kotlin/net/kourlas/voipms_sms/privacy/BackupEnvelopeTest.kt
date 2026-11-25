package net.kourlas.voipms_sms.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

class BackupEnvelopeTest {
    @Test fun preparedWriterProducesAReadableArchiveAndCanOnlyWriteOnce() {
        val data = byteArrayOf(50, 60, 70)
        val password = "wire format regression".toCharArray()
        val writer = BackupEnvelope.prepare(password)
        val sealed = ByteArrayOutputStream()
        writer.write(data.inputStream(), sealed)
        val read = ByteArrayOutputStream()
        BackupEnvelope.read(sealed.toByteArray().inputStream(), read, password)
        assertArrayEquals(data, read.toByteArray())
        assertThrows(IllegalStateException::class.java) {
            writer.write(data.inputStream(), ByteArrayOutputStream())
        }
    }

    @Test fun cancellationAfterDerivationWritesZeroBytes() {
        val writer = BackupEnvelope.prepare("cancel check".toCharArray())
        val target = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            writer.write(byteArrayOf(1).inputStream(), target) { throw CancellationException() }
        }
        assertEquals(0, target.size())
    }
}
