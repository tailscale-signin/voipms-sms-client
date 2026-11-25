package net.kourlas.voipms_sms.sms

import org.junit.Assert.*
import org.junit.Test

class InboxRulesTest {
    @Test fun extractsOnlyTheCodeAndKeepsLeadingZerosAndCase() {
        val cases = mapOf(
            "Your security code is 001379. It expires in 5 minutes." to "001379",
            "092 845 is your Acme verification code." to "092845",
            "Your PIN: 01aB9C" to "01aB9C",
            "Votre code de vérification est 0007." to "0007",
            "Code: 123-456" to "123456",
            "G-001357 is your Google verification code." to "001357",
            "Enter 000071 to verify your phone number." to "000071",
            "Your recovery code: P3G4-8AbC" to "P3G4-8AbC",
            "000004" to "000004"
        )
        for ((body, code) in cases) assertEquals(body, code, VerificationCode.extract(body))
        for (body in listOf("Order 004277 arrives tomorrow.", "Call 416-555-0199 about your PIN",
            "ZIP code 90210", "Your code is 2026-11-25", "code: 1234 and PIN: 5678",
            "Your code is 416 555 0199", "Track it at https://example.com/code/123456")) {
            assertNull(body, VerificationCode.extract(body))
        }
    }

    @Test fun routesUnsolicitedLuresAndKeepsOrdinaryThreads() {
        val lure = "Unpaid tolls: pay today at https://example.invalid"
        assertTrue(InboxRules.suspectedJunk(lure, false, false))
        assertTrue(InboxRules.suspectedJunk("You have won! Claim your prize at https://example.invalid", false, false))
        assertFalse(InboxRules.suspectedJunk(lure, true, false))
        assertFalse(InboxRules.suspectedJunk(lure, false, true))
        assertFalse(InboxRules.suspectedJunk("Your code is 004277. Do not share it.", false, false))
        assertFalse(InboxRules.suspectedJunk("Meet me here: https://example.invalid", false, false))
        assertFalse(InboxRules.suspectedJunk(null, false, false))
    }
}
