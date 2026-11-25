package net.kourlas.voipms_sms.database.entities

import androidx.room.Entity

/** Inclusive Eastern calendar dates, formatted yyyy-MM-dd for ordering in SQLite. */
@Entity(tableName = "sync_checkpoint", primaryKeys = ["accountKey", "did", "startDay"])
data class SyncCheckpoint(
    val accountKey: String,
    val did: String,
    val startDay: String,
    val throughDay: String
)
