package net.kourlas.voipms_sms.notifications

import com.squareup.moshi.Moshi
import net.kourlas.voipms_sms.notifications.workers.ProviderDid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushRegistrationTest {
    private val relay = "https://relay.example/?did={TO}"
    private val empty = ProviderDid("4165550112", "1", "0", "")

    @Test
    fun customCallbackIsPreservedEvenWhenDisabled() {
        for (enabled in listOf("0", "1")) {
            assertEquals(PushRegistration.CUSTOM_CALLBACK,
                empty.copy(callbackEnabled = enabled, callback = "https://other.example/").registrationBlock(relay))
        }
    }

    @Test
    fun missingProviderFieldsDoNotAuthorizeAWrite() {
        val adapter = Moshi.Builder().build().adapter(ProviderDid::class.java)
        val did = adapter.fromJson("""{"did":"4165550112","sms_enabled":"1","sms_url_callback_enabled":"0"}""")!!
        assertEquals(PushRegistration.UNKNOWN_SETTINGS, did.registrationBlock(relay))
        assertEquals(PushRegistration.UNKNOWN_SETTINGS, empty.copy(callbackEnabled = null).registrationBlock(relay))
        assertEquals(PushRegistration.SMS_DISABLED, empty.copy(smsEnabled = "0").registrationBlock(relay))
    }

    @Test
    fun emptyOrMatchingCallbackCanBeRegistered() {
        assertNull(empty.registrationBlock(relay))
        assertNull(empty.copy(callback = null).registrationBlock(relay))
        assertNull(empty.copy(callback = relay, callbackEnabled = "1").registrationBlock(relay))
        assertEquals(PushRegistration.UNKNOWN_SETTINGS, empty.copy(callbackEnabled = "1").registrationBlock(relay))
    }

    @Test
    fun relayAndDeviceUseTheSameNorthAmericanTopic() {
        for (did in listOf("4165550112", "14165550112", "+14165550112")) {
            assertEquals("did-4165550112", pushTopicForDid(did))
        }
        assertNull(pushTopicForDid("+442071234567"))
        assertNull(pushTopicForDid("41655a0112"))
    }
}
