/*
 * VoIP.ms SMS
 * Copyright (C) 2026 Michael Kourlas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.preference.PreferenceManager
import net.kourlas.voipms_sms.preferences.getDidRetrieveMessages
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getActiveDid
import net.kourlas.voipms_sms.preferences.migrateDidPreferences
import kotlinx.coroutines.runBlocking
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.entities.SyncCheckpoint
import net.kourlas.voipms_sms.sms.workers.IncomingMessage
import net.kourlas.voipms_sms.sms.ConversationId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.Date

/**
 * Tests for [Database.import]. The tests use the plain Application class, so
 * that CustomApplication.onCreate does not start Firebase, WorkManager or
 * network callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DatabaseImportTest {
    private lateinit var context: Context
    private lateinit var database: Database
    private val conversationId = ConversationId("5145550100", "5145550199")

    @Before
    fun setUp() {
        resetDatabaseInstance()
        context = ApplicationProvider.getApplicationContext()
        database = Database.getInstance(context)
    }

    @After
    fun tearDown() {
        resetDatabaseInstance()
    }

    @Test
    fun importValidExportReplacesMessages() = runBlocking {
        insertMessages(3)
        val exportFile = exportDatabase()
        insertMessages(2)
        assertEquals(5, messageCount())

        importFile(exportFile)

        assertEquals(3, messageCount())
        assertFalse(backupFile().exists())
    }

    @Test
    fun importNonDatabaseFileKeepsMessages() = runBlocking {
        insertMessages(3)
        val file = File(context.cacheDir, "photo.jpg")
        // A JPEG header followed by data that is not a database.
        file.writeBytes(
            byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()
            ) + ByteArray(8192) { (it % 251).toByte() }
        )

        val e = assertThrows(DatabaseImportException::class.java) {
            runBlocking { importFile(file) }
        }

        assertEquals(
            context.getString(
                R.string.preferences_database_import_fail_damaged
            ), e.message
        )
        assertEquals(3, messageCount())
        assertFalse(backupFile().exists())
    }

    @Test
    fun importTruncatedDatabaseKeepsMessages() = runBlocking {
        insertMessages(3)
        val exportFile = exportDatabase()
        RandomAccessFile(exportFile, "rw").use {
            it.setLength(it.length() / 2)
        }

        val e = assertThrows(DatabaseImportException::class.java) {
            runBlocking { importFile(exportFile) }
        }

        assertEquals(
            context.getString(
                R.string.preferences_database_import_fail_damaged
            ), e.message
        )
        assertEquals(3, messageCount())
        assertFalse(backupFile().exists())
    }

    @Test
    fun importUnsupportedVersionKeepsMessages() = runBlocking {
        insertMessages(3)
        val exportFile = exportDatabase()
        SQLiteDatabase.openDatabase(
            exportFile.path, null, SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = 8 }

        val e = assertThrows(DatabaseImportException::class.java) {
            runBlocking { importFile(exportFile) }
        }

        assertEquals(
            context.getString(
                R.string.preferences_database_import_fail_version
            ), e.message
        )
        assertEquals(3, messageCount())
        assertFalse(backupFile().exists())
    }

    @Test
    fun importDatabaseWithOtherSchemaRestoresBackup() = runBlocking {
        insertMessages(3)
        // This file passes checkImportFile, so the failure occurs when Room
        // opens it, after the current database was replaced.
        val file = File(context.cacheDir, "other.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE other (id INTEGER PRIMARY KEY)")
            it.version = Database.DATABASE_VERSION
        }

        assertThrows(Exception::class.java) {
            runBlocking { importFile(file) }
        }

        assertEquals(3, messageCount())
        assertFalse(backupFile().exists())

        // The restored database accepts new messages.
        insertMessages(1)
        assertEquals(4, messageCount())
    }

    @Test
    fun emptyWindowsAdvanceOnlyTheirOwnCheckpointAndSurviveExport() = runBlocking {
        val start = "2026-08-01"
        val account = "first-account"
        val did = conversationId.did
        val checkpoint = SyncCheckpoint(account, did, start, "2026-10-29")
        database.insertMessagesVoipMsApi(emptyList(), false, checkpoint, null)
        val exportFile = exportDatabase()
        database.insertMessagesVoipMsApi(emptyList(), false, checkpoint.copy(throughDay = "2026-11-01"), checkpoint.throughDay)
        // A late or repeated run cannot move a cursor backwards.
        database.insertMessagesVoipMsApi(emptyList(), false, checkpoint, null)
        assertEquals("2026-11-01", database.checkedThrough(account, did, start))
        assertNull(database.checkedThrough("second-account", did, start))
        assertNull(database.checkedThrough(account, "5145550101", start))
        assertNull(database.checkedThrough(account, did, "2026-07-01"))

        importFile(exportFile)
        assertEquals(checkpoint.throughDay, database.checkedThrough(account, did, start))
        database.deleteTablesContents()
        assertNull(database.checkedThrough(account, did, start))
        // A worker planned against the previous database cannot establish a later cursor.
        database.insertMessagesVoipMsApi(emptyList(), false, checkpoint.copy(throughDay = "2026-11-01"), checkpoint.throughDay)
        assertNull(database.checkedThrough(account, did, start))
    }

    @Test
    fun providerReplayAndSendResponseKeepOneComposerRow() = runBlocking {
        val id = database.insertConversationMessagesDeliveryInProgress(conversationId, listOf("Hi")).single()
        val echo = IncomingMessage(42, Date(), false, conversationId.did, conversationId.contact,
            "Hi", null, null, null)
        database.insertMessagesVoipMsApi(listOf(echo, echo), false, null, null)
        database.markMessageSent(id, echo.voipId)
        // A stale send worker finishing its cleanup must preserve the confirmed receipt.
        database.markMessageNotSent(id)
        database.insertMessagesVoipMsApi(listOf(echo), false, null, null)
        val message = database.getConversationMessagesUnsorted(conversationId).single()
        assertEquals(id, message.databaseId)
        assertEquals(echo.voipId, message.voipId)
        assertTrue(message.isDelivered)
    }

    @Test
    fun folderSurvivesSyncSearchDraftArchiveAndExport() = runBlocking {
        fun incoming(id: Long, text: String) = IncomingMessage(id, Date(id * 1000L), true,
            conversationId.did, conversationId.contact, text, null, null, null)
        suspend fun preview(query: String = "", archived: Boolean = false) =
            database.inboxPreviews(setOf(conversationId.did), query, archived, mutableMapOf()).single()
        database.insertMessagesVoipMsApi(listOf(incoming(1, "earlier text")), false, null, null)
        assertNull(preview().otherFolder)
        database.moveToInboxFolder(listOf(conversationId), true)
        database.markConversationArchived(conversationId)
        assertEquals(true, preview(archived = true).otherFolder)
        database.insertMessagesVoipMsApi(listOf(incoming(2, "latest text")), false, null, null)
        database.updateConversationDraft(conversationId, "draft")
        assertTrue(preview().message.isDraft)
        val result = preview("earlier")
        assertEquals("earlier text", result.message.text)
        assertEquals("latest text", result.latestIncoming)
        assertEquals(true, result.otherFolder)
        val exported = exportDatabase()
        database.moveToInboxFolder(listOf(conversationId), false)
        assertEquals(false, preview().otherFolder)
        importFile(exported)
        assertEquals(true, preview().otherFolder)
    }

    @Test
    fun versionElevenLineAliasesKeepHistoryAndUnsentDrafts() = runBlocking {
        val alias = ConversationId("1" + conversationId.did, conversationId.contact)
        database.insertConversationMessagesDeliveryInProgress(alias, listOf("previous history"))
        database.insertConversationMessagesDeliveryInProgress(conversationId, listOf("previous history"))
        database.updateConversationDraft(alias, "legacy draft")
        database.updateConversationDraft(conversationId, "current draft")

        // Reconstruct the version 11 schema from the export before replaying both migrations.
        val legacy = exportDatabase()
        SQLiteDatabase.openDatabase(legacy.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP INDEX index_sms_inbox")
            it.execSQL("ALTER TABLE archived RENAME TO archived_current")
            it.execSQL("CREATE TABLE archived (DatabaseId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, Did TEXT NOT NULL, Contact TEXT NOT NULL, Archived INTEGER NOT NULL)")
            it.execSQL("INSERT INTO archived SELECT DatabaseId,Did,Contact,Archived FROM archived_current")
            it.execSQL("DROP TABLE archived_current")
            it.execSQL("DROP TABLE sync_checkpoint")
            it.execSQL("DROP INDEX index_sms_Did_VoipId")
            it.execSQL("DROP INDEX index_deleted_Did_VoipId")
            // The old client had fetched the same provider record under both spellings.
            it.execSQL("UPDATE sms SET VoipId = 42, Delivered = 1, DeliveryInProgress = 0")
            it.execSQL("UPDATE room_master_table SET identity_hash = 'cddedea65b24b844237b4da443fc2181' WHERE id = 42")
            it.version = 11
        }
        importFile(legacy)
        assertEquals("previous history", database.getConversationMessagesUnsorted(conversationId).single().text)
        assertEquals("current draft\n\nlegacy draft", database.getConversationDraft(conversationId)?.text)
        val migrated = exportDatabase()
        SQLiteDatabase.openDatabase(migrated.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (table in listOf("sms", "deleted")) {
                db.rawQuery("EXPLAIN QUERY PLAN SELECT DatabaseId FROM $table WHERE Did = ? AND VoipId = ?",
                    arrayOf(conversationId.did, "42")).use { cursor ->
                    cursor.moveToFirst()
                    org.junit.Assert.assertTrue(cursor.getString(3).contains("index_${table}_Did_VoipId"))
                }
            }
        }
    }

    @Test
    fun legacyDidPreferenceMigrationRetainsOptOutAndActiveLine() {
        val did = conversationId.did
        val alias = "+1" + did
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putStringSet(context.getString(R.string.preferences_dids_key), setOf(alias, did))
            .putString(context.getString(R.string.preferences_dids_active_did_key), alias)
            .putBoolean(context.getString(R.string.preferences_did_retrieve_messages_key, alias), false)
            .putBoolean(context.getString(R.string.preferences_did_retrieve_messages_key, did), true)
            .commit()
        migrateDidPreferences(context)
        migrateDidPreferences(context)
        assertEquals(setOf(did), getDids(context))
        assertEquals(did, getActiveDid(context))
        assertFalse(getDidRetrieveMessages(context, did))
    }

    private suspend fun insertMessages(count: Int) {
        database.insertConversationMessagesDeliveryInProgress(
            conversationId, List(count) { "Message $it" }
        )
    }

    private suspend fun messageCount(): Int =
        database.getConversationMessagesUnsorted(conversationId).size

    private suspend fun exportDatabase(): File {
        val file = File(context.cacheDir, "export.db")
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE
                or ParcelFileDescriptor.MODE_TRUNCATE
                or ParcelFileDescriptor.MODE_WRITE_ONLY
        ).use { database.export(it) }
        return file
    }

    private suspend fun importFile(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            .use { database.import(it) }
    }

    private fun backupFile(): File =
        File("${context.getDatabasePath("sms.db").absolutePath}.backup")

    /**
     * Closes the Room database of the current Database instance and removes
     * the instance, so that each test starts with a new instance for its own
     * Application object.
     */
    private fun resetDatabaseInstance() {
        val instanceField =
            Database::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        val instance = instanceField.get(null) as Database? ?: return
        val databaseField =
            Database::class.java.getDeclaredField("database")
        databaseField.isAccessible = true
        (databaseField.get(instance) as AbstractDatabase).close()
        instanceField.set(null, null)
    }
}
