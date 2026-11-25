package net.kourlas.voipms_sms.privacy

import android.content.Context
import android.net.Uri
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.database.entities.Sms
import net.kourlas.voipms_sms.sms.ConversationId
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object PortableBackup {
    private val thread = Regex("threads/([0-9]{1,20})_([0-9]{1,20})")
    private val media = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|gif|png|mp3|wav|mid|mp4|3gp)")
    private const val MAX_RECORD = 1024 * 1024
    data class Preview(val zip: File, val counts: Map<ConversationId, Int>)

    private fun staging(context: Context) = File(context.cacheDir, "portable-messages-stage").apply {
        check(isDirectory || mkdirs())
    }

    /** Clean abandoned plaintext staging files before this process can start a new export or restore. */
    fun clearInterruptedWork(context: Context) {
        File(context.cacheDir, "portable-messages-stage").listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun records(input: InputStream, id: ConversationId, visit: (Sms) -> Unit) {
        val data = DataInputStream(input.buffered())
        while (true) {
            val length = data.readInt()
            if (length == 0) break
            require(length in 1..MAX_RECORD)
            val o = JSONObject(ByteArray(length).also(data::readFully).toString(Charsets.UTF_8))
            fun bit(k: String) = o.getLong(k).also { require(it == 0L || it == 1L) }
            val attachments = o.getJSONArray("media")
            require(attachments.length() in 0..128)
            val refs = (0 until attachments.length()).map { attachments.getString(it) }
            refs.forEach { if (it.startsWith("mms-local:")) require(media.matches(it.removePrefix("mms-local:"))) }
            val row = Sms(voipId = if (o.isNull("provider")) null else o.getLong("provider"),
                date = o.getLong("time"), incoming = bit("in"), did = id.did, contact = id.contact,
                text = o.getString("text"), unread = bit("unread"), delivered = bit("sent"),
                media1 = refs.getOrNull(0).orEmpty(), media2 = refs.getOrNull(1).orEmpty(), media3 = refs.getOrNull(2).orEmpty(),
                mediaJson = net.kourlas.voipms_sms.media.AttachmentList.encode(refs))
            visit(row)
        }
        require(data.read() == -1)
    }

    fun attachments(row: Sms) = net.kourlas.voipms_sms.media.AttachmentList.decode(row.mediaJson)
        .ifEmpty { listOf(row.media1, row.media2, row.media3).filter(String::isNotBlank) }

    fun rows(zip: ZipFile, id: ConversationId, visit: (Sms) -> Unit) {
        zip.getInputStream(requireNotNull(zip.getEntry("threads/" + id.getId()))).use { records(it, id, visit) }
    }

    suspend fun export(context: Context, uri: Uri, password: CharArray) {
        val temp = File.createTempFile("portable-", ".zip", staging(context))
        try {
            val files = mutableSetOf<String>()
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("version"))
                zip.write('1'.code)
                zip.closeEntry()
                Database.getInstance(context).readPortableThreads { id, rows ->
                    zip.putNextEntry(ZipEntry("threads/" + id.getId()))
                    val data = DataOutputStream(zip)
                    for (r in rows) {
                        val refs = attachments(r)
                        refs.filter { it.startsWith("mms-local:") }.forEach { ref ->
                            val name = ref.removePrefix("mms-local:")
                            require(media.matches(name))
                            files.add(name)
                        }
                        val bytes = JSONObject().put("provider", r.voipId ?: JSONObject.NULL).put("time", r.date)
                            .put("in", r.incoming).put("text", r.text).put("unread", r.unread)
                            .put("sent", r.delivered).put("media", org.json.JSONArray(refs)).toString().toByteArray()
                        require(bytes.size in 1..MAX_RECORD)
                        data.writeInt(bytes.size)
                        data.write(bytes)
                    }
                    data.writeInt(0)
                    zip.closeEntry()
                }
                for (name in files) {
                    zip.putNextEntry(ZipEntry("media/$name"))
                    File(context.filesDir, "mms_outgoing/$name").inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            require(temp.length() <= BackupEnvelope.MAX_BYTES)
            ZipFile(temp).use { zip ->
                require(zip.size() <= 100_000 && zip.entries().asSequence().sumOf { it.size } <= BackupEnvelope.MAX_BYTES)
            }
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { dest ->
                temp.inputStream().buffered().use { BackupEnvelope.write(it, dest, password) }
            }
        } finally { temp.delete() }
    }

    fun preview(context: Context, uri: Uri, password: CharArray): Preview {
        val temp = File.createTempFile("restore-", ".zip", staging(context))
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).buffered().use { source ->
                temp.outputStream().buffered().use { BackupEnvelope.read(source, it, password) }
            }
            val counts = linkedMapOf<ConversationId, Int>()
            val referenced = mutableSetOf<String>()
            ZipFile(temp).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size in 1..100_000 && entries.map { it.name }.distinct().size == entries.size)
                var total = 0L
                for (e in entries) {
                    require(e.size in 0..BackupEnvelope.MAX_BYTES)
                    total += e.size
                    require(total <= BackupEnvelope.MAX_BYTES)
                    val match = thread.matchEntire(e.name)
                    when {
                        e.name == "version" -> require(e.size == 1L && zip.getInputStream(e).use { it.read() } == '1'.code)
                        match != null -> {
                            val id = ConversationId(match.groupValues[1], match.groupValues[2])
                            var count = 0
                            rows(zip, id) { row ->
                                count++
                                attachments(row).filter { it.startsWith("mms-local:") }
                                    .mapTo(referenced) { "media/" + it.removePrefix("mms-local:") }
                            }
                            counts[id] = count
                        }
                        e.name.startsWith("media/") && media.matches(e.name.removePrefix("media/")) -> {
                            // Validate every byte before offering restore, including unselected threads.
                            val crc = java.util.zip.CRC32()
                            var actual = 0L
                            zip.getInputStream(e).use { input ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    actual += n
                                    require(actual <= e.size)
                                    crc.update(buf, 0, n)
                                }
                            }
                            require(actual == e.size && crc.value == e.crc)
                        }
                        else -> error("Unsupported backup entry")
                    }
                }
                require(zip.getEntry("version") != null && referenced.all { zip.getEntry(it) != null })
            }
            return Preview(temp, counts)
        } catch (e: Throwable) { temp.delete(); throw e }
    }

    fun installMedia(context: Context, zip: ZipFile, name: String): File? {
        require(media.matches(name))
        val dir = File(context.filesDir, "mms_outgoing")
        require(dir.isDirectory || dir.mkdirs())
        val destination = File(dir, name)
        val entry = requireNotNull(zip.getEntry("media/$name"))
        if (destination.exists()) {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            fun digest(input: InputStream): ByteArray {
                input.use { val buf = ByteArray(16384); while (true) { val n = it.read(buf); if (n < 0) break; hash.update(buf, 0, n) } }
                return hash.digest()
            }
            require(digest(destination.inputStream()).contentEquals(digest(zip.getInputStream(entry))))
            return null
        }
        val tmp = File.createTempFile("restore-", ".part", dir)
        try {
            zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(destination))
        } finally { tmp.delete() }
        return destination
    }
}
