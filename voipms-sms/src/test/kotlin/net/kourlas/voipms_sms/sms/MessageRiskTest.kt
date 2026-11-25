package net.kourlas.voipms_sms.sms

import net.kourlas.voipms_sms.sms.InboxRules.Level
import org.junit.Assert.*
import org.junit.Test

class MessageRiskTest {
    @Test fun routineCodesStayOrdinaryAndSolicitationWarnsEvenInEstablishedThreads() {
        for (text in listOf("Your verification code is 482911. Never share this code.",
            "Votre code de vérification est 482911. Ne partagez jamais votre code.",
            "Seu código de verificação é 482911. Não compartilhe seu código.",
            "Could you send me the postal code?", "Please share your discount code.")) {
            assertEquals(text, Level.ORDINARY, InboxRules.assess(text).level)
        }
        for (text in listOf("Send me the code you just received.", "Can you reply with your PIN?",
            "Envoie-moi le code que tu viens de recevoir.", "Envie o seu código de verificação.",
            "Never share your code. But text me your code.")) {
            val result = InboxRules.assess(text)
            assertEquals(text, Level.CAUTION, result.level)
            assertNotNull(result.description)
            assertFalse(result.shouldQuarantine(true, true))
        }
    }

    @Test fun detectsMultilingualCampaignsAndMisleadingDomainsWithoutContactingThem() {
        for (text in listOf("Péages impayés, payez ici https://peage.example/solde",
            "Encomenda retida, pague a taxa em https://taxa.example",
            "Unpaid tolls: pay now at https://407etr-pay.example",
            "Pay your bill at https://interac.ca@payments.example/123",
            "Vérifiez votre paiement sur https://intеrac.example/bonjour")) {
            val verdict = InboxRules.assess(text)
            assertEquals(text, Level.HIGH, verdict.level)
            assertTrue(verdict.shouldQuarantine(false, false))
            assertFalse(verdict.shouldQuarantine(true, false))
            assertFalse(verdict.shouldQuarantine(false, true))
        }
        assertEquals(Level.HIGH, InboxRules.assess("Send me the code here: https://407etr-login.example").level)
        assertEquals(Level.CAUTION, InboxRules.assess("Unpaid tolls: pay online at https://www.407etr.com").level)
        assertEquals(Level.CAUTION, InboxRules.assess("Please pay today at https://shop.example").level)
        assertEquals(Level.ORDINARY, InboxRules.assess("Your appointment is tomorrow. Book at https://clinic.example").level)
    }

    @Test fun campaignFeedbackIsRestrictedToMatchingLinksAndDoesNotRemoveContentWarnings() {
        val one = "Unpaid tolls: pay at https://payments.example/receipts/124?token=alice"
        val repeat = "Unpaid tolls: pay at https://payments.example/receipts/529?token=bob"
        val key = requireNotNull(InboxRules.campaignKey(one))
        assertEquals(64, key.length)
        assertEquals(key, InboxRules.campaignKey(repeat))
        assertNotEquals(key, InboxRules.campaignKey(repeat.replace("payments.example", "different.example")))
        assertNotEquals(InboxRules.campaignKey(one.replace("payments", "pay123")),
            InboxRules.campaignKey(one.replace("payments", "pay456")))
        assertNull(InboxRules.campaignKey("Your verification code is 482911. Open https://bank.example"))
        assertEquals(Level.HIGH, InboxRules.assess(repeat, InboxRules.Profile()).level)
        val trusted = InboxRules.Profile(corrections = mapOf(key to false))
        assertEquals(Level.CAUTION, InboxRules.assess(repeat, trusted).level)
        val flyer = "Our extraordinary sale is now open: https://shop.example/sale"
        val tagged = InboxRules.Profile(corrections = mapOf(requireNotNull(InboxRules.campaignKey(flyer)) to true))
        assertTrue(InboxRules.assess(flyer, tagged).shouldQuarantine(false, false))
        assertEquals(Level.ORDINARY, InboxRules.assess(flyer).level)
    }
}
