package net.kourlas.voipms_sms.tools

import android.icu.util.ULocale
import android.os.Build
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationSpec
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity

internal object LocalTranslator {
    fun run(host: AppCompatActivity, text: String, from: String, to: String, result: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 31) result(null) else translate(host, text, from, to, result)
    }
    @RequiresApi(31)
    private fun translate(host: AppCompatActivity, text: String, from: String, to: String, result: (String?) -> Unit) {
        val manager = host.getSystemService(TranslationManager::class.java) ?: return result(null)
        fun spec(tag: String) = TranslationSpec(ULocale.forLanguageTag(tag), TranslationSpec.DATA_FORMAT_TEXT)
        val context = TranslationContext.Builder(spec(from), spec(to)).build()
        try {
            manager.createOnDeviceTranslator(context, host.mainExecutor) { translator ->
                if (translator == null) { result(null); return@createOnDeviceTranslator }
                val request = TranslationRequest.Builder().setTranslationRequestValues(listOf(TranslationRequestValue.forText(text))).build()
                try {
                    translator.translate(request, null, host.mainExecutor) {
                        try { result(it.translationResponseValues[0]?.text?.toString()) } finally { translator.destroy() }
                    }
                } catch (_: RuntimeException) { translator.destroy(); result(null) }
            }
        } catch (_: RuntimeException) { result(null) }
    }
}
