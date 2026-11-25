package net.kourlas.voipms_sms.tools

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

internal object LocalTranslator {
    fun run(host: AppCompatActivity, text: String, from: String, to: String, result: (String?) -> Unit) {
        host.lifecycleScope.launch {
            val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
                result(translator.translate(text).await())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { result(null) }
            finally { translator.close() }
        }
    }
}
