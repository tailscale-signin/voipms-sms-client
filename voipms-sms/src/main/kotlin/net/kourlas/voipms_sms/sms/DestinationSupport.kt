package net.kourlas.voipms_sms.sms

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid

internal enum class DestinationSupport { STANDARD, SHORT_CODE, UNSUPPORTED, UNKNOWN }

internal fun destinationSupport(did: String, contact: String): DestinationSupport {
    val number = normalizeVoipMsDid(contact)
    if (!number.all { it in '0'..'9' } || number.isEmpty()) return DestinationSupport.UNSUPPORTED
    if (number.length <= 6) {
        val tollFree = normalizeVoipMsDid(did).take(3) in setOf("800", "833", "844", "855", "866", "877", "888")
        return if (tollFree) DestinationSupport.UNSUPPORTED else DestinationSupport.SHORT_CODE
    }
    if (number.length != 10) return DestinationSupport.UNSUPPORTED
    val phones = PhoneNumberUtil.getInstance()
    val region = try {
        val parsed = phones.parse("+1" + number, null)
        if (!phones.isValidNumber(parsed)) null else phones.getRegionCodeForNumber(parsed)
    } catch (_: NumberParseException) { null }
    return when (region) {
        null, "001", "ZZ" -> DestinationSupport.UNKNOWN
        "US", "CA", "PR", "VI", "GU", "AS", "MP" -> DestinationSupport.STANDARD
        else -> DestinationSupport.UNSUPPORTED
    }
}
