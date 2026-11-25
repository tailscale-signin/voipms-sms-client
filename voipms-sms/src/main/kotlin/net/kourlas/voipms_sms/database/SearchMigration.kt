package net.kourlas.voipms_sms.database

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import net.kourlas.voipms_sms.tools.SearchIndex

class SearchMigration : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) = SearchIndex.install(db)
}
