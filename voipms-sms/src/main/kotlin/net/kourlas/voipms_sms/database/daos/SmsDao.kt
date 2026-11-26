/*
 * VoIP.ms SMS
 * Copyright (C) 2021 Michael Kourlas
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

package net.kourlas.voipms_sms.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import net.kourlas.voipms_sms.database.entities.Sms
import net.kourlas.voipms_sms.sms.ConversationId

@Dao
interface SmsDao {
    @Query("UPDATE sms SET MediaJson = :media WHERE DatabaseId = :id AND MediaJson != :media")
    suspend fun updateAttachments(id: Long, media: String)

    @Query("DELETE FROM ${Sms.TABLE_NAME}")
    suspend fun deleteAll()

    @Query(
        "DELETE FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DATABASE_ID} = :id"
    )
    suspend fun deleteById(id: Long)

    @Query(
        "DELETE FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact"
    )
    suspend fun deleteConversation(did: String, contact: String)

    @Query(
        "DELETE FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} NOT IN(:dids)"
    )
    suspend fun deleteWithoutDids(dids: Set<String>)

    @Query(
        "SELECT * FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DATABASE_ID} = :id"
    )
    suspend fun getById(id: Long): Sms?

    @Query(
        "SELECT DISTINCT ${Sms.COLUMN_CONTACT}, ${Sms.COLUMN_DID} FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} IN(:dids)"
    )
    suspend fun getConversationIds(dids: Set<String>): List<ConversationId>

    @Query(
        "SELECT * FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact AND ${Sms.COLUMN_TEXT} LIKE '%' || :filterConstraint || '%' ORDER BY ${Sms.COLUMN_DELIVERY_IN_PROGRESS} DESC, ${Sms.COLUMN_DATE} DESC, ${Sms.COLUMN_VOIP_ID} DESC, ${Sms.COLUMN_DATABASE_ID} DESC"
    )
    suspend fun getConversationMessagesFiltered(
        did: String, contact: String,
        filterConstraint: String
    ): List<Sms>

    @Query(
        "SELECT COUNT(*) FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact AND ${Sms.COLUMN_TEXT} LIKE '%' || :filterConstraint || '%' ORDER BY ${Sms.COLUMN_DELIVERY_IN_PROGRESS} DESC, ${Sms.COLUMN_DATE} DESC, ${Sms.COLUMN_VOIP_ID} DESC, ${Sms.COLUMN_DATABASE_ID} DESC"
    )
    suspend fun getConversationMessagesFilteredCount(
        did: String,
        contact: String,
        filterConstraint: String
    ): Long

    @Query(
        "SELECT * FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact AND ${Sms.COLUMN_TEXT} LIKE '%' || :filterConstraint || '%' ORDER BY ${Sms.COLUMN_DELIVERY_IN_PROGRESS} DESC, ${Sms.COLUMN_DATE} DESC, ${Sms.COLUMN_VOIP_ID} DESC, ${Sms.COLUMN_DATABASE_ID} DESC LIMIT :itemLimit"
    )
    suspend fun getConversationMessagesFilteredWithLimit(
        did: String,
        contact: String,
        filterConstraint: String,
        itemLimit: Long
    ): List<Sms>

    @Query(
        "SELECT * FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact AND ${Sms.COLUMN_INCOMING} = 1 AND ${Sms.COLUMN_DATE} >= :date AND ${Sms.COLUMN_UNREAD} = 1 ORDER BY ${Sms.COLUMN_DELIVERY_IN_PROGRESS} ASC, ${Sms.COLUMN_DATE} ASC, ${Sms.COLUMN_VOIP_ID} ASC, ${Sms.COLUMN_DATABASE_ID} ASC"
    )
    suspend fun getConversationMessagesUnreadAfterDate(
        did: String,
        contact: String,
        date: Long
    ): List<Sms>

    @Query(
        "SELECT * FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact"
    )
    suspend fun getConversationMessagesUnsorted(
        did: String,
        contact: String
    ): List<Sms>

    @Query("SELECT DISTINCT Did, Contact FROM sms")
    suspend fun portableConversationIds(): List<ConversationId>

    @Query("""
        SELECT * FROM sms WHERE Did = :did AND Contact = :contact
        AND DatabaseId >= :first ORDER BY DatabaseId LIMIT :limit
    """)
    suspend fun portablePage(did: String, contact: String, first: Long, limit: Int): List<Sms>

    @Query("""
        SELECT * FROM sms WHERE Did = :did AND Contact = :contact AND Date >= :afterDate
        AND (:first OR Date > :afterDate OR DatabaseId > :afterId)
        ORDER BY Date, DatabaseId LIMIT :limit
    """)
    suspend fun threadExportPage(
        did: String, contact: String, afterDate: Long, afterId: Long, first: Boolean, limit: Int
    ): List<Sms>

    // The covering index provides an ordered seek per thread on every supported SQLite version.
    @Query("""
        SELECT sms.* FROM (SELECT DISTINCT Did, Contact FROM sms WHERE Did IN (:dids)) AS threads
        JOIN sms ON sms.DatabaseId = (
            SELECT m.DatabaseId FROM sms AS m WHERE m.Did = threads.Did AND m.Contact = threads.Contact
            AND (:filter = '' OR m.Text LIKE '%' || :filter || '%' OR (:digits != '' AND
                 (m.Did LIKE '%' || :digits || '%' OR m.Contact LIKE '%' || :digits || '%')))
            ORDER BY m.DeliveryInProgress DESC, m.Date DESC, m.VoipId DESC, m.DatabaseId DESC LIMIT 1
        )
    """)
    suspend fun latestForDids(dids: Set<String>, filter: String, digits: String): List<Sms>

    @Query("SELECT * FROM sms WHERE Did = :did AND Contact = :contact ORDER BY DeliveryInProgress DESC, Date DESC, VoipId DESC, DatabaseId DESC LIMIT 1")
    suspend fun latestInThread(did: String, contact: String): Sms?

    data class ThreadState(val did: String, val contact: String, val unread: Boolean, val hasReply: Boolean, val latestIncoming: String?)

    @Query("""
        SELECT Did AS did, Contact AS contact, MAX(Unread) AS unread, MIN(Type) = 0 AS hasReply,
            (SELECT incoming.Text FROM sms incoming
             WHERE incoming.Did = thread.Did AND incoming.Contact = thread.Contact AND incoming.Type = 1
             ORDER BY incoming.DeliveryInProgress DESC, incoming.Date DESC, incoming.VoipId DESC, incoming.DatabaseId DESC LIMIT 1) AS latestIncoming
        FROM sms thread WHERE Did IN (:dids) GROUP BY Did, Contact
    """)
    suspend fun threadStates(dids: Set<String>): List<ThreadState>

    @Query(
        "SELECT COALESCE(MAX(${Sms.COLUMN_DATE}), 0) AS ${Sms.COLUMN_DATE} FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact AND ${Sms.COLUMN_INCOMING} = 0"
    )
    suspend fun getConversationMessageDateMostRecentOutgoing(
        did: String,
        contact: String
    ): Long?

    @Query(
        "SELECT ${Sms.COLUMN_DATABASE_ID} FROM ${Sms.TABLE_NAME} WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_VOIP_ID} = :voipId"
    )
    suspend fun getIdByVoipId(did: String, voipId: Long): Long?

    @Query("SELECT * FROM sms WHERE Did = :did AND Contact = :contact AND VoipId IS NULL")
    suspend fun localOnlyMessages(did: String, contact: String): List<Sms>

    @Query("SELECT * FROM sms WHERE Type = 1 AND Date < :cutoff AND DatabaseId > :after ORDER BY DatabaseId LIMIT 200")
    suspend fun codeRetentionCandidates(cutoff: Long, after: Long): List<Sms>

    @Query("SELECT DISTINCT ${Sms.COLUMN_DID} FROM ${Sms.TABLE_NAME}")
    suspend fun getDids(): List<String>

    @Query("UPDATE sms SET DeliveryInProgress=0 WHERE DeliveryInProgress=1 AND DatabaseId NOT IN (SELECT messageId FROM OutboxPart)")
    suspend fun resetUntrackedSends()

    @Insert
    suspend fun insert(sms: Sms): Long

    @Query(
        "UPDATE ${Sms.TABLE_NAME} SET ${Sms.COLUMN_UNREAD} = 0 WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact"
    )
    suspend fun markConversationRead(did: String, contact: String)

    @Query(
        "UPDATE ${Sms.TABLE_NAME} SET ${Sms.COLUMN_UNREAD} = 1 WHERE ${Sms.COLUMN_DID} = :did AND ${Sms.COLUMN_CONTACT} = :contact"
    )
    suspend fun markConversationUnread(did: String, contact: String)

    @Query(
        "UPDATE ${Sms.TABLE_NAME} SET ${Sms.COLUMN_DELIVERED} = 0, ${Sms.COLUMN_DELIVERY_IN_PROGRESS} = 1 WHERE ${Sms.COLUMN_DATABASE_ID} = :id"
    )
    suspend fun markMessageDeliveryInProgress(id: Long)

    @Query(
        "UPDATE ${Sms.TABLE_NAME} SET ${Sms.COLUMN_DELIVERED} = 0, ${Sms.COLUMN_DELIVERY_IN_PROGRESS} = 0 WHERE ${Sms.COLUMN_DATABASE_ID} = :id AND ${Sms.COLUMN_DELIVERY_IN_PROGRESS} = 1"
    )
    suspend fun markMessageNotSent(id: Long)

    @Query(
        "UPDATE ${Sms.TABLE_NAME} SET ${Sms.COLUMN_DELIVERED} = 0, ${Sms.COLUMN_DELIVERY_IN_PROGRESS} = 0 WHERE ${Sms.COLUMN_DELIVERY_IN_PROGRESS} = 1"
    )
    suspend fun markAllDeliveryInProgressMessagesAsNotSent()

    @Query(
        "UPDATE ${Sms.TABLE_NAME} SET ${Sms.COLUMN_VOIP_ID} = :voipId, ${Sms.COLUMN_DELIVERED} = 1, ${Sms.COLUMN_DELIVERY_IN_PROGRESS} = 0, ${Sms.COLUMN_DATE} = :date WHERE ${Sms.COLUMN_DATABASE_ID} = :id"
    )
    suspend fun markMessageSent(id: Long, voipId: Long, date: Long)
}