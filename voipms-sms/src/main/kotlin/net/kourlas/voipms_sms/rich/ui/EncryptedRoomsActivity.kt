package net.kourlas.voipms_sms.rich.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.rich.MatrixChat
import net.kourlas.voipms_sms.rich.RichRoom

/** Encrypted rooms which have no phone contact, including multi-member group conversations. */
class EncryptedRoomsActivity : AppCompatActivity() {
    private lateinit var screen: LinearLayout
    private var room: String? = null
    private var name = ""
    private var owner: String? = null
    private var loading: Job? = null
    private var watcher: Job? = null
    private var typing: Job? = null
    private var serial = 0
    private var pickerRoom: String? = null
    private var pickerOwner: String? = null
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pickerRoom
        val identity = pickerOwner
        pickerRoom = null; pickerOwner = null
        if (uri != null && target != null && identity != null && canUse(identity, target)) {
            confirmMedia(this, uri, name) {
                check(canUse(identity, target))
                MatrixChat.attach(this, target, uri)
            }
        }
    }
    private val backToRooms = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showRooms() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this, backToRooms)
        screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
        }
        setContentView(screen)
        ViewCompat.setOnApplyWindowInsetsListener(screen) { v, data ->
            val bars = data.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(chatDp(12) + bars.left, bars.top, chatDp(12) + bars.right, bars.bottom)
            data
        }
        pickerRoom = savedInstanceState?.getString("picker-room")
        pickerOwner = savedInstanceState?.getString("picker-owner")
        val previous = savedInstanceState?.getString("room")
        val savedOwner = savedInstanceState?.getString("owner")
        if (previous != null && savedOwner != null && savedOwner == MatrixChat.identity(this) &&
            ChatLinks.visibleInHub(this, savedOwner, previous)) showRoom(previous, savedInstanceState.getString("title").orEmpty())
        else showRooms()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("room", room); outState.putString("owner", owner); outState.putString("title", name)
        outState.putString("picker-room", pickerRoom); outState.putString("picker-owner", pickerOwner)
    }
    override fun onResume() {
        super.onResume()
        val target = room
        if (target != null && owner?.let { canUse(it, target) } != true) showRooms()
    }
    private fun canUse(identity: String, target: String) = room == target && owner == identity &&
        MatrixChat.identity(this) == identity && ChatLinks.visibleInHub(this, identity, target)
    private fun clear() {
        loading?.cancel(); watcher?.cancel(); typing?.cancel()
        serial++
        screen.removeAllViews()
    }
    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label; isAllCaps = false; setTextColor(getColor(R.color.primary)); gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }; parent.addView(this)
    }
    private fun header(label: String) = TextView(this).apply {
        text = label; textSize = 22f
        setPadding(chatDp(8), chatDp(16), chatDp(8), chatDp(8)); screen.addView(this)
    }
    private fun showRooms() {
        clear()
        room = null
        backToRooms.isEnabled = false
        owner = MatrixChat.identity(this)
        header(getString(R.string.chat_rooms))
        val identity = owner
        if (identity == null) {
            button(screen, getString(R.string.chat_sign_in)) { RoomDialogs(this).login(::showRooms) }
            return
        }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list); screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val account = MatrixChat.account(this)
        button(list, getString(R.string.chat_matrix_status, account)) {
            val actions = arrayOf(getString(R.string.chat_recover), getString(R.string.chat_sign_out))
            AlertDialog.Builder(this).setTitle(R.string.chat_account).setItems(actions) { _, i ->
                if (i == 0) recover() else AlertDialog.Builder(this).setMessage(R.string.chat_sign_out_confirm)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.chat_sign_out) { _, _ ->
                        chatAction { MatrixChat.logout(this); showRooms() }
                    }.show()
            }.show()
        }
        button(list, getString(R.string.chat_new_room)) { RoomDialogs(this).create { showRoom(it, getString(R.string.chat_matrix)) } }
        button(list, getString(R.string.chat_refresh)) { showRooms() }
        val state = TextView(this).apply { setText(R.string.chat_loading); list.addView(this) }
        val version = serial
        loading = lifecycleScope.launch {
            try {
                val rooms = MatrixChat.rooms(this@EncryptedRoomsActivity).filter {
                    ChatLinks.visibleInHub(this@EncryptedRoomsActivity, identity, it.id)
                }
                if (version != serial || identity != MatrixChat.identity(this@EncryptedRoomsActivity)) return@launch
                state.isVisible = rooms.isEmpty(); state.setText(R.string.chat_empty_rooms)
                rooms.forEach { item ->
                    val label = if (item.invited) getString(R.string.chat_invited, item.title) else item.title.ifBlank { item.id }
                    button(list, label) { choose(identity, item) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (version == serial) state.setText(R.string.chat_error) }
        }
    }
    private fun choose(identity: String, item: RichRoom) {
        if (MatrixChat.identity(this) != identity || !ChatLinks.visibleInHub(this, identity, item.id)) { showRooms(); return }
        if (item.invited) {
            AlertDialog.Builder(this).setTitle(item.title).setMessage(item.id).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.chat_accept) { _, _ ->
                    chatAction { MatrixChat.join(this, item.id); showRooms() }
                }.show()
        } else {
            val target = ChatLinks.linkedThread(this, identity, item.id)
            if (target == null) showRoom(item.id, item.title)
            else {
                ChatLinks.route(this, target, ChatRoute.MATRIX)
                startActivity(Intent(this, ConversationActivity::class.java)
                    .putExtra(getString(R.string.conversation_did),target.did)
                    .putExtra(getString(R.string.conversation_contact),target.contact))
            }
        }
    }
    private fun recover() {
        val input = EditText(this).apply {
            setHint(R.string.chat_recovery_key)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofillCompat()
        }
        AlertDialog.Builder(this).setTitle(R.string.chat_recover).setView(input).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = input.text.toString().trim(); input.text.clear()
                if (key.isNotEmpty()) chatAction { MatrixChat.recover(this, key); showRooms() }
            }.show()
    }
    private fun showRoom(id: String, title: String) {
        val identity = MatrixChat.identity(this) ?: return showRooms()
        if (!ChatLinks.visibleInHub(this, identity, id)) return showRooms()
        clear()
        owner = identity; room = id; name = title.ifBlank { id }; backToRooms.isEnabled = true
        header(name)
        button(screen, getString(R.string.chat_invite)) { if (canUse(identity, id)) RoomDialogs(this).invite(id) }
        val info = TextView(this).apply { setText(R.string.chat_loading); screen.addView(this) }
        val items = object : ListAdapter<ThreadChatEvent, ChatHolder>(DIFF) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                ChatHolder(LayoutInflater.from(parent.context).inflate(R.layout.thread_chat_entry, parent, false))
            override fun onBindViewHolder(holder: ChatHolder, position: Int) = holder.bind(getItem(position)) { event ->
                previewChatMedia { check(canUse(identity, id)); MatrixChat.media(this@EncryptedRoomsActivity, id, event.key) }
            }
        }
        val layout = LinearLayoutManager(this).apply { stackFromEnd = true }
        val timeline = RecyclerView(this).apply { layoutManager = layout; adapter = items }
        screen.addView(timeline, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = layoutInflater.inflate(R.layout.rich_composer, screen, false)
        composer.isVisible = true; screen.addView(composer)
        val input = composer.findViewById<EditText>(R.id.rich_edit)
        input.importantForAutofillCompat()
        val key = ChatLinks.draftKey("room", identity, id)
        input.setText(ChatLinks.draft(this, key)); input.setSelection(input.length())
        input.doAfterTextChanged {
            if (canUse(identity, id)) {
                ChatLinks.saveDraft(this, key, it.toString())
                typing?.cancel()
                typing = lifecycleScope.launch {
                    delay(350)
                    try { MatrixChat.typing(this@EncryptedRoomsActivity, id, input.length() > 0) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { }
                }
            }
        }
        val send = composer.findViewById<ImageButton>(R.id.rich_send)
        send.setOnClickListener {
            val text = input.text.toString()
            if (!canUse(identity, id) || text.isBlank()) return@setOnClickListener
            send.isEnabled = false
            chatAction {
                try {
                    MatrixChat.send(this, id, text)
                    if (input.text.toString() == text) {
                        ChatLinks.saveDraft(this, key, ""); input.text.clear()
                    }
                } finally { send.isEnabled = true }
            }
        }
        composer.findViewById<View>(R.id.rich_attach).setOnClickListener {
            if (canUse(identity, id)) { pickerRoom = id; pickerOwner = identity; pick.launch(arrayOf("*/*")) }
        }
        watcher = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!canUse(identity, id)) return@repeatOnLifecycle
                var lastRead: String? = null
                try {
                    MatrixChat.observe(this@EncryptedRoomsActivity, id).collect { snapshot ->
                        if (!canUse(identity, id)) { showRooms(); return@collect }
                        val rows = snapshot.events.map { it.toThreadEvent() }
                        val atEnd = items.itemCount == 0 || !timeline.canScrollVertically(1)
                        items.submitList(rows) { if (atEnd && rows.isNotEmpty()) timeline.scrollToPosition(rows.lastIndex) }
                        info.isVisible = snapshot.typing.isNotEmpty()
                        info.text = if (snapshot.typing.size == 1) getString(R.string.chat_typing, snapshot.typing.first()) else getString(R.string.chat_typing_many)
                        val latest = snapshot.events.lastOrNull { !it.mine }?.id
                        if (latest != null && lastRead != latest) {
                            try { MatrixChat.read(this@EncryptedRoomsActivity, id); lastRead = latest }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { }
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { info.isVisible = true; info.setText(R.string.chat_error) }
            }
        }
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ThreadChatEvent>() {
            override fun areItemsTheSame(old: ThreadChatEvent, new: ThreadChatEvent) = old.key == new.key
            override fun areContentsTheSame(old: ThreadChatEvent, new: ThreadChatEvent) = old == new
        }
    }
}
