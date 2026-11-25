package net.kourlas.voipms_sms.nearby

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import java.text.DateFormat
import java.util.Date

class NearbyActivity : AppCompatActivity() {
    companion object {
        private const val DID = "nearby.did"
        private const val CONTACT = "nearby.contact"
        private const val DRAFT = "nearby.draft"

        fun intent(
            context: Context,
            did: String? = null,
            contact: String? = null,
            draft: String? = null,
        ) = Intent(context, NearbyActivity::class.java).putExtra(DID, did).putExtra(CONTACT, contact).putExtra(DRAFT, draft)
    }

    private val thread: ConversationId? by lazy {
        val did = intent.getStringExtra(DID)
        val contact = intent.getStringExtra(CONTACT)
        if (!did.isNullOrBlank() && !contact.isNullOrBlank()) ConversationId(did, contact) else null
    }
    private lateinit var account: String
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var host: MaterialButton
    private lateinit var find: MaterialButton
    private lateinit var select: MaterialButton
    private lateinit var disconnect: MaterialButton
    private lateinit var paired: MaterialButton
    private lateinit var associate: MaterialButton
    private lateinit var forget: MaterialButton
    private lateinit var editor: EditText
    private lateinit var composer: LinearLayout
    private lateinit var send: MaterialButton
    private lateinit var list: ListView
    private val selectedPeer = MutableStateFlow<String?>(null)
    private val activePeer: String? get() = selectedPeer.value
    private var prompted: String? = null
    private var prompt: AlertDialog? = null
    private var unlocked = false
    private var authenticating = false
    private var requestingHost = false
    private var submitted = false
    private var initialDraft: String? = null
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[NearbyRadio.permission()] == true) NearbyRadio.start(this, requestingHost) else toast(R.string.nearby_permission_denied)
        }
    private val unlock =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            authenticating = false
            if (result.resultCode != Activity.RESULT_OK) {
                finish()
            } else {
                unlocked = true
                begin()
            }
        }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()

    private fun button(
        label: Int,
        action: () -> Unit,
    ) = MaterialButton(
        this,
        null,
        androidx.appcompat.R.attr.borderlessButtonStyle,
    ).apply {
        setText(label)
        setOnClickListener { action() }
    }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(size: Float) = TextView(this).apply { textSize = size }

    private fun requireGate() = thread?.let { PrivateThreads.isPrivate(this, it) } == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        account =
            runCatching { NearbyStore.scope(this) }.getOrElse {
                toast(R.string.nearby_read_error)
                finish()
                return
            }
        initialDraft = intent.getStringExtra(DRAFT)
        val root = column()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
                )
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val bar =
            MaterialToolbar(this).apply {
                setTitle(R.string.nearby_title)
                setNavigationIcon(R.drawable.ic_close_toolbar_24dp)
                setNavigationContentDescription(android.R.string.cancel)
                setNavigationOnClickListener { finish() }
            }
        root.addView(bar)
        val body = column().apply { setPadding(dp(16), 0, dp(16), dp(10)) }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        status =
            text(14f).apply {
                setText(R.string.nearby_introduction)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        body.addView(status)
        host = button(R.string.nearby_host) { discover(true) }
        find = button(R.string.nearby_find) { discover(false) }
        disconnect = button(R.string.nearby_leave) { NearbyRadio.stop() }
        paired = button(R.string.nearby_history) { choosePaired() }
        body.addView(
            row().apply {
                addView(host)
                addView(find)
                addView(disconnect)
            },
        )
        body.addView(paired, LinearLayout.LayoutParams(-2, -2))
        select = button(R.string.nearby_select_peer) { chooseDevice() }
        body.addView(select)
        title = text(18f)
        body.addView(title)
        associate = button(R.string.nearby_link) { offerLink() }
        forget = button(R.string.nearby_forget) { offerForget() }
        body.addView(
            row().apply {
                addView(associate)
                addView(forget)
            },
        )
        list =
            ListView(this).apply {
                divider = null
                transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
                isStackFromBottom = true
            }
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        editor =
            EditText(this).apply {
                setHint(R.string.nearby_composer)
                maxLines = 4
                minLines = 1
            }
        send = button(R.string.nearby_send) { send() }
        composer =
            row().apply {
                addView(editor, LinearLayout.LayoutParams(0, -2, 1f))
                addView(send)
            }
        body.addView(composer)
        setContentView(root)
        list.isVisible = false
        composer.isVisible = false
        associate.isVisible = false
        forget.isVisible = false
        select.isVisible = false
        disconnect.isVisible =
            false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { NearbyRadio.state.collect { if (!requireGate() || unlocked) render(it) } }
                selectedPeer.collectLatest { id -> if (id != null) observeHistory(id) }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        if (!::account.isInitialized || isFinishing) return
        if (runCatching { NearbyStore.scope(this) }.getOrNull() != account) {
            toast(R.string.nearby_account_changed)
            finish()
            return
        }
        if (requireGate() && !unlocked) {
            if (!authenticating) {
                val intent =
                    getSystemService(
                        KeyguardManager::class.java,
                    ).createConfirmDeviceCredentialIntent(getString(R.string.nearby_title), null)
                if (intent == null) {
                    finish()
                } else {
                    authenticating = true
                    unlock.launch(intent)
                }
            }
        } else {
            begin()
        }
    }

    override fun onStop() {
        if (requireGate() && unlocked) {
            unlocked = false
            clearPeer()
            prompt?.dismiss()
            prompt = null
            prompted = null
        }
        super.onStop()
    }

    private fun begin() {
        val id = thread?.let { NearbyStore.peerId(this, it.did, it.contact) }
        if (id != null && activePeer == null) {
            val label =
                NearbyStore
                    .paired(this, account)
                    .find { it.first == id }
                    ?.second
                    .orEmpty()
            showPeer(id, label)
        }
        render(NearbyRadio.state.value)
    }

    private fun discover(asHost: Boolean) {
        requestingHost = asHost
        val perm = NearbyRadio.permission()
        if (ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            NearbyRadio.start(this, asHost)
        } else {
            // Android 12 requires fine and coarse location in the same permission request.
            val permissions = if (android.os.Build.VERSION.SDK_INT <= 32)
                arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, perm) else arrayOf(perm)
            permission.launch(permissions)
        }
    }

    private fun permitted(peer: String): Boolean {
        if (!NearbyStore.enabledPeer(this, account, peer)) return false
        if (!NearbyStore.privatePeer(this, account, peer)) return true
        return unlocked && thread != null && NearbyStore.linkedThread(this, account, peer) == thread
    }

    private fun render(s: NearbyState) {
        if (runCatching { NearbyStore.scope(this) }.getOrNull() != account) {
            finish()
            return
        }
        status.setText(s.status)
        host.isVisible = !s.started
        find.isVisible = !s.started
        disconnect.isVisible = s.started
        select.isVisible = s.devices.isNotEmpty()
        val peer = s.peer
        if (peer != null && !permitted(peer)) {
            NearbyRadio.stop(R.string.nearby_private_hint)
            clearPeer()
            return
        }
        if (s.comparison != null && prompted != s.comparison && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            prompted = s.comparison
            prompt?.dismiss()
            prompt =
                AlertDialog
                    .Builder(this)
                    .setTitle(R.string.nearby_compare)
                    .setMessage(getString(R.string.nearby_confirm_identity, s.label, s.comparison))
                    .setPositiveButton(R.string.nearby_codes_match) { _, _ -> NearbyRadio.confirm(true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> NearbyRadio.confirm(false) }
                    .setOnCancelListener { NearbyRadio.confirm(false) }
                    .show()
        } else if (s.comparison == null) {
            prompted = null
            prompt?.dismiss()
            prompt = null
        }
        if (s.active && peer != null && activePeer != peer) showPeer(peer, s.label)
        updateControls()
    }

    private fun chooseDevice() {
        val entries =
            NearbyRadio.state.value.devices.entries
                .toList()
        if (entries.isEmpty()) return
        AlertDialog
            .Builder(this)
            .setTitle(R.string.nearby_select_peer)
            .setItems(entries.map { getString(R.string.nearby_device_label, it.value, it.key.takeLast(5)) }.toTypedArray()) { _, i ->
                NearbyRadio.connect(entries[i].key)
            }.show()
    }

    private fun choosePaired() {
        val peers = NearbyStore.paired(this, account).filter { permitted(it.first) }
        if (peers.isEmpty()) {
            toast(R.string.nearby_no_history)
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.nearby_history)
            .setItems(peers.map { getString(R.string.nearby_device_label, it.second, it.first.take(8)) }.toTypedArray()) { _, i ->
                showPeer(peers[i].first, peers[i].second)
            }.show()
    }

    private fun clearPeer() {
        selectedPeer.value = null
        editor.setText("")
        list.adapter = null
        title.text = ""
        updateControls()
    }

    private fun showPeer(
        id: String,
        label: String,
    ) {
        if (!permitted(id)) {
            toast(R.string.nearby_private_hint)
            return
        }
        if (activePeer == id) return
        val draft = initialDraft
        val target = thread
        if (draft != null && target != null && NearbyStore.peerId(this, target.did, target.contact) == id) {
            editor.setText(draft)
            initialDraft = null
        } else {
            editor.setText("")
        }
        title.text = getString(R.string.nearby_device_label, label, id.take(8))
        list.adapter = null
        selectedPeer.value = id
        updateControls()
    }

    private suspend fun observeHistory(id: String) {
        try {
            NearbyStore.observePeer(applicationContext, account, id).collect { messages ->
                if (activePeer != id) return@collect
                if (!permitted(id)) {
                    clearPeer()
                    return@collect
                }
                val bottom = list.count == 0 || list.lastVisiblePosition >= list.count - 2
                val first = list.firstVisiblePosition
                val offset = list.getChildAt(0)?.top ?: 0
                list.adapter =
                    object : ArrayAdapter<NearbyMessage>(this@NearbyActivity, android.R.layout.simple_list_item_2, messages) {
                        override fun getView(
                            position: Int,
                            convertView: View?,
                            parent: ViewGroup,
                        ): View {
                            val item = getItem(position)!!
                            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                            view.findViewById<TextView>(android.R.id.text1).text = item.text
                            val type =
                                when {
                                    item.incoming -> R.string.nearby_received
                                    item.delivered -> R.string.nearby_delivered
                                    else -> R.string.nearby_queued
                                }
                            view.findViewById<TextView>(android.R.id.text2).text =
                                getString(
                                    type,
                                    DateFormat
                                        .getDateTimeInstance(
                                            DateFormat.SHORT,
                                            DateFormat.SHORT,
                                        ).format(Date(item.timestamp)),
                                )
                            return view
                        }
                    }
                if (bottom &&
                    messages.isNotEmpty()
                ) {
                    list.setSelection(messages.lastIndex)
                } else {
                    list.setSelectionFromTop(first, offset)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            toast(R.string.nearby_read_error)
        }
    }

    private fun updateControls() {
        val peer = activePeer
        val s = NearbyRadio.state.value
        val linked = thread?.let { NearbyStore.peerId(this, it.did, it.contact) }
        associate.isVisible = thread != null && peer != null && s.active && s.peer == peer && linked != peer
        list.isVisible = peer != null
        composer.isVisible = peer != null
        forget.isVisible = peer != null
        send.isEnabled = !submitted
        send.setText(if (s.active && s.peer == peer) R.string.nearby_send else R.string.nearby_queue)
    }

    private fun offerLink() {
        val peer = activePeer ?: return
        val target = thread ?: return
        AlertDialog
            .Builder(this)
            .setTitle(R.string.nearby_link)
            .setMessage(getString(R.string.nearby_link_detail, target.contact, target.did))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.nearby_link) { _, _ ->
                if (NearbyRadio.state.value.active && NearbyRadio.state.value.peer == peer) {
                    try {
                        NearbyStore.link(this, account, peer, target)
                        status.text = getString(R.string.nearby_linked, target.contact)
                        initialDraft?.let {
                            editor.setText(it)
                            initialDraft = null
                        }
                    } catch (_: IllegalStateException) {
                        toast(R.string.nearby_conflict)
                    }
                    updateControls()
                }
            }.show()
    }

    private fun offerForget() {
        val peer = activePeer ?: return
        AlertDialog
            .Builder(this)
            .setTitle(R.string.nearby_forget)
            .setMessage(R.string.nearby_forget_detail)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.nearby_forget) { _, _ ->
                if (NearbyRadio.state.value.peer == peer) NearbyRadio.stop()
                NearbyStore.forget(this, account, peer)
                clearPeer()
            }.show()
    }

    private fun send() {
        val peer = activePeer ?: return
        val text = editor.text.toString()
        if (!permitted(peer) || submitted || text.isBlank()) return
        if (!NearbyWire.validText(text)) {
            toast(R.string.nearby_too_long)
            return
        }
        submitted = true
        updateControls()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { NearbyStore.enqueuePeer(applicationContext, account, peer, text) }
                if (activePeer == peer && editor.text.toString() == text) editor.setText("")
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                toast(R.string.nearby_read_error)
            } finally {
                submitted = false
                updateControls()
            }
        }
    }
}
