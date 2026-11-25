package net.kourlas.voipms_sms.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import net.kourlas.voipms_sms.database.entities.OutboxPart

@Dao
interface OutboxDao {
    @Insert
    suspend fun add(part: OutboxPart)

    @Query("SELECT * FROM OutboxPart WHERE batch = :batch ORDER BY messageId")
    suspend fun parts(batch: String): List<OutboxPart>

    @Query("SELECT * FROM OutboxPart WHERE messageId = :id")
    suspend fun byId(id: Long): OutboxPart?

    @Query("SELECT o.* FROM OutboxPart o JOIN sms s ON s.DatabaseId = o.messageId WHERE s.Did = :did AND s.Contact = :contact AND o.generation = :generation AND EXISTS (SELECT 1 FROM OutboxPart pending WHERE pending.batch = o.batch AND pending.state != 'accepted') ORDER BY o.dueAt, o.messageId")
    suspend fun forThread(did: String, contact: String, generation: Long): List<OutboxPart>

    @Query("SELECT o.* FROM OutboxPart o JOIN sms s ON s.DatabaseId=o.messageId WHERE s.Did=:did AND s.Contact=:contact AND o.state != 'accepted'")
    suspend fun forMessages(did: String, contact: String): List<OutboxPart>

    @Query("SELECT o.* FROM OutboxPart o WHERE o.generation=:generation AND EXISTS (SELECT 1 FROM OutboxPart pending WHERE pending.batch = o.batch AND pending.state != 'accepted') ORDER BY o.dueAt, o.messageId")
    suspend fun forAccount(generation: Long): List<OutboxPart>

    @Query("SELECT s.VoipId FROM sms s JOIN OutboxPart o ON o.messageId=s.DatabaseId WHERE o.state='accepted' AND s.Did=:did AND s.VoipId IN (:ids)")
    suspend fun claimedIds(did: String, ids: List<Long>): List<Long>

    @Query("SELECT * FROM OutboxPart WHERE state IN ('queued','attempting') ORDER BY messageId")
    suspend fun active(): List<OutboxPart>

    @Query("UPDATE OutboxPart SET state='attempting', attemptAt=:now WHERE messageId=:id AND batch=:batch AND state='queued' AND dueAt<=:now")
    suspend fun claim(batch: String, id: Long, now: Long): Int

    @Query("UPDATE OutboxPart SET state=:state, reason=:reason WHERE messageId=:id AND batch=:batch")
    suspend fun update(batch: String, id: Long, state: String, reason: String?): Int

    @Query("DELETE FROM OutboxPart WHERE batch=:batch")
    suspend fun remove(batch: String)

    @Query("DELETE FROM OutboxPart")
    suspend fun clear()
}
