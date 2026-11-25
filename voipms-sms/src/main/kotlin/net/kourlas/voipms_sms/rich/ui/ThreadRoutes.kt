package net.kourlas.voipms_sms.rich.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.carrier.CarrierMessages
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.nearby.NearbyActivity
import net.kourlas.voipms_sms.nearby.NearbyStore
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.rich.MatrixChat
import net.kourlas.voipms_sms.sms.ConversationId

internal class ThreadRoutes(
    private val activity: ConversationActivity,
    private val thread: ConversationId,
    private val feed: ResumedChatFeed,
    private val pickFile: () -> Unit,
    private val smsAttachments: () -> Boolean,
    private val render: (ChatRoute, List<ThreadChatEvent>) -> Unit
) {
    var route = ChatLinks.route(activity, thread)
        private set
    private val generation = readAccountCredentials(activity)?.generation
    private val routeButton = activity.findViewById<Button>(R.id.chat_route)
    private val smsComposer = activity.findViewById<View>(R.id.sms_composer)
    private val richComposer = activity.findViewById<View>(R.id.rich_composer)
    private val sendButton = activity.findViewById<ImageButton>(R.id.rich_send)
    private val attachButton = activity.findViewById<ImageButton>(R.id.rich_attach)
    private val smsEdit = activity.findViewById<EditText>(R.id.message_edit_text)
    private val richEdit = activity.findViewById<EditText>(R.id.rich_edit)
    val editor: EditText get() = if (route == ChatRoute.SMS) smsEdit else richEdit
    private var typingJob: Job? = null
    private var pendingSend = false
    private var alive = true
    private var restoring = false
    private var draftKey: String? = null
    private var link: RoomLink? = null
    private var nearbyId: String? = null
    private var incomingTyping = ""
    private var roomProblem = false
    private var lastRead: String? = null
    private val watcher = richEdit.doAfterTextChanged {
        if (!restoring && active()) {
            draftKey?.let { key -> ChatLinks.saveDraft(activity, key, it.toString()) }
            if (route == ChatRoute.MATRIX) publishTyping()
        }
    }
    init {
        richEdit.importantForAutofillCompat()
        routeButton.setOnClickListener { choose() }
        sendButton.setOnClickListener { send() }
        attachButton.setOnClickListener { if (bindingForMedia() != null) pickFile() }
        refresh()
    }

    private fun active() = alive && !activity.isFinishing && !activity.isDestroyed &&
        activity.conversationId == thread && generation == readAccountCredentials(activity)?.generation

    private fun refreshButtons() {
        val label = activity.getString(when (route) {
            ChatRoute.SMS -> R.string.chat_sms
            ChatRoute.MATRIX -> R.string.chat_matrix
            ChatRoute.NEARBY -> R.string.chat_nearby
        })
        routeButton.text = if (roomProblem && route == ChatRoute.MATRIX)
            activity.getString(R.string.chat_meta, label, activity.getString(R.string.chat_review_link))
        else if (incomingTyping.isNotEmpty() && route == ChatRoute.MATRIX)
            activity.getString(R.string.chat_meta, label, incomingTyping)
        else label
        routeButton.contentDescription = activity.getString(R.string.chat_meta, activity.getString(R.string.chat_routes), label)
        smsComposer.isVisible = route == ChatRoute.SMS
        richComposer.isVisible = route != ChatRoute.SMS
        richEdit.isEnabled = draftKey != null
        attachButton.isVisible = route == ChatRoute.MATRIX
        sendButton.isEnabled = !pendingSend && when (route) {
            ChatRoute.MATRIX -> currentLink() != null && !roomProblem
            ChatRoute.NEARBY -> nearbyId != null
            ChatRoute.SMS -> false
        }
        attachButton.isEnabled = sendButton.isEnabled
    }
    private fun currentLink() = link?.takeIf { MatrixChat.identity(activity) == it.owner }

    fun refresh() {
        if (!active()) return
        link = ChatLinks.linked(activity, thread)
        nearbyId = NearbyStore.peerId(activity, thread.did, thread.contact)
        val next = when (route) {
            ChatRoute.SMS -> null
            ChatRoute.MATRIX -> currentLink()?.let { ChatLinks.draftKey("matrix",it.owner,it.room,it.peer, generation.toString(),thread.getId()) }
            ChatRoute.NEARBY -> nearbyId?.let { ChatLinks.draftKey("nearby", generation.toString(), thread.getId(), it) }
        }
        if (next != draftKey) {
            restoring = true
            draftKey = next
            richEdit.setText(next?.let { ChatLinks.draft(activity, it) }.orEmpty())
            richEdit.setSelection(richEdit.length())
            restoring = false
        }
        roomProblem = false
        incomingTyping = ""
        lastRead = null
        refreshButtons()
        val room = currentLink()
        val profile = net.kourlas.voipms_sms.privacy.JunkProfiles(activity).line(thread.did)
        render(ChatRoute.MATRIX, emptyList())
        render(ChatRoute.NEARBY, emptyList())
        feed.observe {
            if (room != null) launch {
                try {
                    MatrixChat.observe(activity, room.room, room.peer).collect { snapshot ->
                        if (!active() || currentLink() != room) return@collect
                        roomProblem = false
                        render(ChatRoute.MATRIX, snapshot.events.map { it.toThreadEvent(profile) })
                        incomingTyping = when (snapshot.typing.size) {
                            0 -> ""
                            1 -> activity.getString(R.string.chat_typing, snapshot.typing.first())
                            else -> activity.getString(R.string.chat_typing_many)
                        }
                        refreshButtons()
                        val latest = snapshot.events.lastOrNull { !it.mine }?.id
                        if (latest != null && latest != lastRead) {
                            lastRead = latest
                            try { MatrixChat.read(activity, room.room, room.peer) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { lastRead = null }
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    if (active() && currentLink() == room) {
                        roomProblem = true
                        render(ChatRoute.MATRIX, emptyList())
                        refreshButtons()
                    }
                }
            }
            if (nearbyId != null) launch {
                NearbyStore.observeThread(activity, thread.did, thread.contact).collect { messages ->
                    if (active()) render(ChatRoute.NEARBY, messages.map {
                        ThreadChatEvent(it.id, it.timestamp, it.text, !it.incoming, ChatRoute.NEARBY, "",
                            if (it.incoming) "" else activity.getString(if (it.delivered) R.string.chat_delivered else R.string.chat_pending),
                            warning = if (it.incoming) net.kourlas.voipms_sms.sms.InboxRules.assess(it.text, profile).description else null)
                    })
                }
            }
        }
    }

    fun choose() {
        if (!active() || pendingSend) return
        val available = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.chat_sms to { switchTo(ChatRoute.SMS) },
            R.string.chat_matrix to {
                if (currentLink() == null || roomProblem) chooseLink() else switchTo(ChatRoute.MATRIX)
            },
            R.string.chat_nearby to {
                if (nearbyId == null) openNearby() else switchTo(ChatRoute.NEARBY)
            },
            R.string.chat_connect_peer to { openNearby() },
            R.string.chat_rooms to { activity.startActivity(Intent(activity, EncryptedRoomsActivity::class.java)) },
            R.string.carrier_messages_action to {
                CarrierMessages.open(activity, thread.contact, editor.text.toString(), route == ChatRoute.SMS && smsAttachments())
            }
        )
        if (link != null) available.add(R.string.chat_unlink to {
            AlertDialog.Builder(activity).setMessage(R.string.chat_unlink_confirm).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chat_unlink) { _, _ ->
                    ChatLinks.unlink(activity, thread)
                    // Keep the previous transport selected until the user chooses a replacement.
                    refresh()
                }.show()
        })
        AlertDialog.Builder(activity).setTitle(R.string.chat_routes)
            .setItems(available.map { activity.getString(it.first) }.toTypedArray()) { _, which -> available[which].second() }
            .show()
    }

    private fun openNearby() {
        activity.startActivity(NearbyActivity.intent(activity, thread.did, thread.contact))
    }
    private fun chooseLink() = RoomDialogs(activity).chooseDirect { chosen ->
        if (!active()) return@chooseDirect
        AlertDialog.Builder(activity).setTitle(R.string.chat_trust)
            .setMessage(activity.getString(R.string.chat_trust_explanation, chosen.peer, thread.contact, thread.did))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.chat_link) { _, _ ->
                if (active() && MatrixChat.identity(activity) == chosen.owner && ChatLinks.bind(activity, thread, chosen))
                    change(ChatRoute.MATRIX)
                else Toast.makeText(activity, R.string.chat_link_conflict, Toast.LENGTH_LONG).show()
            }.show()
    }
    private fun switchTo(next: ChatRoute) {
        if (route == next) return
        if (next != ChatRoute.SMS) { change(next); return }
        val text = richEdit.text.toString()
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.chat_fallback)
            .setMessage(activity.getString(R.string.chat_fallback_body, thread.did))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chat_switch) { _, _ -> change(next) }
        if (text.isNotBlank() && smsEdit.text.isNullOrEmpty() && !smsAttachments()) {
            dialog.setNeutralButton(R.string.chat_move_draft) { _, _ ->
                if (richEdit.text.toString() == text && smsEdit.text.isNullOrEmpty()) {
                    richEdit.text.clear()
                    change(next)
                    smsEdit.setText(text); smsEdit.setSelection(smsEdit.length())
                }
            }
        }
        dialog.show()
    }
    private fun change(next: ChatRoute) {
        if (!active()) return
        route = next
        ChatLinks.route(activity, thread, route)
        refresh()
        activity.invalidateOptionsMenu()
    }
    private fun send() {
        if (!active() || pendingSend || route == ChatRoute.SMS) return
        val text = richEdit.text.toString()
        if (text.isBlank()) return
        val selected = route
        val selectedDraft = draftKey ?: return
        val room = currentLink()
        val peer = nearbyId
        pendingSend = true
        refreshButtons()
        activity.chatAction(R.string.chat_local_error) {
            try {
                when (selected) {
                    ChatRoute.MATRIX -> {
                        check(room != null && !roomProblem && currentLink() == room)
                        MatrixChat.send(activity, room.room, text, room.peer)
                    }
                    ChatRoute.NEARBY -> NearbyStore.enqueueThread(activity, thread.did, thread.contact, text, requireNotNull(peer))
                    ChatRoute.SMS -> error("Unexpected transport")
                }
                if (active() && selectedDraft == draftKey && richEdit.text.toString() == text) richEdit.text.clear()
                else if (ChatLinks.draft(activity, selectedDraft) == text) ChatLinks.saveDraft(activity, selectedDraft, "")
            } finally {
                pendingSend = false
                if (active()) refreshButtons()
            }
        }
    }
    private fun publishTyping() {
        val room = currentLink() ?: return
        typingJob?.cancel()
        typingJob = activity.chatAction {
            delay(350)
            if (active() && route == ChatRoute.MATRIX && currentLink() == room)
                try { MatrixChat.typing(activity, room.room, richEdit.text.isNotEmpty(), room.peer) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Typing expires on the server. */ }
        }
    }
    fun bindingForMedia(): RoomLink? = currentLink()?.takeIf { active() && route == ChatRoute.MATRIX && !roomProblem }
    fun attach(uri: Uri, target: RoomLink) {
        if (target != bindingForMedia()) return
        confirmMedia(activity, uri, target.peer) {
            check(target == bindingForMedia())
            MatrixChat.attach(activity, target.room, uri, target.peer)
        }
    }
    fun open(event: ThreadChatEvent) {
        val room = currentLink() ?: return
        if (event.route == ChatRoute.MATRIX && event.attachment) activity.previewChatMedia {
            check(active() && currentLink() == room)
            MatrixChat.media(activity, room.room, event.key, room.peer)
        }
    }
    fun close() {
        alive = false
        richEdit.removeTextChangedListener(watcher)
        feed.clear()
        typingJob?.cancel()
    }
}
