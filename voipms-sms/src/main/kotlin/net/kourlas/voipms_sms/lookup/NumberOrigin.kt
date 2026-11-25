package net.kourlas.voipms_sms.lookup

import android.util.LruCache
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

internal object NumberOrigin {
    private val descriptions = LruCache<Pair<String, Locale>, String>(256)

    /** Run on a worker thread. The geocoder reads bundled numbering-plan metadata. */
    fun offline(raw: String, locale: Locale): String? {
        val canonical = WikidataDirectory.number(raw) ?: return null
        val key = canonical to locale
        descriptions.get(key)?.let { return it.ifEmpty { null } }
        val phones = PhoneNumberUtil.getInstance()
        val area = try {
            val phone = phones.parse(canonical, null)
            when {
                !phones.isValidNumber(phone) -> ""
                phones.isNumberGeographical(phone) ->
                    PhoneNumberOfflineGeocoder.getInstance().getDescriptionForNumber(phone, locale)
                phones.getNumberType(phone) == PhoneNumberUtil.PhoneNumberType.MOBILE -> {
                    val region = phones.getRegionCodeForNumber(phone)
                    if (region == null || region.length != 2 || region == "ZZ") ""
                    else Locale.Builder().setRegion(region).build().getDisplayCountry(locale)
                }
                else -> ""
            }
        } catch (_: NumberParseException) {
            ""
        }
        descriptions.put(key, area)
        return area.ifBlank { null }
    }
}
