package net.kourlas.voipms_sms.rich

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.matrix.rustcomponents.sdk.AttachmentKind
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineEventContent
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

private const val MAX_UPLOAD = 16 * 1024 * 1024
private const val MAX_DOWNLOAD = 64 * 1024 * 1024L

internal fun readMatrixUpload(c: Context, uri: Uri): Pair<UploadParameters, AttachmentKind> {
    require(uri.scheme == "content")
    val resolver = c.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }?.substringAfterLast('/')?.substringAfterLast('\\')?.filterNot { it.isISOControl() }
        ?.take(128)?.takeIf { it.isNotBlank() } ?: "attachment"
    val bytes = requireNotNull(resolver.openInputStream(uri)).use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(output.size() + count <= MAX_UPLOAD) { "Maximum attachment size is 16 MiB." }
            output.write(chunk, 0, count)
        }
        output.toByteArray()
    }
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val size = bytes.size.toULong()
    val kind = when (mime.substringBefore('/')) {
        "image" -> AttachmentKind.Image(ImageInfo(null, null, mime, size, null, null, null, null), null)
        "video" -> AttachmentKind.Video(VideoInfo(null, null, null, mime, size, null, null, null), null)
        "audio" -> AttachmentKind.Audio(AudioInfo(null, size, mime))
        else -> AttachmentKind.File(FileInfo(mime, size, null, null))
    }
    return UploadParameters(UploadSource.Data(bytes, name), null, null, null, null) to kind
}

private fun MessageType.download(): Pair<MediaSource, ULong?> = when (this) {
    is MessageType.Image -> content.source to content.info?.size
    is MessageType.Audio -> content.source to content.info?.size
    is MessageType.Video -> content.source to content.info?.size
    is MessageType.File -> content.source to content.info?.size
    else -> error("No attachment in this message.")
}

internal suspend fun openMatrixMedia(c: Context, session: MatrixSession, room: Room, eventId: String,
                                     checkRoom: suspend () -> Unit): Uri {
    val dir = File(c.cacheDir, "matrix_shared/" + session.generation)
    check(dir.isDirectory || dir.mkdirs())
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
    val remote = room.loadOrFetchEvent(eventId)
    return remote.use {
        val event = it.content()
        try {
            val msg = ((event as? TimelineEventContent.MessageLike)?.content as? MessageLikeEventContent.RoomMessage)
                ?: error("This event contains no media.")
            val (source, size) = msg.messageType.download()
            require(size == null || size <= MAX_DOWNLOAD.toULong()) { "This attachment exceeds 64 MiB." }
            val mime = msg.messageType.mimeType() ?: "application/octet-stream"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { x -> x.matches(Regex("[a-zA-Z0-9]{1,12}")) } ?: "bin"
            val file = File(dir, UUID.randomUUID().toString() + "." + ext)
            // The SDK authenticates the MXC request and verifies/decrypts an encrypted Matrix attachment.
            session.client.getMediaFile(source, null, mime, false, null).use { downloaded ->
                require(File(downloaded.path()).length() <= MAX_DOWNLOAD)
                checkRoom()
                check(downloaded.persist(file.path))
            }
            FileProvider.getUriForFile(c, c.packageName + ".message_media", file)
        } finally { event.destroy() }
    }
}
