package net.kourlas.voipms_sms.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.utils.HttpClientManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object MmsMedia {
    const val MAX_FILES = 3
    // The POST/base64 API documents a smaller limit than the portal.
    const val MAX_BYTES = 1_200_000
    const val MAX_CAPTION = 2048
    private const val PREFIX = "mms-local:"
    private val filename = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(jpg|gif|png|mp3|wav|mid|mp4|3gp)")
    private val types = mapOf("jpg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "mid" to "audio/midi",
        "mp4" to "video/mp4", "3gp" to "video/3gpp")
    fun mime(file: File): String = types[file.extension] ?: "application/octet-stream"
    fun isLocal(ref: String) = ref.startsWith(PREFIX) && filename.matches(ref.removePrefix(PREFIX))
    fun localFile(context: Context, ref: String): File {
        require(isLocal(ref))
        return File(File(context.filesDir, "mms_outgoing"), ref.removePrefix(PREFIX))
    }
    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".message_media", file)
    private fun create(context: Context, ext: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val ref = PREFIX + UUID.randomUUID() + "." + ext
        val dest = localFile(context, ref)
        dest.parentFile?.mkdirs()
        try { dest.writeBytes(bytes) } catch (failure: IOException) { dest.delete(); throw failure }
        return ref
    }

    fun import(context: Context, uri: Uri): String {
        require(uri.scheme == "content") { context.getString(R.string.mms_unsupported) }
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBounded(24 * 1024 * 1024) }
            ?: throw IOException(context.getString(R.string.mms_unavailable))
        val ext = detect(bytes) ?: throw IOException(context.getString(R.string.mms_unsupported))
        if (ext == "jpg" || ext == "png") {
            return savePhoto(context, photo(bytes))
        }
        require(bytes.size <= MAX_BYTES) { context.getString(R.string.mms_too_large) }
        return create(context, ext, bytes)
    }

    internal fun detect(bytes: ByteArray): String? {
        fun begins(offset: Int, signature: String) = bytes.size >= offset + signature.length &&
            signature.indices.all { bytes[offset + it].toInt() and 255 == signature[it].code }
        return when {
            begins(0, "\u00ff\u00d8\u00ff") -> "jpg"
            begins(0, "\u0089PNG\r\n\u001a\n") -> "png"
            begins(0, "GIF87a") || begins(0, "GIF89a") -> "gif"
            begins(0, "RIFF") && begins(8, "WAVE") -> "wav"
            begins(0, "MThd") -> "mid"
            begins(0, "ID3") || bytes.size >= 2 && bytes[0] == (-1).toByte() &&
                (bytes[1].toInt() and 0xe0) == 0xe0 -> "mp3"
            begins(4, "ftyp") && begins(8, "3g") -> "3gp"
            begins(4, "ftyp") && listOf("isom", "mp41", "mp42", "avc1", "iso2", "M4V ").any { begins(8, it) } -> "mp4"
            else -> null
        }
    }

    internal fun InputStream.readBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (out.size() + count > limit) throw IOException("Attachment exceeds the maximum size")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun photo(bytes: ByteArray): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        require(opts.outWidth > 0 && opts.outHeight > 0)
        var sample = 1
        while (maxOf(opts.outHeight, opts.outWidth) / sample > 3072) sample *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sample
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts))
        val orientation = runCatching { ExifInterface(bytes.inputStream())
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val transform = Matrix()
        when (orientation) {
            2 -> transform.setScale(-1f, 1f)
            3 -> transform.setRotate(180f)
            4 -> transform.setScale(1f, -1f)
            5 -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
            6 -> transform.setRotate(90f)
            7 -> { transform.setRotate(270f); transform.postScale(-1f, 1f) }
            8 -> transform.setRotate(270f)
        }
        return if (transform.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
    }

    fun savePhoto(context: Context, bitmap: Bitmap): String {
        var photo = bitmap
        while (true) {
            for (quality in listOf(91, 82, 70)) {
                val out = ByteArrayOutputStream()
                check(photo.compress(Bitmap.CompressFormat.JPEG, quality, out))
                if (out.size() <= MAX_BYTES) return create(context, "jpg", out.toByteArray())
            }
            photo = photo.scale((photo.width * 0.75).toInt().coerceAtLeast(1),
                (photo.height * 0.75).toInt().coerceAtLeast(1), true)
        }
    }

    fun outgoingFields(context: Context, refs: List<String>): Map<String, String> {
        require(refs.size <= MAX_FILES)
        return refs.mapIndexed { index, ref ->
            val file = localFile(context, ref)
            require(file.length() in 1L..MAX_BYTES.toLong()) { context.getString(R.string.mms_too_large) }
            val bytes = file.inputStream().use { it.readBounded(MAX_BYTES) }
            "media" + (index + 1) to "data:" + mime(file) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.toMap()
    }

    const val MAX_RECEIVED_BYTES = 12 * 1024 * 1024
    val receivedFilename = Regex("[0-9a-f]{64}\\.(?:jpg|gif|png|mp3|wav|mid|mp4|3gp)")

    private fun allowedUrl(ref: String) = ref.toHttpUrlOrNull()?.takeIf {
        it.isHttps && (it.host == "voip.ms" || it.host.endsWith(".voip.ms")) &&
            it.username.isEmpty() && it.password.isEmpty() && it.port == 443
    }

    fun receivedHash(ref: String): String? = allowedUrl(ref)?.let {
        MessageDigest.getInstance("SHA-256").digest(ref.toByteArray()).toHexString()
    }

    /** Check explicitly restored copies first, followed by the ordinary, evictable preview cache. */
    fun knownReceived(context: Context, ref: String): File? {
        val hash = receivedHash(ref) ?: return null
        for (dir in listOf(File(context.filesDir, "mms_received"), File(context.cacheDir, "mms_preview"))) {
            for (ext in types.keys) {
                val file = File(dir, "$hash.$ext")
                if (file.isFile && file.length() in 1L..MAX_RECEIVED_BYTES.toLong()) return file
            }
        }
        return null
    }

    private fun receivedCall(context: Context, ref: String): okhttp3.Call {
        val url = allowedUrl(ref) ?: throw IOException(context.getString(R.string.mms_unavailable))
        val client = HttpClientManager.getInstance().client.newBuilder().followRedirects(false)
            .callTimeout(25, TimeUnit.SECONDS).build()
        return client.newCall(Request.Builder().url(url).get().build())
    }

    private fun saveReceivedResponse(context: Context, ref: String, response: okhttp3.Response): File = response.use {
        if (!it.isSuccessful) throw IOException("HTTP " + it.code)
        if (it.body.contentLength() > MAX_RECEIVED_BYTES) throw IOException(context.getString(R.string.mms_too_large))
        val data = it.body.byteStream().readBounded(MAX_RECEIVED_BYTES)
        val ext = detect(data) ?: throw IOException(context.getString(R.string.mms_unsupported))
        val dir = File(context.cacheDir, "mms_preview").apply { mkdirs() }
        val dest = File(dir, "${requireNotNull(receivedHash(ref))}.$ext")
        val temp = File.createTempFile("incoming", ".part", dir)
        try {
            temp.writeBytes(data)
            if (!temp.renameTo(dest) && !dest.exists()) throw IOException(context.getString(R.string.mms_unavailable))
        } finally { temp.delete() }
        dest
    }

    /** The reader explicitly opened media, or the user opted into downloading media for this backup. */
    fun previewFile(context: Context, ref: String): File {
        if (isLocal(ref)) return localFile(context, ref).also { require(it.isFile) }
        return knownReceived(context, ref) ?: saveReceivedResponse(context, ref, receivedCall(context, ref).execute())
    }

    suspend fun forBackup(context: Context, ref: String, allowNetwork: Boolean): File? {
        knownReceived(context, ref)?.let { return it }
        if (!allowNetwork || allowedUrl(ref) == null) return null
        return suspendCancellableCoroutine { wait ->
            val call = receivedCall(context, ref)
            wait.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) { wait.resumeWithException(e) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try { wait.resume(saveReceivedResponse(context, ref, response)) }
                    catch (e: Exception) { wait.resumeWithException(e) }
                }
            })
        }
    }
}
