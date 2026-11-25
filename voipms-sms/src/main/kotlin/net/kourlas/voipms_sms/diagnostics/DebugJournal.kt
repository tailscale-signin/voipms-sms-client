package net.kourlas.voipms_sms.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.utils.DocumentOutput
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// The recorder accepts fixed event names and numeric measurements only.
internal enum class DiagnosticAction {
    APP, NAVIGATION, INBOX_SCREEN, THREAD_SCREEN, COMPOSE_SCREEN, SETTINGS_SCREEN, OTHER_SCREEN,
    INBOX_LOAD, THREAD_LOAD, PORTABLE_EXPORT, PORTABLE_RESTORE, DATABASE_EXPORT, DATABASE_IMPORT,
    SMS_SYNC, OUTBOX_SEND, MEDIA_EXPORT, THREAD_EXPORT, DEBUG_EXPORT, RECORDING,
    THREAD_CALLS, THREAD_MATRIX, THREAD_NEARBY, THREAD_DRAFT, THREAD_READ_STATE
}

internal enum class DiagnosticStep {
    STARTED, CREATED, RESUMED, PAUSED, STOPPED, DESTROYED, HOME, MESSAGES, OTHER, PRIVATE,
    LOADING, QUERYING, RENDERING, PAGING, EMPTY, SUPERSEDED, PICKER_OPENED, PICKER_RETURNED,
    AUTHORIZING, SELECTING, VALIDATING, SNAPSHOT, ATTACHMENTS, CONTACTS, ENCRYPTING, DECRYPTING,
    OPENING_STREAM, WRITING, READING, VERIFYING, MERGING, FILE, PARTIAL, UNAVAILABLE,
    QUEUED, SENDING, RETRY, HANDOFF, SKIPPED, COMPLETED, CANCELLED, FAILED, CRASHED, DROPPED
}

internal class DebugTrace internal constructor(
    private val store: JournalStore?,
    internal val action: DiagnosticAction,
    val id: String?,
    private val started: Long? = SystemClock.elapsedRealtime()
) {
    fun step(stage: DiagnosticStep, count: Long? = null, error: Throwable? = null) {
        if (store != null && id != null) store.record(action, stage, id, count, error, started?.let { SystemClock.elapsedRealtime() - it })
    }

    internal fun belongsTo(journal: JournalStore) = store === journal && journal.accepts(id)
}

internal object DebugJournal {
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SMS debug journal").apply { isDaemon = true }
    }
    @Volatile private var current: JournalStore? = null

    internal fun store(context: Context): JournalStore {
        current?.let { return it }
        val app = context.applicationContext
        return current ?: synchronized(this) {
            current ?: JournalStore(File(app.noBackupFilesDir, "debug-journal"), writer).also { current = it }
        }
    }

    fun enabled(context: Context) = store(context).enabled
    fun setEnabled(context: Context, enabled: Boolean) = store(context).setEnabled(enabled)
    fun clear(context: Context) = store(context).clear()
    fun begin(context: Context, action: DiagnosticAction): DebugTrace = store(context).begin(action)
    fun resume(context: Context, action: DiagnosticAction, id: String?): DebugTrace = store(context).resume(action, id)
    fun record(context: Context, action: DiagnosticAction, stage: DiagnosticStep, count: Long? = null, error: Throwable? = null) =
        store(context).record(action, stage, count = count, error = error)

    // Take a bounded, consistent copy before calling the document provider, which may be slow.
    fun export(context: Context, uri: Uri, trace: DebugTrace): Boolean {
        val journal = store(context)
        val checkActive = { check(trace.belongsTo(journal)) { "Recording ended" } }
        checkActive()
        trace.step(DiagnosticStep.OPENING_STREAM)
        return DocumentOutput.write(
            openOutput = { mode -> context.contentResolver.openOutputStream(uri, mode) },
            openInput = { context.contentResolver.openInputStream(uri) },
            writeContent = { stream ->
                trace.step(DiagnosticStep.WRITING)
                stream.write(journal.exportBytes(trace))
            },
            verifying = { trace.step(DiagnosticStep.VERIFYING) },
            checkActive = checkActive
        )
    }
}

/** Only the writer, export, opt-in controls and fatal-error handler do file I/O. */
internal class JournalStore(private val dir: File, private val writer: Executor) {
    private val gate = Any()
    private val disk = Any()
    private val marker = AtomicFile(File(dir, "recording"))
    private val recent = File(dir, "recent.jsonl")
    private val previous = File(dir, "previous.jsonl")
    private val process = UUID.randomUUID().toString()
    private val pending = ArrayDeque<JSONObject>()
    private var draining = false
    private var dropped = 0L
    private var epoch: UUID? = try {
        UUID.fromString(marker.openRead().bufferedReader().use { it.readLine() })
    } catch (_: Exception) { null }

    val enabled: Boolean get() = synchronized(gate) { epoch != null }

    fun setEnabled(value: Boolean) = synchronized(disk) {
        if (enabled != value) reset(value)
    }

    fun clear() = synchronized(disk) { reset(enabled) }

    // A trace UUID shares its high bits with the recording epoch. Closing or clearing a
    // session invalidates late callbacks, including a picker restored after process death.
    private fun reset(value: Boolean) {
        synchronized(gate) {
            epoch = null
            pending.clear()
            dropped = 0
        }
        marker.delete()
        for (file in listOf(recent, previous)) {
            if (file.exists() && !file.delete()) throw IOException("Could not clear debug history")
        }
        if (value) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create debug directory")
            val next = UUID.randomUUID()
            val stream = marker.startWrite()
            try {
                stream.write(next.toString().toByteArray(Charsets.US_ASCII))
                marker.finishWrite(stream)
            } catch (e: Throwable) { marker.failWrite(stream); throw e }
            synchronized(gate) { epoch = next }
            record(DiagnosticAction.RECORDING, DiagnosticStep.STARTED)
        }
    }

    fun accepts(id: String?): Boolean = synchronized(gate) { acceptsLocked(id) }
    private fun acceptsLocked(id: String?): Boolean {
        val active = epoch ?: return false
        if (id == null) return false
        val parsed = try { UUID.fromString(id) } catch (_: IllegalArgumentException) { return false }
        return parsed.toString() == id && parsed.mostSignificantBits == active.mostSignificantBits
    }

    fun begin(action: DiagnosticAction): DebugTrace {
        val id = synchronized(gate) {
            val active = epoch ?: return inactive
            UUID(active.mostSignificantBits, UUID.randomUUID().leastSignificantBits).toString()
        }
        return DebugTrace(this, action, id).also { it.step(DiagnosticStep.STARTED) }
    }
    fun resume(action: DiagnosticAction, id: String?) =
        if (accepts(id)) DebugTrace(this, action, id, started = null) else inactive

    fun record(action: DiagnosticAction, stage: DiagnosticStep, id: String? = null, count: Long? = null,
               error: Throwable? = null, elapsed: Long? = null) {
        synchronized(gate) {
            if (epoch == null || (id != null && !acceptsLocked(id))) return
            val event = event(action, stage)
            if (id != null) event.put("operation", id)
            if (count != null) event.put("count", count)
            if (elapsed != null) event.put("elapsedMs", elapsed.coerceAtLeast(0))
            if (error != null) {
                event.put("error", JSONObject().apply {
                    put("class", error.javaClass.name)
                    // Exception messages, raw stack dumps and filenames may contain user data.
                    val frames = org.json.JSONArray()
                    generateSequence(error) { it.cause }.take(4).forEach { cause ->
                        cause.stackTrace.asSequence().filter { it.className.startsWith("net.kourlas.voipms_sms.") }
                            .filter { it.className.length <= 240 && it.methodName.length <= 120 &&
                                STACK_CLASS.matches(it.className) && STACK_METHOD.matches(it.methodName) }
                            .take(8).forEach { frame -> frames.put(frame.className + "." + frame.methodName + ":" + frame.lineNumber) }
                    }
                    put("frames", frames)
                })
            }
            if (pending.size >= MAX_PENDING) { pending.removeFirst(); dropped++ }
            pending.addLast(event)
            if (!draining) {
                draining = true
                writer.execute(::drain)
            }
        }
    }

    private fun event(action: DiagnosticAction, stage: DiagnosticStep): JSONObject = JSONObject()
        .put("type", "event").put("time", Instant.ofEpochMilli(System.currentTimeMillis()).toString())
        .put("session", process).put("uptimeMs", SystemClock.elapsedRealtime())
        .put("action", action.name).put("stage", stage.name)

    // All disk operations acquire disk before gate. Ordinary event producers only acquire gate.
    private fun drain() = synchronized(disk) {
        try { flush() } catch (_: Exception) {
            // Diagnostics must never take down messaging. Report lost records on the next successful write.
        } finally {
            synchronized(gate) {
                draining = false
                if (pending.isNotEmpty()) { draining = true; writer.execute(::drain) }
            }
        }
    }

    private fun flush() {
        val batch = synchronized(gate) {
            if (epoch == null || (pending.isEmpty() && dropped == 0L)) return
            buildList {
                if (dropped > 0) add(event(DiagnosticAction.RECORDING, DiagnosticStep.DROPPED).put("count", dropped))
                dropped = 0
                while (pending.isNotEmpty()) add(pending.removeFirst())
            }
        }
        try {
            // Batch adjacent events in one write, rotating between whole JSON records.
            var length = recent.length()
            var block = StringBuilder()
            fun writeBlock() {
                if (block.isNotEmpty()) recent.appendText(block.toString(), Charsets.UTF_8)
                block = StringBuilder()
            }
            for (entry in batch) {
                val line = entry.toString() + "\n"
                val size = line.toByteArray(Charsets.UTF_8).size
                if (length + size > MAX_FILE_BYTES) {
                    writeBlock()
                    if (!recent.renameTo(previous)) throw IOException("Could not rotate debug history")
                    length = 0
                }
                block.append(line)
                length += size
            }
            writeBlock()
        } catch (e: Exception) {
            synchronized(gate) { dropped += batch.size }
            throw e
        }
    }

    fun fatal(error: Throwable) {
        if (!enabled) return
        record(DiagnosticAction.APP, DiagnosticStep.CRASHED, error = error)
        synchronized(disk) { flush() }
    }

    fun exportBytes(trace: DebugTrace): ByteArray = synchronized(disk) {
        check(trace.belongsTo(this))
        flush()
        val report = java.io.ByteArrayOutputStream()
        val header = JSONObject().put("type", "header").put("schema", 1).put("appVersion", BuildConfig.VERSION_NAME)
            .put("build", BuildConfig.VERSION_CODE).put("androidApi", Build.VERSION.SDK_INT)
        report.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
        for (file in listOf(previous, recent)) {
            if (file.exists()) file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    // A force-stop can interrupt the very last append.
                    if (line.isNotBlank() && runCatching { JSONObject(line) }.isSuccess)
                        report.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
        report.toByteArray()
    }

    private companion object {
        val STACK_CLASS = Regex("[A-Za-z0-9_.$]+")
        val STACK_METHOD = Regex("[A-Za-z0-9_$<>-]+")
        const val MAX_PENDING = 512
        const val MAX_FILE_BYTES = 256 * 1024
        val inactive = DebugTrace(null, DiagnosticAction.RECORDING, null)
    }
}
