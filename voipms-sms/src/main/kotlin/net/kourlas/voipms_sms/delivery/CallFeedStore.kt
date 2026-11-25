package net.kourlas.voipms_sms.delivery

import androidx.core.database.sqlite.transaction
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.sms.workers.accountKey

internal data class CallFeedItem(val id: String, val did: String, val peer: String, val receivedAt: Long,
    val voicemail: Boolean, val mailbox: String = "", val folder: String = "", val slot: String = "",
    val caller: String = "", val providerDate: String = "", val mailboxLines: Set<String> = emptySet()) {
    /** Epoch-keyed records remain precise even when the provider's accompanying date has no time. */
    val providerEpoch: Long?
        get() = if (voicemail && id.startsWith("vme:$mailbox:")) receivedAt / 1000L else null

    val dateOnly: Boolean
        get() = voicemail && providerEpoch == null && providerDate.length == 10
}

internal object CallFeedStore {
    private class Storage(context: Context) : SQLiteOpenHelper(context, "call_trail.db",null,2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events(account TEXT,id TEXT,did TEXT,peer TEXT,receivedAt INTEGER,voicemail INTEGER,mailbox TEXT,folder TEXT,slot TEXT,caller TEXT,providerDate TEXT,mailboxLines TEXT NOT NULL DEFAULT '',PRIMARY KEY(account,id))")
            db.execSQL("CREATE INDEX events_date ON events(account,receivedAt DESC)")
            db.execSQL("CREATE TABLE checkpoints(account TEXT,source TEXT,time INTEGER,PRIMARY KEY(account,source))")
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE events ADD COLUMN mailboxLines TEXT NOT NULL DEFAULT ''")
        }
    }
    private var storage: Storage? = null
    private val revision = MutableStateFlow(0L)
    @Synchronized private fun db(context: Context) = (storage ?: Storage(context.applicationContext).also { storage=it }).writableDatabase
    fun observe(context: Context): Flow<List<CallFeedItem>> = revision.map {
        val account = readAccountCredentials(context)?.takeIf { it.configured } ?: return@map emptyList()
        db(context).rawQuery("SELECT id,did,peer,receivedAt,voicemail,mailbox,folder,slot,caller,providerDate,mailboxLines FROM events WHERE account=? ORDER BY receivedAt DESC,id",
            arrayOf(accountKey(account.email))).use { c -> buildList {
                while (c.moveToNext()) add(CallFeedItem(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)!=0,
                    c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getString(9),
                    c.getString(10).split(',').filter(String::isNotEmpty).toSet()))
            } }
    }.flowOn(Dispatchers.IO)

    fun last(context: Context, account: String, source: String) = db(context).rawQuery(
        "SELECT time FROM checkpoints WHERE account=? AND source=?",arrayOf(account,source)).use { if(it.moveToFirst()) it.getLong(0) else 0L }

    /** Upgrade an existing recording only when its saved instant agrees with the newly supplied epoch. */
    private fun promoteLegacy(db: SQLiteDatabase, account: String, item: CallFeedItem) {
        val epoch = item.providerEpoch ?: return
        val newPrefix = "vme:${item.mailbox}:$epoch:${item.peer}:"
        if (!item.id.startsWith(newPrefix)) return
        val duration = item.id.removePrefix(newPrefix).substringBeforeLast(':')
        val legacyBase = "vm:${item.mailbox}:${item.providerDate}:${item.peer}:$duration:"
        val oldId = db.rawQuery(
            "SELECT id FROM events WHERE account=? AND mailbox=? AND caller=? AND receivedAt=? AND providerDate=? AND substr(id,1,?)=? ORDER BY id",
            arrayOf(account,item.mailbox,item.caller,item.receivedAt.toString(),item.providerDate,legacyBase.length.toString(),legacyBase)
        ).use { c ->
            val eligible = buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (id.removePrefix(legacyBase).toIntOrNull() != null) add(id)
                }
            }
            eligible.singleOrNull()
        } ?: return
        // Atomic re-key retains the historical DID and mailbox line scope. If a recipient has
        // several indistinguishable legacy recordings, none is silently promoted to this one.
        db.execSQL("UPDATE OR IGNORE events SET id=? WHERE account=? AND id=?", arrayOf(item.id,account,oldId))
    }

    /** First synchronization seeds the trail without flooding the notification shade. */
    fun save(context: Context, account: String, source: String, items: List<CallFeedItem>, now: Long, subscribedAt: Long = Long.MAX_VALUE): List<CallFeedItem> {
        val db = db(context)
        val added = mutableListOf<CallFeedItem>()
        db.transaction {
            val prior = last(context,account,source)
            for (item in items) {
                if (item.providerEpoch != null) promoteLegacy(db,account,item)
                val values = ContentValues().apply {
                    put("account",account); put("id",item.id); put("did",item.did); put("peer",item.peer)
                    put("receivedAt",item.receivedAt); put("voicemail",item.voicemail); put("mailbox",item.mailbox)
                    put("folder",item.folder); put("slot",item.slot); put("caller",item.caller); put("providerDate",item.providerDate)
                    put("mailboxLines",item.mailboxLines.sorted().joinToString(","))
                }
                if (db.insertWithOnConflict("events",null,values,SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    val alertFrom = if (prior == 0L) subscribedAt else prior - 86_400_000L
                    if (item.receivedAt >= alertFrom) added.add(item)
                } else if (item.voicemail) {
                    // A changing mailbox assignment does not change who previously received a recording.
                    values.remove("did")
                    values.remove("mailboxLines")
                    db.update("events",values,"account=? AND id=?",arrayOf(account,item.id))
                }
            }
            db.execSQL("INSERT OR REPLACE INTO checkpoints VALUES (?,?,?)",arrayOf<Any>(account,source,now))
        }
        revision.value++
        return added
    }
}
