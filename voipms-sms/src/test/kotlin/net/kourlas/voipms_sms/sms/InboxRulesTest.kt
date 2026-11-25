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
            "Votre NIP est le 004281." to "004281",
            "Seu código de verificação é 012345." to "012345",
            "Your one-time password (OTP) is 018963." to "018963",
            "Sign in to your account.\n@example.com #00a729" to "00a729",
            "Your code is 012345.\n@bank.example #012345 @checkout.example" to "012345",
            "000004" to "000004"
        )
        for ((body, code) in cases) assertEquals(body, code, VerificationCode.extract(body))
        for (body in listOf("Order 004277 arrives tomorrow.", "Call 416-555-0199 about your PIN",
            "ZIP code 90210", "Your code is 2026-11-25", "code: 1234 and PIN: 5678",
            "Your code is 416 555 0199", "Track it at https://example.com/code/123456",
            "Your code is 000123.\n@example.com #000456", "Mail person@example.com #003400",
            "@example.com #123456\nforwarded text", "@example.com #PASSWORD")) {
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
