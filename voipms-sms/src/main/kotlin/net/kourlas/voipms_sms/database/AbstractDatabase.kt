package net.kourlas.voipms_sms.database

import net.kourlas.voipms_sms.database.daos.SyncCheckpointDao
import net.kourlas.voipms_sms.database.entities.SyncCheckpoint
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import net.kourlas.voipms_sms.database.Database.Companion.DATABASE_VERSION
import net.kourlas.voipms_sms.database.daos.ArchivedDao
import net.kourlas.voipms_sms.database.daos.DeletedDao
import net.kourlas.voipms_sms.database.daos.DraftDao
import net.kourlas.voipms_sms.database.daos.SmsDao
import net.kourlas.voipms_sms.database.entities.Archived
import net.kourlas.voipms_sms.database.entities.Deleted
import net.kourlas.voipms_sms.database.entities.Draft
import net.kourlas.voipms_sms.database.entities.Sms

@Database(
    entities = [
        Sms::class,
        Archived::class,
        Draft::class,
        Deleted::class,
        SyncCheckpoint::class],
    autoMigrations = [
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12, spec = NormalizeDidMigration::class),
        AutoMigration(from = 12, to = 13)
    ],
    version = DATABASE_VERSION
)
abstract class AbstractDatabase : RoomDatabase() {
    abstract fun syncCheckpointDao(): SyncCheckpointDao
    abstract fun smsDao(): SmsDao
    abstract fun deletedDao(): DeletedDao
    abstract fun draftDao(): DraftDao
    abstract fun archivedDao(): ArchivedDao
}