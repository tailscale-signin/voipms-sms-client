package net.kourlas.voipms_sms.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import net.kourlas.voipms_sms.tools.SearchIndex
import org.json.JSONObject

/** Compare the staged database to the trusted Room schema for its declared version.
 * Never install a view, trigger, virtual module or expression index supplied by an archive.
 */
internal object ImportSchema {
    private data class Entry(val type: String, val table: String, val sql: String?)
    private val optional = setOf("android_metadata", "sqlite_stat1", "sqlite_stat4")
    private val autoIncrement = Regex("\\bAUTOINCREMENT\\b", RegexOption.IGNORE_CASE)
    private val unsupportedTableClause = Regex("\\b(CHECK|GENERATED|AS|COLLATE|UNIQUE|CONFLICT|DEFERRABLE|WITHOUT|STRICT)\\b|/\\*|--",
        RegexOption.IGNORE_CASE)

    fun matches(context: Context, input: SQLiteDatabase, version: Int): Boolean {
        // Version 9 predates Room. The 9→10 migration changed only the metadata.
        val schemaVersion = maxOf(10, version)
        val json = context.assets.open("net.kourlas.voipms_sms.database.AbstractDatabase/$schemaVersion.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        return SQLiteDatabase.create(null).use { trusted ->
            val entities = json.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val name = entity.getString("tableName")
                fun create(sql: String) = trusted.execSQL(sql.replace("\$" + "{TABLE_NAME}", name))
                create(entity.getString("createSql"))
                val indices = entity.optJSONArray("indices") ?: continue
                for (n in 0 until indices.length()) create(indices.getJSONObject(n).getString("createSql"))
            }
            val setup = json.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) trusted.execSQL(setup.getString(i))
            if (version >= 14) SearchIndex.triggers.forEach { trusted.execSQL(it) }
            trusted.execSQL("ANALYZE")
            val expected = catalog(trusted)
            val actual = catalog(input)
            val optionalNames = if (version == 9) optional + "room_master_table" else optional
            if (actual.keys - optionalNames != expected.keys - optionalNames) return false

            actual.all { (name, entry) ->
                val reference = expected[name] ?: return@all false
                if (entry.type != reference.type || entry.table != reference.table) return@all false
                // Executable and SQLite-owned objects must match SQLite's own rendering exactly.
                if (entry.type != "table" || name.startsWith("sqlite_") || name.startsWith("message_search") ||
                    name == "room_master_table" || name == "android_metadata") return@all entry.sql == reference.sql
                val sql = entry.sql ?: return@all false
                if (!sql.startsWith("CREATE TABLE ", ignoreCase = true) || unsupportedTableClause.containsMatchIn(sql)) return@all false
                if (autoIncrement.containsMatchIn(sql) != autoIncrement.containsMatchIn(reference.sql.orEmpty())) return@all false
                // Older app versions and ALTER TABLE migrations can differ in spacing, identifier
                // quoting and column order. Compare actual column definitions and foreign keys.
                tableColumns(input, name) == tableColumns(trusted, name) &&
                    foreignKeys(input, name) == foreignKeys(trusted, name)
            }
        }
    }

    private fun catalog(db: SQLiteDatabase): Map<String, Entry> =
        db.rawQuery("SELECT name,type,tbl_name,sql FROM sqlite_master", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), Entry(cursor.getString(1),
                    cursor.getString(2), if (cursor.isNull(3)) null else cursor.getString(3)))
            }
        }

    // name here was first matched to an app-owned schema entry.
    private fun tableColumns(db: SQLiteDatabase, name: String): Set<List<String?>> =
        db.rawQuery("PRAGMA table_info(`$name`)", null).use { c -> buildSet {
            while (c.moveToNext()) {
                val defaultValue = c.getString(4)?.takeUnless {
                    // Room added nullable columns using DEFAULT NULL on migrated installations.
                    c.getInt(3) == 0 && it.equals("NULL", ignoreCase = true)
                }
                add(listOf(c.getString(1), c.getString(2), c.getString(3), defaultValue, c.getString(5)))
            }
        } }

    private fun foreignKeys(db: SQLiteDatabase, name: String): Set<List<String?>> =
        db.rawQuery("PRAGMA foreign_key_list(`$name`)", null).use { c -> buildSet {
            while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) })
        } }
}
