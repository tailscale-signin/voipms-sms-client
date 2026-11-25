package net.kourlas.voipms_sms.nearby

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class NearbyMessage(
    val id: String,
    val text: String,
    val incoming: Boolean,
    val timestamp: Long,
    val delivered: Boolean,
)

internal object NearbyStore {
    private class Storage(
        c: Context,
    ) : SQLiteOpenHelper(c, File(c.noBackupFilesDir, "nearby.db").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE peers(scope TEXT,peer TEXT,name TEXT NOT NULL,PRIMARY KEY(scope,peer))")
            db.execSQL(
                "CREATE TABLE links(scope TEXT,did TEXT,contact TEXT,peer TEXT NOT NULL,PRIMARY KEY(scope,did,contact),UNIQUE(scope,peer))",
            )
            db.execSQL(
                "CREATE TABLE messages(scope TEXT,peer TEXT,id TEXT,incoming INTEGER,stamp INTEGER NOT NULL,body BLOB NOT NULL," +
                    "delivered INTEGER NOT NULL,PRIMARY KEY(scope,peer,incoming,id))",
            )
            db.execSQL("CREATE INDEX history ON messages(scope,peer,stamp DESC)")
            db.execSQL("CREATE INDEX outbox ON messages(scope,peer,incoming,delivered,stamp)")
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }

    private var store: Storage? = null
    private val revision = MutableStateFlow(0L)

    @Synchronized private fun db(c: Context) = (store ?: Storage(c.applicationContext).also { store = it }).writableDatabase

    fun scope(c: Context): String {
        val a = checkNotNull(readAccountCredentials(c)) { "Account storage unavailable" }
        return MessageDigest.getInstance("SHA-256").digest("${a.generation}|${a.email}".toByteArray()).toHexString()
    }

    private fun changed() = revision.update { it + 1 }

    private fun key(
        scope: String,
        peer: String,
        id: String,
        incoming: Boolean,
    ) = "$scope|$peer|$id|$incoming"

    private fun selectedLine(
        c: Context,
        did: String,
    ) = did in getDids(c, onlyRetrieveMessages = true)

    internal fun remember(
        c: Context,
        account: String,
        peer: String,
        name: String,
    ) {
        check(scope(c) == account)
        db(c).execSQL("INSERT OR REPLACE INTO peers(scope,peer,name) VALUES(?,?,?)", arrayOf(account, peer, name))
        changed()
    }

    internal fun known(
        c: Context,
        account: String,
        peer: String,
    ) = db(c).rawQuery("SELECT 1 FROM peers WHERE scope=? AND peer=?", arrayOf(account, peer)).use { it.moveToFirst() }

    internal fun paired(
        c: Context,
        account: String,
    ): List<Pair<String, String>> =
        db(c).rawQuery("SELECT peer,name FROM peers WHERE scope=? ORDER BY name,peer", arrayOf(account)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }

    internal fun linkedThread(
        c: Context,
        account: String,
        peer: String,
    ): ConversationId? =
        db(c).rawQuery("SELECT did,contact FROM links WHERE scope=? AND peer=?", arrayOf(account, peer)).use {
            if (it.moveToFirst()) ConversationId(it.getString(0), it.getString(1)) else null
        }

    internal fun enabledPeer(c: Context, account: String, peer: String): Boolean =
        linkedThread(c, account, peer)?.let { selectedLine(c, it.did) } ?: true

    internal fun privatePeer(
        c: Context,
        account: String,
        peer: String,
    ) = linkedThread(c, account, peer)?.let { PrivateThreads.isPrivate(c, it) } == true

    fun peerId(
        c: Context,
        did: String,
        contact: String,
    ): String? {
        if (!selectedLine(c, did)) return null
        return linkId(db(c), scope(c), did, contact)
    }

    fun isLinked(
        c: Context,
        did: String,
        contact: String,
    ) = peerId(c, did, contact) != null

    private fun linkId(
        db: SQLiteDatabase,
        account: String,
        did: String,
        contact: String,
    ) = db.rawQuery("SELECT peer FROM links WHERE scope=? AND did=? AND contact=?", arrayOf(account, did, contact)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    /** A changed device never inherits an earlier pairing, thread or pending delivery. */
    fun link(
        c: Context,
        account: String,
        peer: String,
        thread: ConversationId,
    ) {
        check(scope(c) == account && selectedLine(c, thread.did))
        db(c).transaction {
            check(known(c, account, peer))
            val old = linkId(this, account, thread.did, thread.contact)
            check(old == null || old == peer)
            val oldThread = linkedThread(c, account, peer)
            check(oldThread == null || oldThread == thread)
            execSQL(
                "INSERT OR IGNORE INTO links(scope,did,contact,peer) VALUES(?,?,?,?)",
                arrayOf(account, thread.did, thread.contact, peer),
            )
        }
        changed()
    }

    fun forget(
        c: Context,
        account: String,
        peer: String,
    ) {
        check(scope(c) == account)
        db(c).transaction {
            for (table in listOf("links", "messages", "peers")) delete(table, "scope=? AND peer=?", arrayOf(account, peer))
        }
        changed()
    }

    private fun Cursor.record(
        account: String,
        peer: String,
    ): NearbyMessage {
        val id = getString(0)
        val incoming = getInt(1) == 1
        return NearbyMessage(id, NearbyKeys.unseal(getBlob(3), key(account, peer, id, incoming)), incoming, getLong(2), getInt(4) == 1)
    }

    private fun history(
        c: Context,
        account: String,
        peer: String,
    ): List<NearbyMessage> =
        db(c)
            .rawQuery(
                "SELECT id,incoming,stamp,body,delivered FROM messages WHERE scope=? AND peer=? ORDER BY stamp DESC,rowid DESC LIMIT 500",
                arrayOf(account, peer),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.record(account, peer)) }.asReversed()
            }

    fun observeThread(
        c: Context,
        did: String,
        contact: String,
    ) = revision
        .map {
            val account = scope(c)
            if (!selectedLine(c, did)) emptyList() else linkId(db(c), account, did, contact)?.let { history(c, account, it) }.orEmpty()
        }.distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    fun observePeer(
        c: Context,
        account: String,
        peer: String,
    ) = revision
        .map {
            if (scope(c) == account) history(c, account, peer) else emptyList()
        }.distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    suspend fun enqueueThread(
        c: Context,
        did: String,
        contact: String,
        text: String,
        expectedPeer: String? = null,
    ) = withContext(Dispatchers.IO) {
        val account = scope(c)
        check(selectedLine(c, did))
        db(c).transaction {
            val peer = checkNotNull(linkId(this, account, did, contact))
            check(expectedPeer == null || expectedPeer == peer)
            queue(c, account, peer, text)
        }
        changed()
    }

    fun enqueuePeer(
        c: Context,
        account: String,
        peer: String,
        text: String,
    ) {
        db(c).transaction { queue(c, account, peer, text) }
        changed()
    }

    private fun queue(
        c: Context,
        account: String,
        peer: String,
        text: String,
    ) {
        require(NearbyWire.validText(text))
        check(scope(c) == account && known(c, account, peer) && enabledPeer(c, account, peer))
        put(db(c), account, peer, UUID.randomUUID().toString(), text, false)
    }

    private fun put(
        db: SQLiteDatabase,
        account: String,
        peer: String,
        id: String,
        text: String,
        incoming: Boolean,
    ) {
        val v =
            ContentValues().apply {
                put("scope", account)
                put("peer", peer)
                put("id", id)
                put("incoming", if (incoming) 1 else 0)
                put("delivered", if (incoming) 1 else 0)
                put("stamp", System.currentTimeMillis())
                put("body", NearbyKeys.seal(text, key(account, peer, id, incoming)))
            }
        db.insertOrThrow("messages", null, v)
    }

    /** Idempotent storage allows either side to disconnect after a commit and before its acknowledgement. */
    fun receive(
        c: Context,
        account: String,
        peer: String,
        msg: NearbyWire.Frame.Text,
    ) {
        val id = msg.id.toString()
        db(c).transaction {
            check(scope(c) == account && known(c, account, peer) && enabledPeer(c, account, peer))
            val existing =
                rawQuery("SELECT body FROM messages WHERE scope=? AND peer=? AND incoming=1 AND id=?", arrayOf(account, peer, id))
                    .use { if (it.moveToFirst()) it.getBlob(0) else null }
            if (existing == null) {
                put(this, account, peer, id, msg.body, true)
            } else {
                check(NearbyKeys.unseal(existing, key(account, peer, id, true)) == msg.body)
            }
        }
        changed()
    }

    fun acknowledge(
        c: Context,
        account: String,
        peer: String,
        id: UUID,
    ) {
        check(scope(c) == account)
        val v = ContentValues().apply { put("delivered", 1) }
        if (db(
                c,
            ).update("messages", v, "scope=? AND peer=? AND id=? AND incoming=0 AND delivered=0", arrayOf(account, peer, id.toString())) >
            0
        ) {
            changed()
        }
    }

    fun outbox(
        c: Context,
        account: String,
        peer: String,
    ) = revision
        .map {
            check(scope(c) == account && known(c, account, peer) && enabledPeer(c, account, peer))
            db(c)
                .rawQuery(
                    "SELECT id,incoming,stamp,body,delivered FROM messages WHERE scope=? AND peer=? AND incoming=0 AND delivered=0 " +
                        "ORDER BY stamp,rowid LIMIT 16",
                    arrayOf(account, peer),
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.record(account, peer)) }
                }
        }.distinctUntilChanged()
        .flowOn(Dispatchers.IO)
}
