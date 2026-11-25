package net.kourlas.voipms_sms.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.JournalStore
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.utils.DocumentOutput
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DatabaseTransferTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val database: Database get() = Database.getInstance(context)
    private val thread = ConversationId("5145550100", "5145550199")

    @Before
    @After
    fun resetDatabase() {
        val field = Database::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = field.get(null) as? Database ?: return
        val room = Database::class.java.getDeclaredField("database").apply { isAccessible = true }
        (room.get(previous) as AbstractDatabase).close()
        field.set(null, null)
    }

    @Test
    fun firstExportTruncatesExistingDestinationAndLeavesBorrowedDescriptorOpen() = runBlocking {
        val file = File(context.cacheDir, "document.db")
        RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY).use { borrowed ->
            database.export(borrowed)
            assertTrue(borrowed.fileDescriptor.valid())
        }
        assertEquals(context.getDatabasePath("sms.db").length(), file.length())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(Database.DATABASE_VERSION, it.version)
        }
    }

    @Test
    fun slowDocumentProviderDoesNotBlockInboxAndReceivesConsistentSnapshot(): Unit = runBlocking {
        val file = File(context.cacheDir, "remote.db")
        val opened = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val export = async(Dispatchers.IO) {
            database.export(null) {
                opened.countDown()
                check(releaseProvider.await(15, TimeUnit.SECONDS))
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { opened.await(10, TimeUnit.SECONDS) })
            withTimeout(5_000) {
                database.insertConversationMessagesDeliveryInProgress(thread, listOf("concurrent"))
                assertEquals(1, database.getConversationMessagesUnsorted(thread).size)
            }
        } finally {
            releaseProvider.countDown()
        }
        assertEquals(export.await().bytes, file.length())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
            snapshot.rawQuery("SELECT COUNT(*) FROM sms", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0L, rows.getLong(0))
            }
        }
    }

    @Test
    fun providerReadbackRejectsAlteredDataAndAcceptsValidBytes(): Unit = runBlocking {
        val file = File(context.cacheDir, "document.db")
        val destination = { _: String -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY) }
        val store = JournalStore(File(context.cacheDir, "trace")) { it.run() }
        store.setEnabled(true)
        val trace = store.begin(DiagnosticAction.DATABASE_EXPORT)
        val result = database.export(trace, file::inputStream, destination)
        assertTrue(result.verified)
        assertEquals(result.bytes, file.length())
        val events = store.exportBytes(trace).toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }.map(::JSONObject).filter { it.optString("operation") == trace.id }.toList()
        assertEquals(listOf("STARTED", "SNAPSHOT", "OPENING_STREAM", "WRITING", "VERIFYING"), events.map { it.getString("stage") })
        assertEquals(result.bytes, events.last().getLong("count"))
        assertFalse(events.toString().contains(file.path))
        assertThrows(DocumentOutput.Incomplete::class.java) {
            runBlocking {
                database.export(null, { ByteArray(file.length().toInt()).inputStream() }, destination)
            }
        }
        assertThrows(DocumentOutput.Unavailable::class.java) {
            runBlocking { database.export(null) { null } }
        }
    }

    @Test
    fun unavailableProviderLeavesDatabaseUsableAndCleansStaging(): Unit = runBlocking {
        database.insertConversationMessagesDeliveryInProgress(thread, listOf("saved"))
        assertThrows(IOException::class.java) { runBlocking { database.export(null) { throw IOException() } } }
        assertThrows(IOException::class.java) { runBlocking { database.import(null) { throw IOException() } } }
        assertEquals(1, database.getConversationMessagesUnsorted(thread).size)
        assertFalse(context.cacheDir.list().orEmpty().any { it.startsWith("raw-export-") || it.startsWith("sms-import-") })
    }
}
