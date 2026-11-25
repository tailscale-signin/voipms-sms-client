package net.kourlas.voipms_sms.diagnostics

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DebugJournalTest {
    @get:Rule val folder = TemporaryFolder()
    private val dir get() = File(folder.root, "debug-journal")
    private val tasks = ArrayDeque<Runnable>()
    private val executor = Executor { tasks.addLast(it) }
    private fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    private fun report(store: JournalStore) = store.exportBytes(store.begin(DiagnosticAction.DEBUG_EXPORT))
        .toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).map(::JSONObject).toList()

    @Test fun recordingIsOptInAndRevokingConsentDeletesPendingAndPersistedEvents() {
        val store = JournalStore(dir, executor)
        store.begin(DiagnosticAction.INBOX_LOAD).step(DiagnosticStep.LOADING)
        store.fatal(IllegalStateException("secret"))
        assertFalse(dir.exists())
        store.setEnabled(true)
        val trace = store.begin(DiagnosticAction.THREAD_LOAD)
        trace.step(DiagnosticStep.QUERYING)
        drain()
        trace.step(DiagnosticStep.RENDERING)
        store.setEnabled(false)
        trace.step(DiagnosticStep.COMPLETED)
        drain()
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertFalse(JournalStore(dir, executor).enabled)
        store.setEnabled(true)
        assertNull(store.resume(DiagnosticAction.THREAD_LOAD, trace.id).id)
        assertFalse(report(store).any { it.optString("action") == "THREAD_LOAD" })
    }

    @Test fun operationSurvivesProcessRestartAndClearInvalidatesPicker() {
        val first = JournalStore(dir, executor)
        first.setEnabled(true)
        val trace = first.begin(DiagnosticAction.PORTABLE_EXPORT)
        trace.step(DiagnosticStep.PICKER_OPENED)
        drain()
        val next = JournalStore(dir, executor)
        val resumed = next.resume(DiagnosticAction.PORTABLE_EXPORT, trace.id)
        resumed.step(DiagnosticStep.COMPLETED, count = 42)
        val records = report(next).filter { it.optString("action") == "PORTABLE_EXPORT" }
        assertEquals(listOf("STARTED", "PICKER_OPENED", "COMPLETED"), records.map { it.getString("stage") })
        assertTrue(records.all { it.getString("operation") == trace.id })
        assertEquals(42L, records.last().getLong("count"))
        assertFalse(records.last().has("elapsedMs"))
        assertFalse(next.accepts("not-an-operation"))
        next.clear()
        assertNull(next.resume(DiagnosticAction.PORTABLE_EXPORT, trace.id).id)
        assertThrows(IllegalStateException::class.java) { next.exportBytes(resumed) }
        assertFalse(report(next).any { it.optString("action") == "PORTABLE_EXPORT" })
    }

    @Test fun crashFlushesBeforeRestartAndOmitsExceptionMessagesAndFilenames() {
        val store = JournalStore(dir, executor)
        store.setEnabled(true)
        val cause = IllegalArgumentException("content://private/SMS-123")
        val error = IllegalStateException("contact secret", cause)
        error.stackTrace = arrayOf(StackTraceElement("net.kourlas.voipms_sms.Sample", "render", "personal-name.kt", 7))
        store.fatal(error)
        val records = report(JournalStore(dir, executor))
        assertEquals(1, records.first().getInt("schema"))
        val event = records.single { it.optString("stage") == "CRASHED" }
        assertEquals(IllegalStateException::class.java.name, event.getJSONObject("error").getString("class"))
        assertTrue(event.toString().contains("Sample.render:7"))
        for (sensitive in listOf("contact secret", "content://", "personal-name.kt", "SMS-123")) {
            assertFalse(records.toString().contains(sensitive))
        }
    }

    @Test fun overloadIsReportedAndRecentHistoryStaysWithinTwoFiles() {
        val store = JournalStore(dir, executor)
        store.setEnabled(true)
        repeat(700) { store.record(DiagnosticAction.SMS_SYNC, DiagnosticStep.STARTED) }
        val limited = report(store)
        assertTrue(limited.single { it.optString("stage") == "DROPPED" }.getLong("count") >= 188)
        assertTrue(limited.size <= 514)
        repeat(6) {
            repeat(500) { store.record(DiagnosticAction.SMS_SYNC, DiagnosticStep.COMPLETED) }
            drain()
        }
        val latest = report(store)
        val logs = dir.listFiles().orEmpty().filter { it.extension == "jsonl" }
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.length() in 1L..262144L })
        assertTrue(latest.drop(1).all { it.getString("type") == "event" })
        assertEquals("DEBUG_EXPORT", latest.last().getString("action"))
    }
}
