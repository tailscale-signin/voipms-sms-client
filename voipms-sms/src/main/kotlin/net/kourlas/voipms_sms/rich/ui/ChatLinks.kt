package net.kourlas.voipms_sms.rich.ui

import android.content.Context
import androidx.core.content.edit
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.withSecurePreferences
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import org.json.JSONObject
import java.security.MessageDigest

internal enum class ChatRoute { SMS, MATRIX, NEARBY }
internal data class RoomLink(val owner: String, val room: String, val peer: String)

internal object ChatLinks {
    private fun key(vararg fields: String) = MessageDigest.getInstance("SHA-256")
        .digest(fields.joinToString("\u0000").toByteArray()).toHexString()
    private fun scope(c: Context, thread: ConversationId) =
        key(readAccountCredentials(c)?.generation.toString(), thread.did, thread.contact)
    private fun linkKey(c: Context, thread: ConversationId) = "rich.link." + scope(c, thread)
    private fun reverseKey(owner: String, room: String) = "rich.room." + key(owner, room)
    private fun prefs(c: Context) = c.getSharedPreferences("outgoing-transport", Context.MODE_PRIVATE)

    fun route(c: Context, thread: ConversationId): ChatRoute = ChatRoute.entries.firstOrNull {
        it.name == prefs(c).getString(scope(c, thread), null)
    } ?: ChatRoute.SMS

    fun route(c: Context, thread: ConversationId, route: ChatRoute) {
        prefs(c).edit { putString(scope(c, thread), route.name) }
    }

    fun linked(c: Context, thread: ConversationId): RoomLink? = withSecurePreferences(c) { p ->
        p.getString(linkKey(c, thread), null)?.let {
            val obj = JSONObject(it)
            RoomLink(obj.getString("owner"), obj.getString("room"), obj.getString("peer"))
        }
    }

    /** A linked room has one phone identity. Reassignment requires removing its existing link. */
    fun bind(c: Context, thread: ConversationId, link: RoomLink): Boolean = withSecurePreferences(c) { p ->
        val reverse = reverseKey(link.owner, link.room)
        val existing = p.getString(reverse, null)?.let(::JSONObject)
        if (existing != null && existing.getString("scope") != scope(c, thread)) return@withSecurePreferences false
        val forward = linkKey(c, thread)
        val old = p.getString(forward, null)?.let(::JSONObject)
        p.edit {
            if (old != null) remove(reverseKey(old.getString("owner"), old.getString("room")))
            putString(forward, JSONObject().put("owner", link.owner).put("room", link.room).put("peer", link.peer).toString())
            putString(reverse, JSONObject().put("scope", scope(c, thread)).put("did", thread.did)
                .put("contact", thread.contact).toString())
        }
        true
    } == true

    fun unlink(c: Context, thread: ConversationId) = withSecurePreferences(c) { p ->
        val k = linkKey(c, thread)
        p.getString(k, null)?.let(::JSONObject)?.let { old ->
            p.edit { remove(reverseKey(old.getString("owner"), old.getString("room"))); remove(k) }
        }
    }

    fun visibleInHub(c: Context, owner: String, room: String): Boolean = withSecurePreferences(c) { p ->
        val link = p.getString(reverseKey(owner, room), null)?.let(::JSONObject) ?: return@withSecurePreferences true
        val thread = ConversationId(link.getString("did"), link.getString("contact"))
        link.getString("scope") == scope(c, thread) && !PrivateThreads.isPrivate(c, thread)
    } == true

    fun linkedThread(c: Context, owner: String, room: String): ConversationId? = withSecurePreferences(c) { p ->
        p.getString(reverseKey(owner, room), null)?.let(::JSONObject)?.let { stored ->
            ConversationId(stored.getString("did"), stored.getString("contact"))
                .takeIf { scope(c, it) == stored.getString("scope") }
        }
    }

    fun draftKey(vararg ids: String) = "rich.draft." + key(*ids)
    fun draft(c: Context, k: String) = withSecurePreferences(c) { it.getString(k, "").orEmpty() }.orEmpty()
    fun saveDraft(c: Context, k: String, text: String) = withSecurePreferences(c) {
        it.edit { if (text.isEmpty()) remove(k) else putString(k, text) }
    }
}
