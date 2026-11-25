package net.kourlas.voipms_sms.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException

class DocumentOutputTest {
    @Test fun writablePipeVerifiesBothSingleAndBufferedWritesAfterClose() {
        var closed = false
        val saved = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        assertTrue(DocumentOutput.write(
            { mode -> assertEquals("wt", mode); saved },
            { assertTrue(closed); saved.toByteArray().inputStream() },
            { it.write(1); it.write(byteArrayOf(2, 3)) }, {}, {}
        ))
    }

    @Test fun rejectsShortExtendedAndCorruptSameLengthDestinations() {
        listOf(byteArrayOf(1), byteArrayOf(1, 2, 3), byteArrayOf(1, 9)).forEach { received ->
            assertThrows(DocumentOutput.Incomplete::class.java) {
                DocumentOutput.write({ ByteArrayOutputStream() }, { received.inputStream() },
                    { it.write(byteArrayOf(1, 2)) }, {}, {})
            }
        }
    }

    @Test fun writeOnlyAndOlderProviderModesAreDistinguished() {
        val modes = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        assertFalse(DocumentOutput.write({ output }, { throw SecurityException() }, { it.write(0) }, {}, {}))
        assertTrue(DocumentOutput.write({ mode ->
            modes.add(mode)
            if (mode == "wt") throw FileNotFoundException()
            ByteArrayOutputStream()
        }, { byteArrayOf(1).inputStream() }, { it.write(1) }, {}, {}))
        assertEquals(listOf("wt", "w"), modes)
        assertThrows(DocumentOutput.Unavailable::class.java) {
            DocumentOutput.write({ mode ->
                if (mode == "wt") throw IllegalArgumentException()
                ByteArrayOutputStream()
            }, { null }, {}, {}, {})
        }
        assertThrows(DocumentOutput.Unavailable::class.java) {
            DocumentOutput.write({ null }, { null }, {}, {}, {})
        }
    }

    @Test fun failedCloseAndCancelledWritesNeverStartReadback() {
        val destination = object : ByteArrayOutputStream() {
            override fun close() { throw IOException() }
        }
        assertThrows(IOException::class.java) {
            DocumentOutput.write({ destination }, { error("readback") }, { it.write(1) }, {}, {})
        }
        assertThrows(CancellationException::class.java) {
            DocumentOutput.write({ error("open") }, { error("readback") }, {}, {}, { throw CancellationException() })
        }
    }
}
