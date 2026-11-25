package net.kourlas.voipms_sms.delivery

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
    val caller: String = "", val providerDate: String = "")

internal object CallFeedStore {
    private class Storage(context: Context) : SQLiteOpenHelper(context, "call_trail.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events(account TEXT,id TEXT,did TEXT,peer TEXT,receivedAt INTEGER,voicemail INTEGER,mailbox TEXT,folder TEXT,slot TEXT,caller TEXT,providerDate TEXT,PRIMARY KEY(account,id))")
            db.execSQL("CREATE INDEX events_date ON events(account,receivedAt DESC)")
            db.execSQL("CREATE TABLE checkpoints(account TEXT,source TEXT,time INTEGER,PRIMARY KEY(account,source))")
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) = Unit
    }
    private var storage: Storage? = null
    private val revision = MutableStateFlow(0L)
    @Synchronized private fun db(context: Context) = (storage ?: Storage(context.applicationContext).also { storage=it }).writableDatabase
    fun observe(context: Context): Flow<List<CallFeedItem>> = revision.map {
        val account = readAccountCredentials(context)?.takeIf { it.configured } ?: return@map emptyList()
        db(context).rawQuery("SELECT id,did,peer,receivedAt,voicemail,mailbox,folder,slot,caller,providerDate FROM events WHERE account=? ORDER BY receivedAt DESC,id",
            arrayOf(accountKey(account.email))).use { c -> buildList {
                while (c.moveToNext()) add(CallFeedItem(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)!=0,
                    c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getString(9)))
            } }
    }.flowOn(Dispatchers.IO)

    fun last(context: Context, account: String, source: String) = db(context).rawQuery(
        "SELECT time FROM checkpoints WHERE account=? AND source=?",arrayOf(account,source)).use { if(it.moveToFirst()) it.getLong(0) else 0L }

    /** First synchronization seeds the trail without flooding the notification shade. */
    fun save(context: Context, account: String, source: String, items: List<CallFeedItem>, now: Long): List<CallFeedItem> {
        val db = db(context)
        val added = mutableListOf<CallFeedItem>()
        db.beginTransaction()
        try {
            val prior = last(context,account,source)
            for (item in items) {
                val values = ContentValues().apply {
                    put("account",account); put("id",item.id); put("did",item.did); put("peer",item.peer)
                    put("receivedAt",item.receivedAt); put("voicemail",item.voicemail); put("mailbox",item.mailbox)
                    put("folder",item.folder); put("slot",item.slot); put("caller",item.caller); put("providerDate",item.providerDate)
                }
                if (db.insertWithOnConflict("events",null,values,SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    if (prior != 0L && item.receivedAt >= prior - 86_400_000L) added.add(item)
                } else if (item.voicemail) db.update("events",values,"account=? AND id=?",arrayOf(account,item.id))
            }
            db.execSQL("INSERT OR REPLACE INTO checkpoints VALUES (?,?,?)",arrayOf(account,source,now))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value++
        return added
    }
}
