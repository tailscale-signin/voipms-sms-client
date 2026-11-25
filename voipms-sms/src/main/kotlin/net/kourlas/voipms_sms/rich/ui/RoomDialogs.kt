package net.kourlas.voipms_sms.rich.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.rich.MatrixChat

internal class RoomDialogs(private val a: AppCompatActivity) {
    private fun field(hint: Int, type: Int) = EditText(a).apply {
        setSingleLine(); setHint(hint); inputType = type
    }
    private fun fields(vararg items: EditText) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.chatDp(24), a.chatDp(8), a.chatDp(24), 0)
        items.forEach { addView(it) }
    }
    fun login(done: () -> Unit) {
        val server = field(R.string.chat_server, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field(R.string.chat_username, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val pass = field(R.string.chat_password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dialog = AlertDialog.Builder(a).setTitle(R.string.chat_account).setView(fields(server, user, pass))
            .setPositiveButton(R.string.chat_sign_in, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            val accept = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            accept.setOnClickListener {
                val serverValue = server.text.toString().trim()
                val userValue = user.text.toString().trim()
                val password = pass.text.toString()
                if (!serverValue.startsWith("https://") || userValue.isBlank() || password.isEmpty()) {
                    Toast.makeText(a, R.string.chat_login_fields, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                accept.isEnabled = false
                a.chatAction {
                    try {
                        MatrixChat.login(a, serverValue, userValue, password)
                        pass.text.clear()
                        dialog.dismiss()
                        done()
                    } finally { accept.isEnabled = true }
                }
            }
        }
        dialog.show()
    }
    fun chooseDirect(done: (RoomLink) -> Unit) {
        val owner = MatrixChat.identity(a)
        if (owner == null) { login { chooseDirect(done) }; return }
        a.chatAction {
            val rooms = MatrixChat.rooms(a).filter {
                !it.invited && it.directPeer != null && ChatLinks.visibleInHub(a, owner, it.id)
            }
            if (owner != MatrixChat.identity(a)) return@chatAction
            val labels = rooms.map { a.getString(R.string.chat_room_identity, it.title, it.directPeer) } +
                a.getString(R.string.chat_new_direct)
            AlertDialog.Builder(a).setTitle(R.string.chat_link).setItems(labels.toTypedArray()) { _, pos ->
                if (pos == rooms.size) {
                    val peer = field(R.string.chat_peer, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                    AlertDialog.Builder(a).setTitle(R.string.chat_new_direct).setView(fields(peer))
                        .setPositiveButton(R.string.chat_invite) { _, _ ->
                            val id = peer.text.toString().trim()
                            if (!validUser(id) || id == MatrixChat.account(a)) {
                                Toast.makeText(a, R.string.chat_valid_members, Toast.LENGTH_LONG).show()
                            } else a.chatAction {
                                check(owner == MatrixChat.identity(a))
                                val room = MatrixChat.create(a, "", listOf(id))
                                if (owner == MatrixChat.identity(a)) done(RoomLink(owner, room, id))
                            }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                } else {
                    val room = rooms[pos]
                    done(RoomLink(owner, room.id, requireNotNull(room.directPeer)))
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }
    fun create(done: (String) -> Unit) {
        val title = field(R.string.chat_room_title, InputType.TYPE_CLASS_TEXT)
        val members = field(R.string.chat_members, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        AlertDialog.Builder(a).setTitle(R.string.chat_new_room).setView(fields(title, members))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.chat_create) { _, _ ->
                val ids = members.text.toString().split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (ids.isEmpty() || ids.any { !validUser(it) || it == MatrixChat.account(a) }) {
                    Toast.makeText(a, R.string.chat_valid_members, Toast.LENGTH_LONG).show()
                } else a.chatAction { done(MatrixChat.create(a, title.text.toString().trim(), ids)) }
            }.show()
    }
    fun invite(room: String) {
        val peer = field(R.string.chat_peer, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        AlertDialog.Builder(a).setTitle(R.string.chat_invite).setView(fields(peer))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.chat_invite) { _, _ ->
                val id = peer.text.toString().trim()
                if (validUser(id)) a.chatAction { MatrixChat.invite(a, room, id) }
                else Toast.makeText(a, R.string.chat_valid_members, Toast.LENGTH_LONG).show()
            }.show()
    }
    private fun validUser(value: String) = value.matches(Regex("@[^\\s:]+:[^\\s/]+"))
}
