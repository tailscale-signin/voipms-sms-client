package net.kourlas.voipms_sms.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.kourlas.voipms_sms.database.entities.SyncCheckpoint

@Dao
interface SyncCheckpointDao {
    @Query("SELECT throughDay FROM sync_checkpoint WHERE accountKey = :account AND did = :did AND startDay = :start")
    suspend fun get(account: String, did: String, start: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(checkpoint: SyncCheckpoint)

    @Query("DELETE FROM sync_checkpoint WHERE did NOT IN (:dids)")
    suspend fun deleteWithoutDids(dids: Set<String>)

    @Query("DELETE FROM sync_checkpoint")
    suspend fun clear()
}
