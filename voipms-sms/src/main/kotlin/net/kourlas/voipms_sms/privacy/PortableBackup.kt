package net.kourlas.voipms_sms.privacy

import android.content.Context
import net.kourlas.voipms_sms.utils.DocumentOutput
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.kourlas.voipms_sms.diagnostics.DebugTrace
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.contacts.LocalContacts
import net.kourlas.voipms_sms.media.MmsMedia
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
    enum class ReceivedMode { LINKS, CACHED, FETCH }
    data class ExportResult(val included: Int, val unavailable: Int, val contacts: Int? = null, val verified: Boolean = true)
    data class Preview(val zip: File, val counts: Map<ConversationId, Int>, val received: Map<String, String>,
                       val contacts: Map<String, String> = emptyMap())
    data class Selection(val threads: Set<ConversationId>, val contacts: Set<String>)
    data class RestoreResult(val messages: Int, val contacts: Int, val contactsFailed: Boolean = false)

    private fun staging(context: Context) = File(context.cacheDir, "portable-messages-stage").apply {
        check(mkdirs() || isDirectory)
    }

    /** Clean abandoned plaintext staging files before this process can start a new export or restore. */
    fun clearInterruptedWork(context: Context) {
        File(context.cacheDir, "portable-messages-stage").listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun records(input: InputStream, id: ConversationId): Sequence<Sms> = sequence {
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
            yield(row)
        }
        require(data.read() == -1)
    }

    fun attachments(row: Sms) = net.kourlas.voipms_sms.media.AttachmentList.decode(row.mediaJson)
        .ifEmpty { listOf(row.media1, row.media2, row.media3).filter(String::isNotBlank) }

    fun rows(zip: ZipFile, id: ConversationId): Sequence<Sms> = sequence {
        zip.getInputStream(requireNotNull(zip.getEntry("threads/" + id.getId()))).use { yieldAll(records(it, id)) }
    }

    suspend fun export(context: Context, uri: Uri, password: CharArray, mode: ReceivedMode = ReceivedMode.LINKS,
                       includeContacts: Boolean = true, trace: DebugTrace? = null): ExportResult {
        val job = currentCoroutineContext()
        val received = mutableSetOf<String>()
        var included = 0
        var unavailable = 0
        trace?.step(DiagnosticStep.SNAPSHOT)
        val temp = File.createTempFile("portable-", ".zip", staging(context))
        try {
            val files = mutableSetOf<String>()
            if (includeContacts) trace?.step(DiagnosticStep.CONTACTS)
            val contacts = if (includeContacts) LocalContacts.snapshot(context) else null
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("version"))
                zip.write(if (contacts.isNullOrEmpty()) '2'.code else '3'.code)
                zip.closeEntry()
                if (!contacts.isNullOrEmpty()) {
                    zip.putNextEntry(ZipEntry(BackupContacts.ENTRY))
                    BackupContacts.write(zip, contacts, password) { job.ensureActive() }
                    zip.closeEntry()
                }
                trace?.step(DiagnosticStep.MESSAGES)
                var messageCount = 0L
                Database.getInstance(context).readPortableThreads { id, rows ->
                    val entry = "threads/" + id.getId()
                    require(thread.matches(entry))
                    zip.putNextEntry(ZipEntry(entry))
                    val data = DataOutputStream(zip)
                    rows.collect { r ->
                        job.ensureActive()
                        val refs = attachments(r)
                        if (r.incoming == 1L && mode != ReceivedMode.LINKS) {
                            refs.filterNot(MmsMedia::isLocal).forEach { received.add(it) }
                        }
                        refs.filter { it.startsWith("mms-local:") }.forEach { ref ->
                            val name = ref.removePrefix("mms-local:")
                            require(media.matches(name))
                            files.add(name)
                        }
                        require(refs.size <= 128)
                        val bytes = JSONObject().put("provider", r.voipId ?: JSONObject.NULL).put("time", r.date)
                            .put("in", r.incoming).put("text", r.text).put("unread", r.unread)
                            .put("sent", r.delivered).put("media", org.json.JSONArray(refs)).toString().toByteArray()
                        require(bytes.size in 1..MAX_RECORD)
                        data.writeInt(bytes.size)
                        data.write(bytes)
                        messageCount++
                    }
                    data.writeInt(0)
                    zip.closeEntry()
                }
                trace?.step(DiagnosticStep.MESSAGES, count = messageCount)
                trace?.step(DiagnosticStep.ATTACHMENTS, count = received.size.toLong() + files.size)
                var fileNumber = 0L
                fun nextFile(): Boolean {
                    fileNumber++
                    val sample = fileNumber <= 3 || fileNumber % 100 == 0L
                    if (sample) trace?.step(DiagnosticStep.FILE, count = fileNumber)
                    return sample
                }
                // Network reads happen after closing the database snapshot. Preserve the provider URL
                // on every row, even when the attachment has expired or has an unsupported format.
                var receivedBytes = 0L
                val limit = 512L * 1024 * 1024
                for (ref in received) {
                    job.ensureActive()
                    val sample = nextFile()
                    var cause: java.io.IOException? = null
                    val file = try {
                        if (receivedBytes >= limit) null else MmsMedia.forBackup(context, ref, mode == ReceivedMode.FETCH)
                    } catch (e: CancellationException) { throw e }
                    catch (e: java.io.IOException) { cause = e; null }
                    val bytes = try {
                        if (file == null || file.length() > limit - receivedBytes) null
                        else file.inputStream().use { input -> with(MmsMedia) { input.readBounded(MAX_RECEIVED_BYTES) } }
                    } catch (e: java.io.IOException) { cause = e; null }
                    if (file == null || bytes == null || MmsMedia.detect(bytes) != file.extension) {
                        unavailable++
                        if (sample) trace?.step(DiagnosticStep.UNAVAILABLE, count = fileNumber, error = cause)
                        continue
                    }
                    job.ensureActive()
                    zip.putNextEntry(ZipEntry("received/${file.name}"))
                    zip.write(bytes)
                    zip.closeEntry()
                    receivedBytes += bytes.size
                    included++
                }
                if (unavailable > 0) trace?.step(DiagnosticStep.UNAVAILABLE, count = unavailable.toLong())
                val buffer = ByteArray(64 * 1024)
                for (name in files) {
                    job.ensureActive()
                    nextFile()
                    try {
                        zip.putNextEntry(ZipEntry("media/$name"))
                        File(context.filesDir, "mms_outgoing/$name").inputStream().use { input ->
                            while (true) {
                                job.ensureActive()
                                val size = input.read(buffer)
                                if (size < 0) break
                                zip.write(buffer, 0, size)
                            }
                        }
                        zip.closeEntry()
                    } catch (e: java.io.IOException) {
                        trace?.step(DiagnosticStep.FILE, count = fileNumber, error = e)
                        throw e
                    }
                }
            }
            trace?.step(DiagnosticStep.VALIDATING)
            require(temp.length() <= BackupEnvelope.MAX_BYTES)
            ZipFile(temp).use { zip ->
                require(zip.size() <= 100_000 && zip.entries().asSequence().sumOf { it.size } <= BackupEnvelope.MAX_BYTES)
            }
            job.ensureActive()
            trace?.step(DiagnosticStep.ENCRYPTING)
            val writer = BackupEnvelope.prepare(password)
            job.ensureActive()
            trace?.step(DiagnosticStep.OPENING_STREAM)
            val verified = DocumentOutput.write(
                openOutput = { mode -> context.contentResolver.openOutputStream(uri, mode) },
                openInput = { context.contentResolver.openInputStream(uri) },
                writeContent = { dest ->
                    trace?.step(DiagnosticStep.WRITING)
                    temp.inputStream().buffered().use { source -> writer.write(source, dest) { job.ensureActive() } }
                },
                verifying = { trace?.step(DiagnosticStep.VERIFYING) },
                checkActive = { job.ensureActive() }
            )
            if (!verified) trace?.step(DiagnosticStep.UNAVAILABLE)
            return ExportResult(included, unavailable, contacts?.size, verified)
        } finally { temp.delete() }
    }

    fun preview(context: Context, uri: Uri, password: CharArray, trace: DebugTrace? = null,
                checkActive: () -> Unit = {}): Preview {
        trace?.step(DiagnosticStep.SNAPSHOT)
        val temp = File.createTempFile("restore-", ".zip", staging(context))
        try {
            trace?.step(DiagnosticStep.OPENING_STREAM)
            val input = context.contentResolver.openInputStream(uri) ?: throw DocumentOutput.Unavailable()
            input.buffered().use { source ->
                trace?.step(DiagnosticStep.DECRYPTING)
                temp.outputStream().buffered().use { BackupEnvelope.read(source, it, password, checkActive = checkActive) }
            }
            trace?.step(DiagnosticStep.VALIDATING)
            val counts = linkedMapOf<ConversationId, Int>()
            val referenced = mutableSetOf<String>()
            val receivedHashes = mutableSetOf<String>()
            val receivedEntries = mutableMapOf<String, String>()
            var contacts: Map<String, String> = emptyMap()
            ZipFile(temp).use { zip ->
                require(zip.size() in 1..100_000)
                val entries = zip.entries().asSequence()
                val names = mutableSetOf<String>()
                val version = requireNotNull(zip.getEntry("version"))
                require(version.size == 1L)
                val versionCode = zip.getInputStream(version).use { it.read() }
                require(versionCode in '1'.code..'3'.code)
                require((versionCode == '3'.code) == (zip.getEntry(BackupContacts.ENTRY) != null))
                var total = 0L
                for (e in entries) {
                    checkActive()
                    require(names.add(e.name))
                    require(e.size in 0..BackupEnvelope.MAX_BYTES)
                    total += e.size
                    require(total <= BackupEnvelope.MAX_BYTES)
                    val match = thread.matchEntire(e.name)
                    when {
                        e.name == "version" -> Unit
                        e.name == BackupContacts.ENTRY -> {
                            require(e.size in 1..BackupContacts.MAX_SEALED_BYTES.toLong())
                            contacts = zip.getInputStream(e).use { BackupContacts.read(it, password) }
                        }
                        match != null -> {
                            val id = ConversationId(match.groupValues[1], match.groupValues[2])
                            var count = 0
                            rows(zip, id).forEach { row ->
                                checkActive()
                                count++
                                if (row.incoming == 1L) attachments(row).mapNotNullTo(receivedHashes, MmsMedia::receivedHash)
                                attachments(row).filter { it.startsWith("mms-local:") }
                                    .mapTo(referenced) { "media/" + it.removePrefix("mms-local:") }
                            }
                            counts[id] = count
                        }
                        versionCode >= '2'.code && e.name.startsWith("received/") && MmsMedia.receivedFilename.matches(e.name.removePrefix("received/")) -> {
                            val name = e.name.removePrefix("received/")
                            require(e.size in 1..MmsMedia.MAX_RECEIVED_BYTES.toLong())
                            val bytes = zip.getInputStream(e).use { input -> with(MmsMedia) { input.readBounded(MAX_RECEIVED_BYTES) } }
                            val crc = java.util.zip.CRC32().apply { update(bytes) }
                            require(bytes.size.toLong() == e.size && crc.value == e.crc && MmsMedia.detect(bytes) == name.substringAfterLast('.'))
                            require(receivedEntries.put(name.substringBefore('.'), name) == null)
                        }
                        e.name.startsWith("media/") && media.matches(e.name.removePrefix("media/")) -> {
                            // Validate every byte before offering restore, including unselected threads.
                            val crc = java.util.zip.CRC32()
                            var actual = 0L
                            zip.getInputStream(e).use { input ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    checkActive()
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
                require(referenced.all { zip.getEntry(it) != null } && receivedHashes.containsAll(receivedEntries.keys))
            }
            return Preview(temp, counts, receivedEntries, contacts)
        } catch (e: Throwable) { temp.delete(); throw e }
    }

    suspend fun restore(context: Context, preview: Preview, selection: Selection, trace: DebugTrace? = null): RestoreResult {
        require(preview.counts.keys.containsAll(selection.threads) && preview.contacts.keys.containsAll(selection.contacts))
        trace?.step(DiagnosticStep.MERGING, count = selection.threads.size.toLong())
        val messages = if (selection.threads.isEmpty()) 0 else
            Database.getInstance(context).restorePortableThreads(preview, selection.threads)
        if (selection.contacts.isEmpty()) return RestoreResult(messages, 0)
        trace?.step(DiagnosticStep.CONTACTS, count = selection.contacts.size.toLong())
        val selectedNames = preview.contacts.filterKeys { it in selection.contacts }
        return try {
            RestoreResult(messages, LocalContacts.mergeMissing(context, selectedNames))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (selection.threads.isEmpty()) throw e
            // Message transactions and the encrypted address book commit independently. Both merges are idempotent.
            trace?.step(DiagnosticStep.PARTIAL, error = e)
            RestoreResult(messages, 0, contactsFailed = true)
        }
    }

    fun installMedia(context: Context, zip: ZipFile, name: String, received: Boolean = false): File? {
        require(if (received) MmsMedia.receivedFilename.matches(name) else media.matches(name))
        val dir = File(context.filesDir, if (received) "mms_received" else "mms_outgoing")
        require(dir.isDirectory || dir.mkdirs())
        val destination = File(dir, name)
        val entry = requireNotNull(zip.getEntry((if (received) "received/" else "media/") + name))
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
