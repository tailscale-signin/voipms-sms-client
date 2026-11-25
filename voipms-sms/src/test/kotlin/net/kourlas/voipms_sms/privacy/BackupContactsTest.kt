package net.kourlas.voipms_sms.privacy

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class BackupContactsTest {
    @Test fun rejectsDuplicateNumbersEvenInAnAuthenticatedContactEntry() {
        val password = "contact section passphrase".toCharArray()
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { out ->
            out.writeInt(2)
            for (name in listOf("First", "Second")) {
                for (value in listOf("4165550100", name)) {
                    val bytes = value.toByteArray()
                    out.writeShort(bytes.size)
                    out.write(bytes)
                }
            }
        }
        val sealed = ByteArrayOutputStream().apply {
            BackupEnvelope.write(plain.toByteArray().inputStream(), this, password)
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { BackupContacts.read(sealed.inputStream(), password) }
    }

    @Test fun rejectsNonCanonicalNumbersAndOverlongNamesBeforeCreatingAnEntry() {
        val password = "contact section passphrase".toCharArray()
        for (contacts in listOf(mapOf("+14165550100" to "Name"), mapOf("4165550100" to "x".repeat(121)),
            mapOf("4165550100" to "unexpected\nline"))) {
            assertThrows(IllegalArgumentException::class.java) { BackupContacts.write(ByteArrayOutputStream(), contacts, password) {} }
        }
    }
}
