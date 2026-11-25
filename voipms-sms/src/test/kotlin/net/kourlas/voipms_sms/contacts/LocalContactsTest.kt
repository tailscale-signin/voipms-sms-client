package net.kourlas.voipms_sms.contacts

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalContactsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun saveRenameRestoreAndReopenWithoutExposingNamesOrPhones() {
        val path = File(temp.root, "contacts")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val first = ContactFile(path) { key }
        assertTrue(first.snapshot().isEmpty())
        assertFalse(path.exists())
        val phone = requireNotNull(LocalContacts.number("+1 (416) 555-0111"))
        first.save(phone, "Mélanie at the studio")
        assertEquals(1, first.mergeMissing(mapOf(phone to "Old name", "12345" to "Dispatch")))
        first.save(phone, "Mélanie")
        assertEquals(mapOf(phone to "Mélanie", "12345" to "Dispatch"), ContactFile(path) { key }.snapshot())
        val bytes = path.readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(phone))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("Dispatch"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        path.writeBytes(bytes)
        assertThrows(Exception::class.java) { ContactFile(path) { key }.save(phone, "Replacement") }
        assertArrayEquals(bytes, path.readBytes())
    }

    @Test fun normalizedNumberAndNameValidation() {
        assertEquals("4165550111", LocalContacts.number("+1 (416) 555-0111"))
        assertNull(LocalContacts.number("Unknown"))
        assertTrue(LocalContacts.validateEntry("12345", "École de Montréal"))
        assertFalse(LocalContacts.validateEntry("14165550111", "Melo"))
        assertFalse(LocalContacts.validateEntry("12345", "\u202ehidden"))
        assertFalse(LocalContacts.validateEntry("12345", "  "))
    }
}
