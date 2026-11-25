package net.kourlas.voipms_sms.media

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import net.kourlas.voipms_sms.utils.HttpClientManager
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Only the verified English language model is downloaded; images stay on the device. */
internal object PhotoOcr {
    private const val SHA = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    private const val URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata"
    private fun root(context: Context) = File(context.noBackupFilesDir, "ocr")
    private fun model(context: Context) = File(root(context), "tessdata/eng.traineddata")
    fun installed(context: Context) = model(context).isFile

    @Synchronized
    private fun prepare(context: Context) {
        val target = model(context)
        if (target.isFile) return
        target.parentFile?.mkdirs()
        val bytes = HttpClientManager.getInstance().client.newCall(Request.Builder().url(URL).build())
            .execute().use {
                if (!it.isSuccessful) throw IOException("OCR model download failed (HTTP ${it.code})")
                with(MmsMedia) { it.body.byteStream().readBounded(5 * 1024 * 1024) }
            }
        if (MessageDigest.getInstance("SHA-256").digest(bytes).toHexString() != SHA) {
            throw IOException("The downloaded OCR model could not be verified")
        }
        val temp = File.createTempFile("english-", ".part", target.parentFile)
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) throw IOException("OCR model could not be installed")
        } finally { temp.delete() }
    }
    fun read(context: Context, bitmap: Bitmap): String {
        prepare(context)
        val engine = TessBaseAPI()
        try {
            check(engine.init(root(context).path, "eng"))
            engine.setImage(bitmap)
            return engine.getUTF8Text().trim()
        } finally { engine.recycle() }
    }
}
