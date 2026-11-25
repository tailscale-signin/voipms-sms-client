/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2021 Michael Kourlas
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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import net.kourlas.voipms_sms.preferences.ShareSuggestions
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.tools.LineProfiles
import net.kourlas.voipms_sms.sms.InboxRules
import net.kourlas.voipms_sms.sms.VerificationCode
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.database.entities.Archived
import net.kourlas.voipms_sms.database.entities.Deleted
import net.kourlas.voipms_sms.database.entities.Draft
import net.kourlas.voipms_sms.database.entities.SyncCheckpoint
import net.kourlas.voipms_sms.database.entities.Sms
import net.kourlas.voipms_sms.demo.getDemoNotification
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.sms.workers.IncomingMessage
import net.kourlas.voipms_sms.utils.CoroutineReadWriteLock
import net.kourlas.voipms_sms.utils.getContactName
import net.kourlas.voipms_sms.utils.getContactPhotoAdaptiveBitmap
import net.kourlas.voipms_sms.utils.getDigitsOfString
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale

private const val TAG = "Database"


/**
 * Provides access to the application's database.
 */
class Database private constructor(private val context: Context) {
    private val importExportLock = CoroutineReadWriteLock(readers = 100)
    private var database = createDatabase(context)

    internal suspend fun <T> outboxTransaction(action: suspend AbstractDatabase.() -> T): T =
        importExportLock.read { database.withTransaction { database.action() } }

    /**
     * Deletes the message with the specified DID, database ID, and optionally
     * VoIP.ms ID from the database.
     */
    suspend fun deleteMessage(did: String, databaseId: Long, voipId: Long?) =
        coroutineScope {
            importExportLock.read {
                database.withTransaction {
                    if (voipId != null) {
                        insertVoipIdDeleted(did, voipId)
                    }
                    database.smsDao().deleteById(databaseId)
                }
            }

            launch {
                updateShortcuts()
            }
        }

    /**
     * Deletes all messages that are not associated with the specified DIDs.
     */
    suspend fun deleteMessagesWithoutDids(dids: Set<String>) =

        importExportLock.read {
            database.withTransaction {
                database.syncCheckpointDao().deleteWithoutDids(dids)
                database.smsDao().deleteWithoutDids(dids)
                database.deletedDao().deleteWithoutDids(dids)
                database.draftDao().deleteWithoutDids(dids)
                database.archivedDao().deleteWithoutDids(dids)
            }
        }

    /**
     * Deletes the messages in the specified conversation from the database.
     * Also deletes any draft message if one exists.
     */
    suspend fun deleteConversation(conversationId: ConversationId) =
        coroutineScope {
            val messages = importExportLock.read {
                database.withTransaction {
                    val messages = getConversationMessagesUnsortedWithoutLock(
                        conversationId
                    )
                    for (message in messages) {
                        if (message.voipId != null) {
                            insertVoipIdDeleted(message.did, message.voipId)
                        }
                    }

                    database.smsDao().deleteConversation(
                        conversationId.did, conversationId.contact
                    )
                    database.draftDao().deleteConversation(
                        conversationId.did, conversationId.contact
                    )
                    database.archivedDao().deleteConversation(
                        conversationId.did, conversationId.contact
                    )

                    updateConversationDraftWithoutLock(conversationId, "")

                    messages
                }
            }

            launch {
                updateShortcuts()
            }

            messages
        }

    /**
     * Deletes all rows in the deleted messages table from the database.
     */
    suspend fun deleteTableDeletedContents() =
        importExportLock.read {
            database.deletedDao().deleteAll()
        }

    /**
     * Deletes all rows in all tables in the database.
     */
    suspend fun deleteTablesContents() = coroutineScope {
        importExportLock.read {
            database.withTransaction {
                database.syncCheckpointDao().clear()
                database.smsDao().deleteAll()
                database.deletedDao().deleteAll()
                database.draftDao().deleteAll()
                database.archivedDao().deleteAll()
            }
        }

        launch {
            updateShortcuts()
        }
    }

    /**
     * Exports the database to the specified file descriptor.
     */
    suspend fun export(exportFd: ParcelFileDescriptor) =
        importExportLock.write {
            val dbFile = context.getDatabasePath(DATABASE_NAME)

            try {
                // Close database to persist it to disk before export
                database.close()

                val exportStream = FileOutputStream(exportFd.fileDescriptor)
                val dbStream = FileInputStream(dbFile)
                exportStream.channel.truncate(0)
                val buffer = ByteArray(1024)
                do {
                    val length = dbStream.read(buffer)
                    if (length > 0) {
                        exportStream.write(buffer, 0, length)
                    } else {
                        break
                    }
                } while (true)
                exportStream.close()
                dbStream.close()
            } finally {
                // Refresh database after export
                database.close()
                database = createDatabase(context)
            }
        }

    /**
     * Gets the draft message for the specified conversation.
     *
     * @return Null if the message does not exist.
     */
    suspend fun getConversationDraft(
        conversationId: ConversationId
    ): Message? =
        importExportLock.read {
            database.draftDao().getConversation(
                conversationId.did, conversationId.contact
            )?.toMessage()
        }

    /**
     * Gets all conversation IDs in the database associated with the specified
     * DIDs.
     */
    suspend fun getConversationIds(
        dids: Set<String>
    ): Set<ConversationId> =
        importExportLock.read {
            getConversationIdsWithoutLock(dids)
        }

    /**
     * Gets all messages in a specified conversation that match a specified
     * filter constraint. The resulting list is sorted by date, from most
     * recent to least recent.
     */
    suspend fun getConversationMessagesFiltered(
        conversationId: ConversationId,
        filterConstraint: String,
        itemLimit: Long?
    ): List<Message> =
        importExportLock.read {
            val rows = if (itemLimit != null) {
                database.smsDao().getConversationMessagesFilteredWithLimit(
                    conversationId.did, conversationId.contact,
                    filterConstraint, itemLimit
                )
            } else {
                database.smsDao()
                    .getConversationMessagesFiltered(
                        conversationId.did,
                        conversationId.contact,
                        filterConstraint
                    )
            }
            val queued = if (rows.any { it.incoming == 0L && it.delivered == 0L })
                database.outboxDao().forMessages(conversationId.did, conversationId.contact).associateBy { it.messageId }
                else emptyMap()
            rows.map { row -> row.toMessage().apply {
                queued[row.databaseId]?.let { outboxState = it.state; outboxDueAt = it.dueAt }
            } }
        }

    /**
     * Gets the number of messages in a specified conversation that match a
     * specified filter constraint.
     */
    suspend fun getConversationMessagesFilteredCount(
        conversationId: ConversationId,
        filterConstraint: String
    ): Long =
        importExportLock.read {
            database.smsDao()
                .getConversationMessagesFilteredCount(
                    conversationId.did,
                    conversationId.contact,
                    filterConstraint
                )
        }

    /**
     * Gets all unread messages that chronologically follow the most recent
     * outgoing message for the specified conversation.
     *
     * The resulting list is sorted by date, from least recent to most recent.
     */
    suspend fun getConversationMessagesUnread(
        conversationId: ConversationId
    ): List<Message> =
        importExportLock.read {
            // Retrieve the date of the most recent outgoing message.
            val date = database.smsDao()
                .getConversationMessageDateMostRecentOutgoing(
                    conversationId.did,
                    conversationId.contact
                ) ?: 0

            // Retrieve all unread messages with a date equal to or after
            // this date.
            database.smsDao()
                .getConversationMessagesUnreadAfterDate(
                    conversationId.did, conversationId.contact, date
                )
                .map { it.toMessage() }
        }

    /**
     * Gets all messages in a specified conversation. The resulting list is not
     * sorted.
     */
    suspend fun getConversationMessagesUnsorted(
        conversationId: ConversationId
    ): List<Message> =
        importExportLock.read {
            getConversationMessagesUnsortedWithoutLock(conversationId)
        }

    /**
     * Gets the most recent message in each conversation associated with the
     * specified DIDs that matches a specified filter constraint. The resulting
     * list is sorted by date, from most recent to least recent.
     */
    internal data class InboxPreview(
        val message: Message, val otherFolder: Boolean?, val latestIncoming: String?, val hasReply: Boolean
    )

    internal suspend fun inboxPreviews(
        dids: Set<String>, filter: String, archived: Boolean, names: MutableMap<String, String>
    ): List<InboxPreview> = importExportLock.read {
        if (dids.isEmpty()) return@read emptyList()
        val latest = database.smsDao().latestForDids(dids, "", "").associateBy { ConversationId(it.did, it.contact) }
        val settings = database.archivedDao().forDids(dids).associateBy { ConversationId(it.did, it.contact) }
        val replies = database.smsDao().threadsWithReplies(dids).toHashSet()
        getConversationsMessageMostRecentFilteredWithoutLock(dids, filter, names, latest).mapNotNull { message ->
            val id = message.conversationId
            val row = settings[id]
            if ((row?.archived == 1) != archived) return@mapNotNull null
            InboxPreview(message, row?.otherFolder, latest[id]?.takeIf { it.incoming == 1L }?.text, id in replies)
        }
    }

    /**
     * Gets all DIDs used in the database.
     */
    internal suspend fun readPortableThreads(visit: (ConversationId, List<Sms>) -> Unit) =
        importExportLock.read {
            database.withTransaction {
                val dao = database.smsDao()
                val lines = dao.getDids().toSet()
                for (id in dao.getConversationIds(lines)) {
                    if (!net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(context, id)) {
                        visit(id, dao.getConversationMessagesUnsorted(id.did, id.contact))
                    }
                }
            }
        }

    internal suspend fun restorePortableThreads(preview: net.kourlas.voipms_sms.privacy.PortableBackup.Preview,
                                               selection: Set<ConversationId>): Int = importExportLock.write {
        val created = mutableListOf<File>()
        var added = 0
        try {
            java.util.zip.ZipFile(preview.zip).use { zip ->
                database.withTransaction {
                    val dao = database.smsDao()
                    val installed = mutableSetOf<String>()
                    for (id in selection) {
                        require(id in preview.counts && !net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(context, id))
                        val rows = net.kourlas.voipms_sms.privacy.PortableBackup.rows(zip, id)
                        // Match repeated local-only records by multiplicity when merging the same backup again.
                        fun fingerprint(row: Sms) = row.copy(databaseId = 0, unread = 0, deliveryInProgress = 0,
                            media1 = "", media2 = "", media3 = "", mediaJson = net.kourlas.voipms_sms.media.AttachmentList.encode(net.kourlas.voipms_sms.privacy.PortableBackup.attachments(row)))
                        val existing = dao.localOnlyMessages(id.did, id.contact)
                            .groupingBy(::fingerprint).eachCount().toMutableMap()
                        for (row in rows) {
                            val providerId = row.voipId
                            if (providerId != null && (dao.getIdByVoipId(id.did, providerId) != null ||
                                database.deletedDao().get(id.did, providerId) != null)) continue
                            if (providerId == null) {
                                val key = fingerprint(row)
                                val count = existing.getOrDefault(key, 0)
                                if (count > 0) { existing[key] = count - 1; continue }
                            }
                            for (ref in net.kourlas.voipms_sms.privacy.PortableBackup.attachments(row)) {
                                if (ref.startsWith("mms-local:") && installed.add(ref)) {
                                    net.kourlas.voipms_sms.privacy.PortableBackup.installMedia(
                                        context, zip, ref.removePrefix("mms-local:"))?.let(created::add)
                                }
                            }
                            dao.insert(row)
                            added++
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            created.forEach { it.delete() }
            throw e
        }
        added
    }

    internal suspend fun purgeExpiredCodes(cutoff: Long) = importExportLock.read {
        var after = 0L
        while (true) {
            val page = database.smsDao().codeRetentionCandidates(cutoff, after)
            if (page.isEmpty()) break
            database.withTransaction {
                for (row in page) {
                    if (net.kourlas.voipms_sms.privacy.PortableBackup.attachments(row).isEmpty() &&
                        net.kourlas.voipms_sms.sms.VerificationCode.eligibleForRetention(row.text)) {
                        row.voipId?.let { insertVoipIdDeleted(row.did, it) }
                        database.smsDao().deleteById(row.databaseId)
                    }
                }
            }
            after = page.last().databaseId
        }
    }

    suspend fun getDids(): Set<String> =
        importExportLock.read {
            database.smsDao().getDids().toSet()
        }

    /**
     * Retrieves the message with the specified database ID from the database.
     *
     * @return Null if the message does not exist.
     */
    internal suspend fun searchMessages(query: androidx.sqlite.db.SupportSQLiteQuery) =
        importExportLock.read { database.messageSearchDao().search(query) }

    suspend fun setSearchOcr(databaseId: Long, text: String, expectedMedia: List<String>? = null) = withContext(Dispatchers.IO) {
        importExportLock.read {
            database.withTransaction {
                val msg = database.messageSearchDao().search(androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT * FROM sms WHERE DatabaseId=?", arrayOf(databaseId))).firstOrNull()
                if (msg == null || (expectedMedia != null && msg.toMessage().medias != expectedMedia) ||
                    net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(context, ConversationId(msg.did, msg.contact))) {
                    false
                } else {
                    database.openHelper.writableDatabase.execSQL(
                        "UPDATE message_search SET ocr=? WHERE rowid=?", arrayOf<Any>(text, databaseId))
                    true
                }
            }
        }
    }

    internal suspend fun clearSearchOcr(id: ConversationId) = withContext(Dispatchers.IO) {
        importExportLock.read {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE message_search SET ocr='' WHERE rowid IN (SELECT DatabaseId FROM sms WHERE Did=? AND Contact=?)",
                arrayOf(id.did, id.contact))
        }
    }

    internal suspend fun messagesAtAnchor(id: ConversationId, databaseId: Long): List<Message> = importExportLock.read {
        val dao = database.messageSearchDao()
        val anchor = dao.search(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT * FROM sms WHERE Did=? AND Contact=? AND DatabaseId=?",
            arrayOf<Any>(id.did, id.contact, databaseId))).firstOrNull() ?: return@read emptyList()
        val bindings: Array<Any> = arrayOf(id.did, id.contact, anchor.date, anchor.date, databaseId)
        val preceding = dao.search(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT * FROM sms WHERE Did=? AND Contact=? AND (Date<? OR (Date=? AND DatabaseId<?)) ORDER BY Date DESC,DatabaseId DESC LIMIT 25",
            bindings))
        val following = dao.search(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT * FROM sms WHERE Did=? AND Contact=? AND (Date>? OR (Date=? AND DatabaseId>?)) ORDER BY Date,DatabaseId LIMIT 25",
            bindings))
        (preceding.asReversed() + anchor + following).map { it.toMessage() }
    }

    suspend fun getMessageDatabaseId(databaseId: Long): Message? =
        importExportLock.read {
            database.smsDao().getById(databaseId)?.toMessage()
        }

    suspend fun checkedThrough(accountKey: String, did: String, startDay: String): String? =
        importExportLock.read { database.syncCheckpointDao().get(accountKey, did, startDay) }

    /**
     * Imports the database from the specified file descriptor. Checks the
     * file before it replaces the current database. If Room cannot open the
     * imported database, restores the current database.
     *
     * @throws DatabaseImportException If the file is not a database, is
     * damaged, or is from an unsupported schema version.
     */
    suspend fun import(importFd: ParcelFileDescriptor) = coroutineScope {
        importExportLock.write {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val backupFile = File("${dbFile.absolutePath}.backup")
            val importFile = File(context.cacheDir, IMPORT_FILE_NAME)

            try {
                // Copy the file to a temporary file and check it before the
                // current database changes.
                FileInputStream(importFd.fileDescriptor).use { input ->
                    importFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                checkImportFile(importFile)

                // Replace the current database, but keep a backup of it.
                database.close()
                dbFile.copyTo(backupFile, overwrite = true)
                try {
                    // deleteDatabase also deletes the -journal, -shm and -wal
                    // files of the current database.
                    context.deleteDatabase(DATABASE_NAME)
                    importFile.copyTo(dbFile)
                    database = createDatabase(context)

                    // Room opens the file only when it is first used. Open it
                    // here, so that the migration and the schema check run
                    // while the backup still exists.
                    database.openHelper.writableDatabase
                    // Restoring someone else's backup never authorizes its future transmissions.
                    database.withTransaction {
                        database.outboxDao().clear()
                        database.smsDao().resetUntrackedSends()
                    }
                } catch (e: Exception) {
                    database.close()
                    context.deleteDatabase(DATABASE_NAME)
                    backupFile.copyTo(dbFile)
                    database = createDatabase(context)
                    throw e
                }
            } finally {
                importFile.delete()
                backupFile.delete()
            }
        }
    }

    /**
     * Checks that the specified file is an undamaged SQLite database with a
     * schema version that can be migrated to the current version. Uses the
     * framework SQLite API rather than Room, so that the check does not
     * change the file.
     *
     * @throws DatabaseImportException If the check fails.
     */
    private fun checkImportFile(file: File) {
        val version = try {
            SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                val result = db.rawQuery("PRAGMA quick_check", null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (result != "ok") {
                    throw DatabaseImportException(
                        context.getString(
                            R.string.preferences_database_import_fail_damaged
                        )
                    )
                }
                db.version
            }
        } catch (e: SQLiteException) {
            throw DatabaseImportException(
                context.getString(
                    R.string.preferences_database_import_fail_damaged
                ), e
            )
        }
        if (version !in MIN_IMPORT_DATABASE_VERSION..DATABASE_VERSION) {
            throw DatabaseImportException(
                context.getString(
                    R.string.preferences_database_import_fail_version
                )
            )
        }
    }

    /**
     * Inserts new outgoing messages into the database with the specified
     * conversation ID and text. This message is marked as in the process of
     * being delivered.
     *
     * @return The database IDs of the inserted message.
     */
    suspend fun insertConversationMessagesDeliveryInProgress(
        conversationId: ConversationId,
        texts: List<String>,
        media: List<String> = emptyList(),
        outbox: net.kourlas.voipms_sms.database.entities.OutboxPart? = null
    ): List<Long> = coroutineScope {
        val messages = importExportLock.read {
            val smses = texts.map {
                Sms(
                    did = conversationId.did,
                    contact = conversationId.contact,
                    text = it,
                    mediaJson = net.kourlas.voipms_sms.media.AttachmentList.encode(media),
                    deliveryInProgress = 1
                )
            }
            database.withTransaction {
                smses.map {
                    // No need to take the insertion lock, since there is no
                    // chance these messages already exist in the database.
                    val databaseId = database.smsDao().insert(it)
                    if (outbox != null) database.outboxDao().add(outbox.copy(messageId = databaseId))
                    it.toMessage(databaseId)
                }
            }
        }

        launch {
            updateShortcuts()
        }

        messages.map { it.databaseId }
    }

    /**
     * Inserts messages originally taken from the database.
     */
    suspend fun insertMessagesDatabase(messages: List<Message>) =
        coroutineScope {
            importExportLock.read {
                database.withTransaction {
                    for (message in messages) {
                        val sms = Sms(
                            voipId = message.voipId,
                            date = message.date.time / 1000L,
                            incoming = if (message.isIncoming) 1L else 0L,
                            did = message.did,
                            contact = message.contact,
                            text = message.text,
                            unread = if (message.isUnread) 1L else 0L,
                            delivered = if (message.isDelivered) 1L else 0L,
                            deliveryInProgress = if (message.isDeliveryInProgress)
                                1L else 0L,
                            media1 = message.media1,
                            media2 = message.media2,
                            media3 = message.media3,
                            mediaJson = net.kourlas.voipms_sms.media.AttachmentList.encode(message.medias),
                        )

                        // Don't add the message if it already exists in our
                        // database.
                        if (message.voipId == null
                            || database.smsDao().getIdByVoipId(
                                message.did, message.voipId
                            ) == null
                        ) {
                            database.smsDao().insert(sms)
                        }
                    }
                }
            }

            launch {
                updateShortcuts()
            }
        }

    /**
     * Inserts new messages from the VoIP.ms API.
     *
     * @param retrieveDeletedMessages If true, then any existing messages that
     * have the same VoIP.ms ID as a message in the list of new messages are
     * marked as not deleted.
     * @return The conversation IDs associated with the newly added messages.
     */
    suspend fun insertMessagesVoipMsApi(
        incomingMessages: List<IncomingMessage>,
        retrieveDeletedMessages: Boolean,
        checkpoint: SyncCheckpoint?,
        previousThrough: String?
    ): Set<ConversationId> =
        coroutineScope {
            val (addedMessages, addedConversationIds) = importExportLock.read {
                val addedConversationIds = mutableSetOf<ConversationId>()
                val addedMessages = mutableListOf<Message>()

                database.withTransaction {
                    for (incomingMessage in incomingMessages) {
                        if (retrieveDeletedMessages) {
                            // Retrieve deleted messages is true, so we should
                            // remove this message from our list of deleted
                            // messages.
                            database.deletedDao()
                                .delete(
                                    incomingMessage.did,
                                    incomingMessage.voipId
                                )
                        } else if (database.deletedDao().get(
                                incomingMessage.did,
                                incomingMessage.voipId
                            ) != null
                        ) {
                            // Retrieve deleted messages is not true and this
                            // message has been previously deleted, so we
                            // shouldn't add it back
                            continue
                        }

                        val databaseId = database.smsDao().getIdByVoipId(
                            incomingMessage.did, incomingMessage.voipId
                        )
                        if (databaseId != null) {
                            if (incomingMessage.medias.isNotEmpty()) {
                                database.smsDao().updateAttachments(databaseId,
                                    net.kourlas.voipms_sms.media.AttachmentList.encode(incomingMessage.medias))
                            }
                            continue
                        }

                        val sms = Sms(
                            voipId = incomingMessage.voipId,
                            date = incomingMessage.date.time / 1000L,
                            incoming = if (incomingMessage.isIncoming)
                                1L else 0L,
                            did = incomingMessage.did,
                            contact = incomingMessage.contact,
                            text = incomingMessage.text ?: "",
                            media1 = incomingMessage.media1 ?: "",
                            media2 = incomingMessage.media2 ?: "",
                            media3 = incomingMessage.media3 ?: "",
                            mediaJson = net.kourlas.voipms_sms.media.AttachmentList.encode(incomingMessage.medias),
                            unread = if (incomingMessage.isIncoming)
                                1L else 0L,
                            delivered = 1L,
                            deliveryInProgress = 0L
                        )
                        val newDatabaseId = database.smsDao().insert(sms)

                        addedConversationIds.add(
                            ConversationId(
                                incomingMessage.did,
                                incomingMessage.contact
                            )
                        )
                        addedMessages.add(sms.toMessage(newDatabaseId))

                        // Incoming activity reopens the thread while retaining its inbox folder.
                        database.archivedDao().clearArchiveFlag(
                            incomingMessage.did, incomingMessage.contact
                        )
                    }

                    if (checkpoint != null) {
                        val cursors = database.syncCheckpointDao()
                        val stored = cursors.get(checkpoint.accountKey, checkpoint.did, checkpoint.startDay)
                        // An import or another worker may have changed the cursor since
                        // planning. Only advance from a range we know was covered.
                        if ((previousThrough == null || (stored != null && stored >= previousThrough)) &&
                            (stored == null || checkpoint.throughDay > stored)) {
                            cursors.save(checkpoint)
                        }
                    }
                }

                Pair(addedMessages, addedConversationIds)
            }

            launch {
                addedMessages.filter { it.isIncoming }.forEach {
                    net.kourlas.voipms_sms.tools.ThreadReminders.inbound(context, it.conversationId, it.date.time / 1000)
                }
                if (addedMessages.isNotEmpty()) {
                    updateShortcuts()
                }
            }

            addedConversationIds
        }

    /**
     * Returns whether the specified conversation is archived.
     */
    suspend fun isConversationArchived(
        conversationId: ConversationId
    ): Boolean =
        importExportLock.read {
            database.archivedDao().getConversation(
                conversationId.did, conversationId.contact
            )?.archived == 1
        }

    /**
     * Marks all delivery in progress messages as not sent.
     */
    suspend fun markAllDeliveryInProgressMessagesAsNotSent() {
        database.withTransaction {
            database.smsDao().markAllDeliveryInProgressMessagesAsNotSent()
        }
    }

    /**
     * Marks the specified conversation as archived.
     */
    suspend fun markConversationArchived(
        conversationId: ConversationId
    ) =
        importExportLock.read {
            database.withTransaction {
                val existingArchived = database.archivedDao()
                    .getConversation(conversationId.did, conversationId.contact)
                val newArchived =
                    Archived(
                        databaseId = existingArchived?.databaseId ?: 0,
                        did = conversationId.did,
                        contact = conversationId.contact, archived = 1, otherFolder = existingArchived?.otherFolder
                    )
                database.archivedDao().update(newArchived)
            }
        }

    /**
     * Marks the specified conversation as unarchived.
     */
    suspend fun markConversationUnarchived(
        conversationId: ConversationId
    ) =
        importExportLock.read {
            database.withTransaction {
                val existingArchived = database.archivedDao()
                    .getConversation(conversationId.did, conversationId.contact)
                val newArchived =
                    Archived(
                        databaseId = existingArchived?.databaseId ?: 0,
                        did = conversationId.did,
                        contact = conversationId.contact, archived = 0, otherFolder = existingArchived?.otherFolder
                    )
                database.archivedDao().update(newArchived)
            }
        }

    suspend fun moveToInboxFolder(ids: List<ConversationId>, other: Boolean) = importExportLock.read {
        database.withTransaction {
            val dao = database.archivedDao()
            for (id in ids) {
                val old = dao.getConversation(id.did, id.contact)
                dao.update(Archived(old?.databaseId ?: 0, id.did, id.contact, old?.archived ?: 0, other))
            }
        }
    }

    /**
     * Marks the specified conversation as read.
     **/
    suspend fun markConversationRead(
        conversationId: ConversationId
    ) =
        importExportLock.read {
            database.smsDao().markConversationRead(
                conversationId.did,
                conversationId.contact
            )
        }

    /**
     * Marks the specified conversation as unread.
     **/
    suspend fun markConversationUnread(conversationId: ConversationId) =
        importExportLock.read {
            database.smsDao().markConversationUnread(
                conversationId.did,
                conversationId.contact
            )
        }

    /**
     * Marks the message with the specified database ID as in the process of
     * being delivered.
     */
    suspend fun markMessageDeliveryInProgress(databaseId: Long) =
        importExportLock.read {
            database.smsDao().markMessageDeliveryInProgress(databaseId)
        }

    /**
     * Marks the message with the specified database ID as having failed to
     * be sent.
     */
    suspend fun markMessageNotSent(databaseId: Long) =
        importExportLock.read {
            database.smsDao().markMessageNotSent(databaseId)
        }

    /**
     * Marks the message with the specified database ID as having been sent.
     * In addition, adds the specified VoIP.ms ID to the message.
     */
    suspend fun markMessageSent(databaseId: Long, voipId: Long) =
        importExportLock.read {
            database.withTransaction {
                val dao = database.smsDao()
                val sent = dao.getById(databaseId) ?: return@withTransaction
                // A concurrent sync can see the outgoing provider record before
                // the send response arrives. Keep the row used by the composer.
                val syncedId = dao.getIdByVoipId(sent.did, voipId)
                if (syncedId != null && syncedId != databaseId) dao.deleteById(syncedId)
                dao.markMessageSent(databaseId, voipId, Date().time / 1000L)
            }
        }

    /**
     * Updates the draft message associated with the specified conversation
     * and containing the specified text.
     *
     * Any existing draft message with the specified conversation is
     * automatically removed. If an empty message is inserted, any existing
     * message is removed from the database.
     */
    suspend fun updateConversationDraft(
        conversationId: ConversationId,
        text: String
    ) =
        importExportLock.read {
            database.withTransaction {
                updateConversationDraftWithoutLock(conversationId, text)
            }
        }

    /**
     * Update the app shortcuts.
     */
    private val shortcutLock = kotlinx.coroutines.sync.Mutex()

    suspend fun updateShortcuts() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return@withContext
        shortcutLock.withLock {
            val dids = net.kourlas.voipms_sms.preferences.getDids(context, onlyShowInConversationsView = true)
            val names = mutableMapOf<String, String>()
            val rows = if (BuildConfig.IS_DEMO) {
                listOf(InboxPreview(getDemoNotification(), false, null, true))
            } else inboxPreviews(dids, "", false, names)
            val phrases = net.kourlas.voipms_sms.privacy.SpamReview.phrases(context)
            val eligible = rows.filter { row ->
                val id = row.message.conversationId
                if (!ShareSuggestions.enabled(context, id) || PrivateThreads.isPrivate(context, id) ||
                    row.otherFolder == true || row.latestIncoming?.let(VerificationCode::extract) != null) {
                    return@filter false
                }
                // Query Contacts only if the text and lack of replies would classify this thread as junk.
                row.otherFolder == false || InboxRules.reason(row.latestIncoming, row.hasReply, false, phrases) == null ||
                    getContactName(context, id.contact, names) != null
            }.map { it.message }
            val eligibleIds = eligible.mapTo(hashSetOf()) { it.conversationId.getId() }
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.id != "new_conversation" }
            val cached = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_CACHED)
            val staleDynamic = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
                .map { it.id }.filterNot(eligibleIds::contains)
            if (staleDynamic.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, staleDynamic)
            val stalePins = pinned.map { it.id }.filterNot(eligibleIds::contains)
            val staleCached = cached.map { it.id }.filterNot(eligibleIds::contains)
            if (stalePins.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, stalePins, context.getString(R.string.pinned_shortcut_disable_error))
            }
            if (staleCached.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, staleCached)

            val limit = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - 1).coerceAtLeast(0)
            val dynamicIds = eligible.take(limit).mapTo(hashSetOf()) { it.conversationId.getId() }
            val existing = (pinned + cached).mapTo(hashSetOf()) { it.id }
            // Only render bitmaps for shortcuts the OS can actually display.
            val shortcuts = eligible.filter { it.conversationId.getId() in dynamicIds || it.conversationId.getId() in existing }
                .mapIndexed { rank, message ->
                    val id = message.conversationId
                    val name = getContactName(context, id.contact, names)
                    val peer = name ?: getFormattedPhoneNumber(id.contact)
                    val line = LineProfiles.label(context, id.did)
                    val shortLine = LineProfiles.name(context, id.did) ?: id.did.takeLast(4)
                    val icon = IconCompat.createWithAdaptiveBitmap(getContactPhotoAdaptiveBitmap(context, name, id.contact))
                    val target = Intent(context, ConversationActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .putExtra(context.getString(R.string.conversation_did), id.did)
                        .putExtra(context.getString(R.string.conversation_contact), id.contact)
                    ShortcutInfoCompat.Builder(context, id.getId())
                        .setIcon(icon).setIntent(target)
                        .setPerson(Person.Builder().setName(peer).setKey(id.contact).setIcon(icon).setUri("tel:${id.contact}").build())
                        .setLongLabel(context.getString(R.string.line_conversation_label, peer, line))
                        .setShortLabel(context.getString(R.string.line_conversation_label, shortLine, peer))
                        .setLongLived(true).setLocusId(LocusIdCompat(id.getId()))
                        .setCategories(setOf("existing_conversation_target")).setRank(rank).build()
                }
            val reenabled = pinned.filter { !it.isEnabled && it.id in eligibleIds }.mapTo(hashSetOf()) { it.id }
            if (reenabled.isNotEmpty()) {
                ShortcutManagerCompat.enableShortcuts(context, shortcuts.filter { it.id in reenabled })
            }
            try {
                if (existing.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, shortcuts.filter { it.id in existing })
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.filter { it.id in dynamicIds })
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed to update shortcuts", e)
            }
        }
        Unit
    }

    /**
     * Gets all conversation IDs in the database associated with the specified
     * DIDs.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private suspend fun getConversationIdsWithoutLock(
        dids: Set<String>
    ): Set<ConversationId> {
        return database.smsDao().getConversationIds(dids).toSet()
    }

    /**
     * Gets all messages in a specified conversation. The resulting list is not
     * sorted.s
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private suspend fun getConversationMessagesUnsortedWithoutLock(
        conversationId: ConversationId
    ): List<Message> {
        return database.smsDao()
            .getConversationMessagesUnsorted(
                conversationId.did,
                conversationId.contact
            )
            .map { it.toMessage() }
    }

    /**
     * Gets the draft messages for all conversations associated with the
     * specified DIDs.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private suspend fun getConversationsMessageDraft(
        dids: Set<String>
    ): List<Message> {
        return database.draftDao()
            .getConversations(dids)
            .map { it.toMessage() }
    }

    /**
     * Gets the most recent draft message in each conversation associated
     * with the specified DIDs that matches a specified filter constraint.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private suspend fun getConversationsMessageDraftFiltered(
        dids: Set<String>, filterConstraint: String
    ): List<Message> {
        return getConversationsMessageDraft(dids)
            .filter {
                it.text
                    .lowercase(Locale.getDefault())
                    .contains(filterConstraint)
            }
            .toMutableList()
    }

    /**
     * Gets the most recent message in each conversation associated with the
     * specified DIDs that matches a specified filter constraint. The resulting
     * list is sorted by date, from most recent to least recent.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private suspend fun getConversationsMessageMostRecentFilteredWithoutLock(
        dids: Set<String>,
        filterConstraint: String = "",
        contactNameCache: MutableMap<String, String>? = null,
        current: Map<ConversationId, Sms>? = null
    ): List<Message> {
        val latest = current ?: database.smsDao().latestForDids(dids, "", "")
            .associateBy { ConversationId(it.did, it.contact) }
        val messages = mutableMapOf<ConversationId, Message>()
        if (filterConstraint.isEmpty()) {
            latest.forEach { (id, sms) -> messages[id] = sms.toMessage() }
        } else {
            val matches = database.smsDao().latestForDids(dids, filterConstraint, getDigitsOfString(filterConstraint))
            for (sms in matches) messages[ConversationId(sms.did, sms.contact)] = sms.toMessage()
            for ((id, sms) in latest) {
                if (id !in messages && getContactName(context, sms.contact, contactNameCache)
                        ?.contains(filterConstraint, ignoreCase = true) == true) {
                    messages[id] = sms.toMessage()
                }
            }
        }
        for (draft in getConversationsMessageDraftFiltered(dids, filterConstraint)) {
            messages[draft.conversationId] = draft
        }
        return messages.values.sorted()
    }

    /**
     * Inserts the deleted VoIP.ms message ID associated with the specified
     * DID into the database.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private suspend fun insertVoipIdDeleted(did: String, voipId: Long) {
        if (database.deletedDao().get(did, voipId) != null) {
            // VoIP.ms ID and DID already in table
            return
        }
        database.deletedDao().insert(Deleted(did = did, voipId = voipId))
    }

    /**
     * Updates the draft message associated with the specified conversation
     * and containing the specified text.
     *
     * Any existing draft message with the specified conversation is
     * automatically removed. If an empty message is inserted, any existing
     * message is removed from the database.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private suspend fun updateConversationDraftWithoutLock(
        conversationId: ConversationId,
        text: String
    ) {
        // If text is empty, then just delete any existing draft message.
        if (text == "") {
            database.draftDao().deleteConversation(
                conversationId.did,
                conversationId.contact
            )
            return
        }

        // Otherwise, update the draft message, if any.
        val existingDraft = database.draftDao().getConversation(
            conversationId.did, conversationId.contact
        )
        val newDraft = Draft(
            databaseId = existingDraft?.databaseId ?: 0,
            did = conversationId.did,
            contact = conversationId.contact,
            text = text
        )
        database.draftDao().update(newDraft)
    }

    companion object {
        private const val DATABASE_NAME = "sms.db"
        private const val IMPORT_FILE_NAME = "import.db"
        const val DATABASE_VERSION = 14

        // The oldest schema version with a migration path to
        // DATABASE_VERSION.
        private const val MIN_IMPORT_DATABASE_VERSION = 9

        private val migration9To10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Do nothing. The database did not change when we switched
                // to Room.
            }
        }

        // It is not a leak to store an instance to the Application object,
        // since it has the same lifetime as the application itself
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Database? = null

        /**
         * Gets the sole instance of the Database class. Initializes the
         * instance if it does not already exist.
         */
        fun getInstance(context: Context): Database =
            instance ?: synchronized(this) {
                instance ?: Database(
                    context.applicationContext
                ).also { instance = it }
            }

        /**
         * Creates a RoomDatabase instance.
         */
        private fun createDatabase(context: Context): AbstractDatabase {
            return Room.databaseBuilder(
                context, AbstractDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(migration9To10)
                .addCallback(object : androidx.room.RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        net.kourlas.voipms_sms.tools.SearchIndex.install(db)
                    }
                })
                .build()
        }
    }
}
