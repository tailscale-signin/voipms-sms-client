package net.kourlas.voipms_sms.privacy

import net.kourlas.voipms_sms.sms.InboxRules
import net.kourlas.voipms_sms.sms.VerificationCode
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PrivacyRulesTest {
    @Test fun vaultRequiresDeliberateOverscrollAndCanBeToggledTwice() {
        val pull = PrivateInboxGesture(150f)
        var open = false
        fun move(y: Float) { if (pull.move(2f, y)) open = !open }
        pull.start(0f, 0f, false)
        move(200f)
        assertFalse(open)
        pull.start(0f, 0f, true)
        move(80f)
        assertFalse(open)
        move(185f)
        move(240f)
        assertTrue(open)
        pull.start(0f, 0f, true)
        move(180f)
        assertFalse(open)
        pull.start(0f, 0f, true)
        assertFalse(pull.move(95f, 12f))
        move(200f)
        assertFalse(open)
    }

    @Test fun protectsOrdinaryNumbersAndShowsRulePrecedence() {
        assertTrue(VerificationCode.eligibleForRetention("Your security code is 005199."))
        assertFalse(VerificationCode.eligibleForRetention("514438"))
        assertFalse(VerificationCode.eligibleForRetention("Invoice 123456: your balance is 482.50"))
        assertNotNull(InboxRules.reason("Collect the package", false, false, setOf("COLLECT")))
        assertNull(InboxRules.reason("Collect the package", false, true, setOf("collect")))
        assertNull(InboxRules.reason("Collect the package", true, false, setOf("collect")))
    }

    @Test fun portableEnvelopeAuthenticatesPasswordAndFinalChunk() {
        val data = ByteArray(75000) { (it % 233).toByte() }
        val phrase = "my long passphrase".toCharArray()
        val sealed = ByteArrayOutputStream().also { BackupEnvelope.write(data.inputStream(), it, phrase) }.toByteArray()
        fun decrypt(cipher: ByteArray, password: CharArray) = ByteArrayOutputStream().also {
            BackupEnvelope.read(cipher.inputStream(), it, password)
        }.toByteArray()
        assertArrayEquals(data, decrypt(sealed, phrase))
        assertTrue(runCatching { decrypt(sealed, "different".toCharArray()) }.isFailure)
        assertTrue(runCatching { decrypt(sealed.copyOf(sealed.size - 20), phrase) }.isFailure)
    }
}
