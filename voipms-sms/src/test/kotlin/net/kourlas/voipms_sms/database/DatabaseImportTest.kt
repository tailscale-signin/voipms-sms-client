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
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.diagnostics.JournalStore
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

    @Test fun unreadThreadSurvivesDraftAndSearchUntilOpened(): Unit = runBlocking {
        fun incoming(id: Long, text: String) = IncomingMessage(id, Date(id * 1000), true,
            conversationId.did, conversationId.contact, text, null, null, null)
        database.insertMessagesVoipMsApi(listOf(incoming(1, "Old note")), false, null, null)
        database.markConversationRead(conversationId)
        database.insertMessagesVoipMsApi(listOf(incoming(2, "New note")), false, null, null)
        database.updateConversationDraft(conversationId, "Partly typed reply")
        suspend fun preview(query: String) = database.inboxPreviews(setOf(conversationId.did), query, false, mutableMapOf()).single()
        assertTrue(preview("").message.isDraft)
        assertTrue(preview("").unread)
        assertTrue(preview("old").unread)
        assertEquals("New note", preview("old").latestIncoming)
        database.markConversationRead(conversationId)
        assertFalse(preview("").unread)
    }

    @Test fun automaticClipboardCopiesOnlyFreshNewCodesAndHonoursSwitch(): Unit = runBlocking {
        val manager = context.getSystemService(android.content.ClipboardManager::class.java)
        val prefs = context.getSharedPreferences("code-privacy", Context.MODE_PRIVATE)
        val now = 1_800_000_000_000L
        fun message(id: Long, offset: Long, incoming: Boolean = true, text: String = "Verification code: 00$id.") =
            IncomingMessage(id, Date(now + offset), incoming, conversationId.did, conversationId.contact, text, null, null, null)
        suspend fun receive(vararg rows: IncomingMessage, since: Long = 0) =
            database.insertMessagesVoipMsApi(rows.toList(), false, null, null) { batch ->
                net.kourlas.voipms_sms.privacy.CodePrivacy.copyReceived(context, 42, batch, since, now)
            }
        fun current() = manager.primaryClip?.getItemAt(0)?.text?.toString()
        fun sentinel() = manager.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "previous"))
        sentinel()
        receive(message(1111, -1000), message(1112, -2000), message(1113, 0, incoming = false))
        assertEquals("001111", current())
        assertTrue(manager.primaryClipDescription?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
        sentinel()
        receive(message(1111, -1000), message(1114, -3000), message(1115, -3600_000))
        receive(message(1116, -1000), since = now)
        receive(message(1117, 0, text = "Lunch is at noon."))
        assertEquals("previous", current())
        prefs.edit().putBoolean("autoCopy", false).commit()
        receive(message(1118, 0))
        assertEquals("previous", current())
        prefs.edit().putBoolean("autoCopy", true).commit()
        context.getSharedPreferences("private-threads", Context.MODE_PRIVATE).edit()
            .putBoolean(conversationId.getId(), true).commit()
        receive(message(1119, 0))
        assertEquals("previous", current())
    }

    @Test
    fun receivedImageRestoresOfflineToASyncedThreadAndPrivateImagesStayOut(): Unit = runBlocking {
        val media = net.kourlas.voipms_sms.media.MmsMedia
        val archive = net.kourlas.voipms_sms.privacy.PortableBackup
        val remote = "https://sms.voip.ms/mms/public-image.png"
        val privateRemote = "https://sms.voip.ms/mms/private-image.png"
        val unavailable = "https://example.invalid/unsupported.png"
        val privatePeer = ConversationId(conversationId.did, "5145550122")
        val image = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+tm1sAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT)
        val directory = File(context.cacheDir, "mms_preview").apply { mkdirs() }
        fun cached(ref: String) = File(directory, "${media.receivedHash(ref)}.png").apply { writeBytes(image) }
        val primaryCache = cached(remote)
        val privateCache = cached(privateRemote)
        val messages = listOf(
            IncomingMessage(34, Date(10000), true, conversationId.did, conversationId.contact, null, remote, unavailable, null),
            IncomingMessage(35, Date(20000), true, privatePeer.did, privatePeer.contact, null, privateRemote, null, null))
        database.insertMessagesVoipMsApi(messages, false, null, null)
        val locks = context.getSharedPreferences("private-threads", Context.MODE_PRIVATE)
        locks.edit().putBoolean(privatePeer.getId(), true).commit()
        val uri = android.net.Uri.fromFile(File(context.cacheDir, "received-test.vsms"))
        val pass = "portable test pass".toCharArray()
        try {
            val report = archive.export(context, uri, pass, net.kourlas.voipms_sms.privacy.PortableBackup.ReceivedMode.CACHED)
            assertEquals(1, report.included)
            assertEquals(1, report.unavailable)
            val contents = archive.preview(context, uri, pass)
            try {
                assertEquals(setOf(conversationId), contents.counts.keys)
                assertEquals(setOf(media.receivedHash(remote)), contents.received.keys)
                assertTrue(primaryCache.delete())
                assertNull(media.knownReceived(context, remote))
                assertEquals(0, database.restorePortableThreads(contents, setOf(conversationId)))
                val local = requireNotNull(media.knownReceived(context, remote))
                assertEquals("mms_received", local.parentFile!!.name)
                assertTrue(image.contentEquals(media.previewFile(context, remote).readBytes()))
                assertFalse(database.getConversationMessagesUnsorted(conversationId).single().isDeliveryInProgress)
                local.delete()
            } finally { contents.zip.delete() }
        } finally {
            locks.edit().remove(privatePeer.getId()).commit()
            privateCache.delete()
        }
    }

    @Test
    fun portableBackupPreviewsAndMergesOnlySelectedHistoryWithoutRequeueing() = runBlocking {
        val peer = ConversationId(conversationId.did, "5145550117")
        val attachmentDir = File(context.filesDir, "mms_outgoing").apply { mkdirs() }
        val media = (1..4).map { index ->
            File(attachmentDir, "${java.util.UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(index.toByte())) }
        }
        database.insertConversationMessagesDeliveryInProgress(conversationId, listOf("with four images"),
            media.map { "mms-local:" + it.name })
        insertMessages(1)
        database.insertConversationMessagesDeliveryInProgress(peer, listOf("leave behind"))
        val uri = android.net.Uri.fromFile(File(context.cacheDir, "portable-test.vsms"))
        val pass = "local test archive".toCharArray()
        val archive = net.kourlas.voipms_sms.privacy.PortableBackup
        archive.export(context, uri, pass)
        assertThrows(Exception::class.java) { archive.preview(context, uri, "incorrect".toCharArray()) }
        assertEquals(2, messageCount())
        val contents = archive.preview(context, uri, pass)
        try {
            assertEquals(mapOf(conversationId to 2, peer to 1), contents.counts)
            assertEquals(0, database.restorePortableThreads(contents, setOf(conversationId)))
            database.deleteTablesContents()
            media.forEach { assertTrue(it.delete()) }
            assertEquals(2, database.restorePortableThreads(contents, setOf(conversationId)))
            assertEquals(listOf(1, 2, 3, 4), media.map { it.readBytes().single().toInt() })
            assertEquals(4, database.getConversationMessagesUnsorted(conversationId).first { it.text == "with four images" }.medias.size)
            assertEquals(0, database.restorePortableThreads(contents, setOf(conversationId)))
            assertTrue(database.getConversationMessagesUnsorted(peer).isEmpty())
            assertTrue(database.getConversationMessagesUnsorted(conversationId).none { it.isDeliveryInProgress })
        } finally { contents.zip.delete() }
    }

    @Test
    fun portableExportReadsAllPagesAndReplacesALargerDocument(): Unit = runBlocking {
        val archive = net.kourlas.voipms_sms.privacy.PortableBackup
        val file = File(context.cacheDir, "multi-page.vsms").apply { writeBytes(ByteArray(128 * 1024)) }
        val password = "paged backup test".toCharArray()
        try {
            insertMessages(257)
            val uri = android.net.Uri.fromFile(file)
            val journal = JournalStore(File(context.cacheDir, "portable-journal")) { it.run() }
            journal.setEnabled(true)
            val trace = journal.begin(DiagnosticAction.PORTABLE_EXPORT)
            val report = archive.export(context, uri, password, includeContacts = false, trace = trace)
            assertTrue(report.verified)
            val log = journal.exportBytes(trace).toString(Charsets.UTF_8)
            val events = log.lineSequence().filter(String::isNotBlank).map { org.json.JSONObject(it) }
                .filter { it.optString("action") == DiagnosticAction.PORTABLE_EXPORT.name }.toList()
            assertTrue(events.all { it.optString("operation") == trace.id })
            assertTrue(events.any { it.optString("stage") == DiagnosticStep.MESSAGES.name && it.optInt("count") == 257 })
            assertTrue(events.any { it.optString("stage") == DiagnosticStep.WRITING.name })
            assertTrue(events.any { it.optString("stage") == DiagnosticStep.VERIFYING.name })
            assertTrue(listOf(conversationId.contact, conversationId.did, file.name, "Message 256",
                "paged backup test").none(log::contains))
            assertTrue(file.length() < 128 * 1024)
            val preview = archive.preview(context, uri, password)
            try {
                assertEquals(257, preview.counts[conversationId])
                java.util.zip.ZipFile(preview.zip).use { zip ->
                    assertEquals(List(257) { "Message $it" }, archive.rows(zip, conversationId).map { it.text }.toList())
                }
            } finally { preview.zip.delete() }
        } finally { password.fill('\u0000'); file.delete() }
    }

    @Test
    fun originalVersionOnePortableArchiveRestoresWithoutAnAddressBook(): Unit = runBlocking {
        val archive = net.kourlas.voipms_sms.privacy.PortableBackup
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("version"))
            out.write('1'.code)
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("threads/" + conversationId.getId()))
            val record = """{"time":10000,"provider":21,"in":1,"text":"original archive","unread":1,"sent":1,"media":[]}""".toByteArray()
            val data = java.io.DataOutputStream(out)
            data.writeInt(record.size)
            data.write(record)
            data.writeInt(0)
            out.closeEntry()
        }
        val file = File(context.cacheDir, "original-portable.vsms")
        val password = "version one passphrase".toCharArray()
        try {
            file.outputStream().use { dest ->
                net.kourlas.voipms_sms.privacy.BackupEnvelope.write(zip.toByteArray().inputStream(), dest, password)
            }
            val parsed = archive.preview(context, android.net.Uri.fromFile(file), password)
            try {
                assertTrue(parsed.contacts.isEmpty())
                val restored = archive.restore(context, parsed, net.kourlas.voipms_sms.privacy.PortableBackup.Selection(
                    setOf(conversationId), emptySet()))
                assertEquals(1, restored.messages)
                assertEquals(0, restored.contacts)
                assertEquals("original archive", database.getConversationMessagesUnsorted(conversationId).single().text)
            } finally { parsed.zip.delete() }
        } finally { password.fill('\u0000'); file.delete() }
    }

    @Test
    fun encryptedContactBookIsOptionalAndCanRestoreSelectedNamesWithoutAnyHistory(): Unit = runBlocking {
        val contacts = net.kourlas.voipms_sms.contacts.LocalContacts
        val archive = net.kourlas.voipms_sms.privacy.PortableBackup
        val target = File(context.noBackupFilesDir, "local_contacts.v1")
        val instance = contacts.javaClass.getDeclaredField("instance").apply { isAccessible = true }
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val monday = "5145550177"
        val conflict = "5145550188"
        val ignored = "5145550192"
        fun attachEmptyBook() {
            if (target.exists()) assertTrue(target.delete())
            instance.set(contacts, net.kourlas.voipms_sms.contacts.ContactFile(target) { key })
        }
        attachEmptyBook()
        val document = File(context.cacheDir, "contacts-test.vsms")
        val uri = android.net.Uri.fromFile(document)
        val password = "local contacts passphrase".toCharArray()
        try {
            contacts.save(context, monday, "Équipe du lundi")
            contacts.save(context, conflict, "Backup name")
            contacts.save(context, ignored, "Unselected person")
            insertMessages(1)
            assertNull(archive.export(context, uri, password, includeContacts = false).contacts)
            val messagesOnly = archive.preview(context, uri, password)
            try {
                assertTrue(messagesOnly.contacts.isEmpty())
                java.util.zip.ZipFile(messagesOnly.zip).use { assertNull(it.getEntry(net.kourlas.voipms_sms.privacy.BackupContacts.ENTRY)) }
            } finally { messagesOnly.zip.delete() }
            assertEquals(3, archive.export(context, uri, password).contacts)
            val preview = archive.preview(context, uri, password)
            try {
                assertEquals(3, preview.contacts.size)
                java.util.zip.ZipFile(preview.zip).use { zip ->
                    val encrypted = zip.getInputStream(zip.getEntry(net.kourlas.voipms_sms.privacy.BackupContacts.ENTRY)).use { it.readBytes() }
                    assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(monday))
                    assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("Backup name"))
                    assertThrows(Exception::class.java) {
                        net.kourlas.voipms_sms.privacy.BackupContacts.read(encrypted.inputStream(), "wrong".toCharArray())
                    }
                }
                attachEmptyBook()
                contacts.save(context, conflict, "Current name")
                database.deleteTablesContents()
                val selection = net.kourlas.voipms_sms.privacy.PortableBackup.Selection(emptySet(), setOf(monday, conflict))
                val result = archive.restore(context, preview, selection)
                assertEquals(0, result.messages)
                assertEquals(1, result.contacts)
                assertFalse(result.contactsFailed)
                assertEquals(mapOf(monday to "Équipe du lundi", conflict to "Current name"), contacts.snapshot(context))
                assertEquals(0, archive.restore(context, preview, selection).contacts)
                assertEquals(0, messageCount())
            } finally { preview.zip.delete() }
        } finally {
            instance.set(contacts, null)
            target.delete()
            document.delete()
            password.fill('\u0000')
        }
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
    fun importDatabaseWithOtherSchemaKeepsCurrentMessages() = runBlocking {
        insertMessages(3)
        // A supported version alone does not identify a valid backup.
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

        // The original database still accepts new messages.
        insertMessages(1)
        assertEquals(4, messageCount())
    }

    @Test
    fun wrongRoomIdentityRestoresOriginalDatabase() = runBlocking {
        insertMessages(1)
        val exported = exportDatabase()
        SQLiteDatabase.openDatabase(exported.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE room_master_table SET identity_hash = 'unknown' WHERE id = 42")
        }
        insertMessages(2)
        assertThrows(IllegalStateException::class.java) { runBlocking { importFile(exported) } }
        assertEquals(3, messageCount())
        insertMessages(1)
        assertEquals(4, messageCount())
    }

    @Test
    fun importRejectsUnexpectedTriggerIncludingOneUsingAnExpectedName() = runBlocking {
        insertMessages(2)
        val exported = exportDatabase()
        SQLiteDatabase.openDatabase(exported.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TRIGGER search_removed")
            it.execSQL("CREATE TRIGGER search_removed AFTER DELETE ON sms BEGIN SELECT 1; END")
        }
        assertThrows(DatabaseImportException::class.java) { runBlocking { importFile(exported) } }
        assertEquals(2, messageCount())
        assertFalse(backupFile().exists())
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
            for (name in listOf("search_added", "search_changed", "search_removed", "search_media_changed")) {
                it.execSQL("DROP TRIGGER " + name)
            }
            it.execSQL("DROP TABLE message_search")
            it.execSQL("DROP TABLE OutboxPart")
            it.execSQL("DROP INDEX index_sms_search_date")
            it.execSQL("ALTER TABLE sms DROP COLUMN MediaJson")
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
        // A migrated installation can export and re-import the same data a second time.
        importFile(migrated)
        assertEquals("previous history", database.getConversationMessagesUnsorted(conversationId).single().text)
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
