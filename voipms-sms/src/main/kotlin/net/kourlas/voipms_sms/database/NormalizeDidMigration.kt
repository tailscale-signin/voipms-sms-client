package net.kourlas.voipms_sms.database

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid

class NormalizeDidMigration : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val tables = listOf("sms", "draft", "archived", "deleted")
        val aliases = db.query(tables.joinToString(" UNION ") { "SELECT Did FROM $it" }).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        for (alias in aliases) {
            val did = normalizeVoipMsDid(alias)
            if (alias == did) continue
            // The editor has one draft per conversation. Keep both texts if two
            // historical spellings of this line had different unsent drafts.
            db.execSQL("""
                UPDATE draft SET Text = Text || char(10,10) ||
                    (SELECT old.Text FROM draft old WHERE old.Did = ? AND old.Contact = draft.Contact LIMIT 1)
                WHERE Did = ? AND EXISTS (SELECT 1 FROM draft old WHERE old.Did = ?
                    AND old.Contact = draft.Contact AND old.Text != draft.Text)
            """.trimIndent(), arrayOf(alias, did, alias))
            db.execSQL("""
                DELETE FROM draft WHERE Did = ? AND EXISTS (
                    SELECT 1 FROM draft current WHERE current.Did = ? AND current.Contact = draft.Contact)
            """.trimIndent(), arrayOf(alias, did))
            for (table in tables) {
                db.execSQL("UPDATE $table SET Did = ? WHERE Did = ?", arrayOf(did, alias))
            }
        }
        // Alias normalization and overlapping legacy sends may leave multiple
        // rows for one provider ID. A null provider ID is a local pending send.
        db.execSQL("""
            CREATE TEMP TABLE merged_sms AS
            SELECT Did, VoipId, MIN(DatabaseId) AS keepId, MIN(Unread), MAX(Delivered),
                   MAX(Text), MAX(Media1), MAX(Media2), MAX(Media3)
            FROM sms WHERE VoipId IS NOT NULL GROUP BY Did, VoipId HAVING COUNT(*) > 1
        """.trimIndent())
        try {
            db.query("SELECT * FROM temp.merged_sms").use { rows ->
                while (rows.moveToNext()) {
                    // Preserve the oldest local row ID, its nonempty content and
                    // the read state. Fill missing content from the provider echo.
                    val keep = rows.getLong(2)
                    db.execSQL("""
                        UPDATE sms SET Unread = ?, Delivered = ?,
                        Text = COALESCE(NULLIF(Text,''), ?), Media1 = COALESCE(NULLIF(Media1,''), ?),
                        Media2 = COALESCE(NULLIF(Media2,''), ?), Media3 = COALESCE(NULLIF(Media3,''), ?)
                        WHERE DatabaseId = ?
                    """.trimIndent(), arrayOf(rows.getLong(3), rows.getLong(4), rows.getString(5),
                        rows.getString(6), rows.getString(7), rows.getString(8), keep))
                    db.execSQL("DELETE FROM sms WHERE Did = ? AND VoipId = ? AND DatabaseId != ?",
                        arrayOf(rows.getString(0), rows.getLong(1), keep))
                }
            }
        } finally {
            db.execSQL("DROP TABLE temp.merged_sms")
        }
    }
}
