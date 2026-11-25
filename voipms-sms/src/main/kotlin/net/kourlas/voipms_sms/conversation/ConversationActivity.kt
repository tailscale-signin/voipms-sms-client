/*
 * VoIP.ms SMS
 * Copyright (C) 2015-2023 Michael Kourlas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.conversation

import net.kourlas.voipms_sms.contacts.LocalContacts
import net.kourlas.voipms_sms.contacts.ContactNameDialog
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.util.Linkify
import android.util.Log
import net.kourlas.voipms_sms.tools.LineProfiles
import net.kourlas.voipms_sms.tools.MessageActions
import net.kourlas.voipms_sms.tools.ConversationSpeech
import net.kourlas.voipms_sms.tools.ThreadReminders
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import net.kourlas.voipms_sms.ui.MessageBackCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.core.text.toSpannable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.lookup.NumberOrigin
import net.kourlas.voipms_sms.lookup.OnlineNumberLookup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.rich.ui.ChatRoute
import net.kourlas.voipms_sms.rich.ui.ResumedChatFeed
import net.kourlas.voipms_sms.rich.ui.ThreadRoutes
import net.kourlas.voipms_sms.rich.ui.RoomLink
import net.kourlas.voipms_sms.rich.ui.ThreadChatEvent
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.CustomApplication
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversations.ConversationsActivity
import net.kourlas.voipms_sms.conversations.ConversationsArchivedActivity
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.demo.getDemoNotification
import net.kourlas.voipms_sms.newConversation.NewConversationActivity
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.getDidShowInConversationsView
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getMessageTextBoxMaximumSize
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.sms.destinationSupport
import net.kourlas.voipms_sms.sms.DestinationSupport
import net.kourlas.voipms_sms.sms.workers.Outbox
import net.kourlas.voipms_sms.utils.hasOversizedSmsUrl
import net.kourlas.voipms_sms.ui.FastScroller
import net.kourlas.voipms_sms.utils.abortActivity
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.preferences.ShareSuggestions
import net.kourlas.voipms_sms.utils.getContactName
import net.kourlas.voipms_sms.utils.getContactPhotoBitmap
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import net.kourlas.voipms_sms.utils.getMessageTexts
import net.kourlas.voipms_sms.utils.registerNonExportedReceiver
import net.kourlas.voipms_sms.utils.safeUnregisterReceiver
import net.kourlas.voipms_sms.utils.showAlertDialog
import net.kourlas.voipms_sms.utils.showErrorMessage
import net.kourlas.voipms_sms.utils.showPermissionSnackbar
import net.kourlas.voipms_sms.utils.showSnackbar
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "ConversationActivity"

/**
 * Activity used to display messages in a single conversation.
 */
open class ConversationActivity(val bubble: Boolean = false) :
    AppCompatActivity(), ActionMode.Callback,
    ActivityCompat.OnRequestPermissionsResultCallback {
    // UI elements
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConversationRecyclerViewAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private val messageBack = MessageBackCallback()
    private var actionMode: ActionMode?
        get() = messageBack.selection
        set(value) { messageBack.selection = value }

    // The DID and contact associated with this conversation
    private lateinit var did: String
    private lateinit var contact: String
    val conversationId: ConversationId
        get() = ConversationId(did, contact)

    private var smsWatcher: TextWatcher? = null
    private var draftRead: Job? = null
    private var lastDraftWrite: Job? = null
    private var privateDictationPending = false
    private var keepReviewedDraft = false
    private val dictate = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val wasPrivate = privateDictationPending
        privateDictationPending = false
        if (::did.isInitialized && ::contact.isInitialized && result.resultCode == RESULT_OK &&
            data != null && data.getStringExtra("thread") == conversationId.getId()) {
            val editor = routes?.editor ?: findViewById<EditText>(R.id.message_edit_text)
            val text = data.getStringExtra(ConversationSpeech.RESULT).orEmpty()
            if (text.isNotBlank()) {
                keepReviewedDraft = true
                editor.text.insert(editor.selectionStart.coerceAtLeast(0), text)
            }
        } else if (wasPrivate) finish()
    }

    // Additional metadata associated with the DID and contact, including the
    // name and photo of the person associated with the DID or contact
    private var contactName: String? = null
    private var offlineRegion: String? = null
    private var contactJob: kotlinx.coroutines.Job? = null
    private data class ContactPresentation(val name: String?, val photo: Bitmap, val region: String?)

    // Broadcast receivers
    private val syncCompleteReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Show error in snackbar if one occurred
                intent?.getStringExtra(
                    getString(
                        R.string.sync_complete_error
                    )
                )?.let {
                    showSnackbar(
                        this@ConversationActivity,
                        R.id.coordinator_layout, it
                    )
                }

                // Refresh adapter to show new messages
                if (::adapter.isInitialized) {
                    adapter.refresh()
                }
            }
        }
    private val sentMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Show error in snackbar if one occurred
            intent?.getStringExtra(
                getString(
                    R.string.sent_message_error
                )
            )?.let {
                showSnackbar(
                    this@ConversationActivity,
                    R.id.coordinator_layout, it
                )
            }

            if (::adapter.isInitialized) {
                // Refresh adapter to show message was sent
                adapter.refresh()

                // Scroll to the bottom of the adapter so that the message is
                // in view
                if (adapter.itemCount > 0) {
                    layoutManager.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }
    }

    private lateinit var richFeed: ResumedChatFeed
    private var routes: ThreadRoutes? = null
    private var fileRoom: RoomLink? = null
    private var selectedFile: android.net.Uri? = null
    private var fileThread: String? = null
    private var fileGeneration: Long? = null
    private fun clearRichFile() {
        fileRoom = null; selectedFile = null; fileThread = null; fileGeneration = null
    }
    private fun finishRichFile() {
        val uri = selectedFile
        val target = fileRoom
        val valid = target != null && fileThread == conversationId.getId() &&
            fileGeneration == readAccountCredentials(this)?.generation && target == routes?.bindingForMedia()
        clearRichFile()
        if (privateSession && !valid) { finish(); return }
        findViewById<View>(android.R.id.content).visibility = View.VISIBLE
        if (valid && uri != null) routes?.attach(uri, target)
    }
    private val richFileGate = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (::did.isInitialized && ::contact.isInitialized && it.resultCode == RESULT_OK && fileRoom != null) finishRichFile()
        else { clearRichFile(); finish() }
    }
    private val richFilePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (fileRoom == null || !::did.isInitialized || !::contact.isInitialized) return@registerForActivityResult
        selectedFile = uri
        if (privateSession) {
            @Suppress("DEPRECATION")
            val proof = getSystemService(android.app.KeyguardManager::class.java)
                .createConfirmDeviceCredentialIntent(getString(R.string.private_threads), null)
            if (proof == null || uri == null) { clearRichFile(); finish() } else richFileGate.launch(proof)
        } else finishRichFile()
    }
    private fun pickRichFile() {
        fileRoom = routes?.bindingForMedia() ?: return
        fileThread = conversationId.getId()
        fileGeneration = readAccountCredentials(this)?.generation
        richFilePicker.launch(arrayOf("*/*"))
    }
    internal fun openRichMedia(event: ThreadChatEvent) { routes?.open(event) }

    private var privateSession = false
    private var resumedConversation: ConversationId? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        richFeed = ResumedChatFeed(this)
        onBackPressedDispatcher.addCallback(this, messageBack)
        setContentView(R.layout.conversation)
        val list = findViewById<View>(R.id.list)
        findViewById<View>(R.id.message_section).addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) ViewCompat.requestApplyInsets(list)
        }
        mmsPickerDraft = savedInstanceState?.getString("mms_picker_draft")
        onNewIntent(intent)
        if (!privateSession && savedInstanceState != null && ::contact.isInitialized &&
            savedInstanceState.getString("rich_picker_thread") == conversationId.getId()) {
            val binding = routes?.bindingForMedia()
            if (binding != null && binding.owner == savedInstanceState.getString("rich_picker_owner") &&
                binding.room == savedInstanceState.getString("rich_picker_room") && binding.peer == savedInstanceState.getString("rich_picker_peer")) {
                fileRoom = binding; fileThread = conversationId.getId()
                fileGeneration = savedInstanceState.getLong("rich_picker_generation")
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                OnlineNumberLookup.updates.collect {
                    if (::contact.isInitialized) updateContactHeading()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val wasResumed = resumedConversation != null
        leaveVisibleConversation()
        val previous = if (::adapter.isInitialized) conversationId else null
        val previousAnchor = this.intent.getLongExtra(net.kourlas.voipms_sms.tools.MessageSearch.ANCHOR, -1)
        clearRichFile()
        setIntent(intent)
        if (intent.hasExtra(net.kourlas.voipms_sms.delivery.CallAlerts.GENERATION) &&
            intent.getLongExtra(net.kourlas.voipms_sms.delivery.CallAlerts.GENERATION, -1) != readAccountCredentials(this)?.generation) {
            finish()
            return
        }

        if (!setupDidAndContact(intent)) {
            return
        }
        privateSession = net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(this, conversationId)
        if (privateSession) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            if (!net.kourlas.voipms_sms.privacy.PrivateThreads.consume(conversationId)) {
                findViewById<View>(android.R.id.content).visibility = View.INVISIBLE
                startActivity(Intent(this, net.kourlas.voipms_sms.privacy.PrivateThreadGateActivity::class.java)
                    .putExtra("target", Intent(intent)).putExtra("did", did).putExtra("peer", contact))
                finish()
                return
            }
        }
        draftRead?.cancel()
        contactJob?.cancel()
        val editor = findViewById<EditText>(R.id.message_edit_text)
        smsWatcher?.let(editor::removeTextChangedListener)
        routes?.close()
        val replaceAdapter = previous != conversationId ||
            previousAnchor != intent.getLongExtra(net.kourlas.voipms_sms.tools.MessageSearch.ANCHOR, -1)
        if (replaceAdapter && previous != null) actionMode?.finish()
        if (previous != null && previous != conversationId) {
            androidx.lifecycle.ViewModelProvider(this)[net.kourlas.voipms_sms.delivery.VoicePlaybackModel::class.java].stop()
            editor.text.clear()
            keepReviewedDraft = false
        }
        contactName = null
        offlineRegion = null
        setupToolbar()
        if (replaceAdapter) setupRecyclerView()
        setupMessageText()
        getMessageTextFromIntent()
        setupSendButton()
        val sharedMedia = net.kourlas.voipms_sms.media.IncomingShare.uris(this, intent)
        if (sharedMedia.isNotEmpty()) {
            // Consume the share exactly once. The selected draft survives recreation.
            intent.clipData = null
            intent.removeExtra(Intent.EXTRA_STREAM)
            mmsComposer.addUris(sharedMedia)
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) loadContactPresentation()
        findViewById<View>(R.id.chat_route).visibility = if (BuildConfig.IS_DEMO) View.GONE else View.VISIBLE
        routes = if (BuildConfig.IS_DEMO) null else ThreadRoutes(this, conversationId, richFeed, ::pickRichFile,
            { mmsComposer.attachments.isNotEmpty() }, adapter::updateChat)
        setupDemo()

        if (wasResumed) enterVisibleConversation()
        if (ShareSuggestions.enabled(this, conversationId) && !PrivateThreads.isPrivate(this, conversationId)) {
            ShortcutManagerCompat.reportShortcutUsed(applicationContext, conversationId.getId())
        }
    }

    /**
     * Retrieve and store the DID and contact from the intent, as well as
     * additional metadata such as the name and photo.
     *
     * @param intent The intent that this activity was launched with.
     */
    private fun setupDidAndContact(intent: Intent): Boolean {
        if (net.kourlas.voipms_sms.media.IncomingShare.isShare(intent)) {
            // Generic text share; forward to NewConversationActivity
            val shortcutId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
                } else {
                    intent.getStringExtra("android.intent.extra.shortcut.ID")
                }
            val parts = shortcutId?.split("_")
            val target = parts?.takeIf { it.size == 2 }?.let { ConversationId(normalizeVoipMsDid(it[0]), it[1]) }
            if (target == null || !ShareSuggestions.enabled(this, target) || PrivateThreads.isPrivate(this, target) ||
                target.did !in getDids(applicationContext)
            ) {
                val newIntent = Intent()
                newIntent.action = intent.action
                newIntent.type = intent.type
                net.kourlas.voipms_sms.media.IncomingShare.forward(this, intent, newIntent)
                newIntent.putExtra(
                    Intent.EXTRA_TEXT,
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                )
                newIntent.component = ComponentName(
                    applicationContext,
                    NewConversationActivity::class.java
                )
                startActivity(newIntent)
                finish()
                return false
            } else {
                did = target.did
                contact = target.contact
            }
        } else {
            // Standard intent
            val d =
                intent.getStringExtra(getString(R.string.conversation_did))
            val c = intent.getStringExtra(
                getString(
                    R.string.conversation_contact
                )
            )
            if (d == null || c == null) {
                abortActivity(
                    this,
                    Exception(
                        "No DID or contact specified:" +
                            " did: '$d', contact: '$c'"
                    )
                )
                return false
            } else {
                did = normalizeVoipMsDid(d)
                contact = c
            }
        }

        // We shouldn't show a conversation for a DID that is no longer
        // configured
        if (did !in getDids(applicationContext) && !BuildConfig.IS_DEMO) {
            abortActivity(this, Exception("DID '$did' no longer exists"))
            return false
        }

        // We shouldn't show a conversation for a DID that is not configured to
        // be displayed in the conversation view
        if (!getDidShowInConversationsView(
                applicationContext,
                did
            ) && !BuildConfig.IS_DEMO
        ) {
            abortActivity(
                this,
                Exception("DID '$did' not displayed in conversation view")
            )
            return false
        }

        // Remove the leading one from a North American phone number
        // (e.g. +1 (123) 555-4567)
        if (contact.length == 11 && contact[0] == '1') {
            contact = contact.substring(1)
        }

        return true
    }

    /** Resolve Contacts and decode the avatar off the notification launch path. */
    private fun loadContactPresentation() {
        val target = conversationId
        val size = resources.getDimensionPixelSize(R.dimen.contact_badge)
        val locale = resources.configuration.locales[0]
        contactJob?.cancel()
        contactJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val name = if (BuildConfig.IS_DEMO) net.kourlas.voipms_sms.demo.getContactName(target.contact)
                           else LocalContacts.find(applicationContext, target.contact) ?: getContactName(applicationContext, target.contact)
                val region = if (name == null) NumberOrigin.offline(target.contact, locale) else null
                ContactPresentation(name, getContactPhotoBitmap(applicationContext, name, target.contact, size), region)
            }
            if (target != conversationId) return@launch
            contactName = result.name
            offlineRegion = result.region
            adapter.updateContactPhoto(result.photo)
            updateContactHeading()
            invalidateOptionsMenu()
        }
    }

    /**
     * Sets up the activity toolbar.
     */
    open fun setupToolbar() {
        // Set up toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.let {
            it.setHomeButtonEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
        }

        updateContactHeading()
        invalidateOptionsMenu()

        if (!BuildConfig.IS_DEMO) {
            findViewById<Toolbar>(R.id.toolbar).setOnClickListener { editLocalContact() }
        }

        // Allow contact phone number to be copied by long clicking the toolbar
        findViewById<Toolbar>(R.id.toolbar).setOnLongClickListener {
            val clipboard = getSystemService(
                CLIPBOARD_SERVICE
            ) as ClipboardManager
            val clip = ClipData.newPlainText(
                getString(R.string.conversation_contact_clipboard_description),
                getFormattedPhoneNumber(contact)
            )
            clipboard.setPrimaryClip(clip)
            Toast.makeText(
                this, getString(
                    R.string.conversation_copied_contact_toast_message
                ),
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        actionMode = null
    }

    internal fun editLocalContact() {
        ContactNameDialog.show(this, contact) { loadContactPresentation() }
    }

    private fun updateContactHeading() {
        val label = OnlineNumberLookup.label(this, contact, contactName)
        supportActionBar?.apply {
            val number = getFormattedPhoneNumber(contact)
            title = contactName ?: if (label == null) number else OnlineNumberLookup.blueNumber(this@ConversationActivity, contact, toolbar = true)
            subtitle = when {
                contactName != null -> number
                label != null -> OnlineNumberLookup.description(this@ConversationActivity, label)
                else -> offlineRegion?.let { getString(R.string.lookup_offline_region, it) }
            }
        }
    }

    /**
     * Sets up the activity recycler view.
     */
    private var callFeedObservation: kotlinx.coroutines.Job? = null

    private fun setupRecyclerView() {
        // Set up recycler view with most recent message at bottom of list
        val firstSetup = !::recyclerView.isInitialized
        if (firstSetup) {
            layoutManager = LinearLayoutManager(this)
            layoutManager.orientation = RecyclerView.VERTICAL
            layoutManager.stackFromEnd = true
            recyclerView = findViewById(R.id.list)
            recyclerView.layoutManager = layoutManager
        }
        adapter = ConversationRecyclerViewAdapter(
            this,
            recyclerView,
            layoutManager,
            conversationId
        )
        recyclerView.setHasFixedSize(true)
        recyclerView.adapter = adapter
        val target = conversationId
        val targetAdapter = adapter
        callFeedObservation?.cancel()
        if (!BuildConfig.IS_DEMO) callFeedObservation = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                net.kourlas.voipms_sms.delivery.CallFeedSync.refresh(applicationContext)
                net.kourlas.voipms_sms.delivery.CallFeedStore.observe(applicationContext, target)
                    .catch { error ->
                        if (error !is Exception || error is CancellationException) throw error
                        threadReadFailure(DiagnosticAction.THREAD_CALLS, error)
                    }
                    .collect { if (adapter === targetAdapter) targetAdapter.updateCalls(it) }
            }
        }
        if (!firstSetup) return
        recyclerView.addOnScrollListener(object :
                                             RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: RecyclerView, dx: Int,
                dy: Int
            ) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy < 0 && !adapter.loadingMoreItems
                    && layoutManager.findFirstVisibleItemPosition() in 0..ConversationRecyclerViewAdapter.START_LOAD_INDEX.toInt()
                ) {
                    adapter.loadMoreItems()
                }
            }

        })

        val composer = findViewById<View>(R.id.message_section)
        val margin = resources.getDimensionPixelSize(R.dimen.margin)
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val insets =
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(
                insets.left,
                v.paddingTop,
                insets.right,
                insets.bottom + composer.height + margin
            )
            WindowInsetsCompat.CONSUMED
        }

        // Set up fast scroller
        val frameLayout = findViewById<FrameLayout>(R.id.frame_layout)
        FastScroller.addTo(
            recyclerView, frameLayout.overlay, FastScroller.POSITION_RIGHT_SIDE
        )
    }

    /**
     * Sets up the activity message text container.
     */
    private fun setupMessageText() {
        // Set up container for message text box
        val messageSection = findViewById<LinearLayout>(R.id.message_section)
        ViewCompat.setElevation(
            messageSection,
            resources.getDimension(R.dimen.send_message_elevation)
        )
        messageSection.clipToOutline = true
        ViewCompat.setOnApplyWindowInsetsListener(messageSection) { v, windowInsets ->
            val insets =
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val margin = resources.getDimensionPixelSize(R.dimen.margin)
            v.updateLayoutParams<MarginLayoutParams> {
                val gutter = resources.getDimensionPixelSize(R.dimen.thread_gutter)
                leftMargin = gutter + insets.left
                rightMargin = gutter + insets.right
                bottomMargin = margin + insets.bottom
            }
            WindowInsetsCompat.CONSUMED
        }

        // Set up message text box
        val messageEditText = findViewById<EditText>(R.id.message_edit_text)
        val target = conversationId
        smsWatcher = object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence, start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence, start: Int, before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(s: Editable) =
                onMessageTextChange(target, s.toString())
        }.also(messageEditText::addTextChangedListener)
        messageEditText.onFocusChangeListener =
            View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    adapter.refresh()
                }
            }
        val intentMessageText = intent.getStringExtra(
            getString(R.string.conversation_extra_message_text)
        )
        if (intentMessageText != null) {
            messageEditText.setText(intentMessageText)
            messageEditText.setSelection(messageEditText.text.length)
        }
        val intentFocus = intent.getBooleanExtra(
            getString(R.string.conversation_extra_focus), false
        )
        if (intentFocus) {
            messageEditText.requestFocus()
        }
    }

    /**
     * Retrieves and stores the message text from the intent.
     */
    private fun getMessageTextFromIntent() {
        if (net.kourlas.voipms_sms.media.IncomingShare.isShare(intent)) {
            val messageText = findViewById<EditText>(
                R.id.message_edit_text
            )
            if (intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                messageText.setText(intent.getStringExtra(Intent.EXTRA_TEXT))
                messageText.requestFocus()
                messageText.setSelection(messageText.text.length)
            }
        }
    }

    /**
     * Called when the message text box text changes.
     */
    private fun onMessageTextChange(target: ConversationId, newText: String) {
        val maxLength = applicationContext.resources.getInteger(
            R.integer.sms_max_length
        )
        val charsRemainingTextView = findViewById<TextView>(
            R.id.chars_remaining_text
        )

        val parts = getMessageTexts(this, newText)
        val msgsCount = parts.size
        val bytesCount = parts.last().toByteArray().size

        if (msgsCount == 1) {
            if (bytesCount >= maxLength - 10) {
                // Show "N" when there are N characters left in the first
                // message and N <= 10
                charsRemainingTextView.visibility = View.VISIBLE
                charsRemainingTextView.text = "${maxLength - bytesCount}"
            } else {
                // Show nothing
                charsRemainingTextView.visibility = View.GONE
            }
        } else {
            // Show "N / M" when there are M messages and M >= 2; N is the
            // number of characters left in the current message
            charsRemainingTextView.visibility = View.VISIBLE
            charsRemainingTextView.text = getString(
                R.string.conversation_char_rem,
                maxLength - bytesCount, msgsCount
            )
        }

        val previous = lastDraftWrite
        lastDraftWrite = CustomApplication.getApplication().applicationScope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                Database.getInstance(applicationContext).updateConversationDraft(target, newText)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { threadReadFailure(DiagnosticAction.THREAD_DRAFT, e) }
        }
    }

    /**
     * Sets up the activity send button.
     */
    private lateinit var mmsComposer: net.kourlas.voipms_sms.media.MmsComposer
    private var mmsPickerDraft: String? = null
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mms_picker_draft", mmsPickerDraft)
        if (!privateSession) fileRoom?.let {
            outState.putString("rich_picker_owner", it.owner); outState.putString("rich_picker_room", it.room)
            outState.putString("rich_picker_peer", it.peer); outState.putString("rich_picker_thread", fileThread)
            fileGeneration?.let { generation -> outState.putLong("rich_picker_generation", generation) }
        }
    }
    private val mmsPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (::mmsComposer.isInitialized && mmsComposer.draftKey == mmsPickerDraft) mmsComposer.addUris(uris)
        mmsPickerDraft = null
    }

    private fun setupSendButton() {
        val generation = readAccountCredentials(applicationContext)?.generation ?: 0
        mmsComposer = net.kourlas.voipms_sms.media.MmsComposer(this, "$generation/$did/$contact") {
            mmsPickerDraft = mmsComposer.draftKey
            mmsPicker.launch(arrayOf("image/*", "video/*", "audio/*"))
        }

        // Set up send button
        val sendButton = findViewById<ImageButton>(R.id.send_button)
        sendButton.setOnClickListener {
            this@ConversationActivity.sendMessage()
        }
        sendButton.setOnLongClickListener { scheduleComposer(); true }
    }

    /**
     * Sets up the conversation activity for demo mode.
     */
    private fun setupDemo() {
        if (BuildConfig.IS_DEMO) {
            Notifications.getInstance(applicationContext).showDemoNotification(
                getDemoNotification()
            )
        }
    }

    /**
     * Called after the user clicks the send message button.
     */
    private var enqueueInProgress = false

    private fun scheduleComposer() {
        OutboxUi(this).pickTime { sendMessage(it, preview = true) }
    }

    private fun sendMessage(scheduled: Long? = null, preview: Boolean = false, asMms: Boolean = false) {
        if (enqueueInProgress || routes?.route?.let { it != ChatRoute.SMS } == true) return
        val editor = findViewById<EditText>(R.id.message_edit_text)
        val text = editor.text.toString()
        val media = mmsComposer.attachments.toList()
        if ((text.isBlank() && media.isEmpty()) || !mmsComposer.validate(text)) return
        val account = readAccountCredentials(this)?.takeIf { it.configured } ?: return
        if (did !in getDids(this)) return
        val smsParts = if (media.isEmpty()) getMessageTexts(this, text) else listOf(text)
        val mms = asMms || media.isNotEmpty()
        val parts = if (mms) listOf(text) else smsParts
        val send = {
            if (!enqueueInProgress) {
                enqueueInProgress = true
                lifecycleScope.launch {
                    try {
                        val due = scheduled ?: (System.currentTimeMillis() + Outbox.UNDO_MS)
                        val batch = Outbox.enqueue(applicationContext, conversationId, text, media, account.generation, due, asMms = mms)
                        if (editor.text.toString() == text) editor.setText("")
                        if (mmsComposer.attachments == media) mmsComposer.clear()
                        adapter.refresh()
                        if (adapter.itemCount > 0) layoutManager.scrollToPosition(adapter.itemCount - 1)
                        showSnackbar(this@ConversationActivity, R.id.coordinator_layout,
                            getString(if (scheduled == null) R.string.outbox_saved else R.string.outbox_scheduled),
                            getString(R.string.outbox_undo), View.OnClickListener {
                                lifecycleScope.launch {
                                    if (Outbox.cancel(applicationContext, batch)) {
                                        if (editor.text.isEmpty()) editor.setText(text)
                                        if (mmsComposer.attachments.isEmpty()) mmsComposer.restore(media)
                                        adapter.refresh()
                                    } else Toast.makeText(this@ConversationActivity, R.string.outbox_too_late, Toast.LENGTH_LONG).show()
                                }
                            }, Outbox.UNDO_MS.toInt())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to queue message", e)
                        showErrorMessage(this@ConversationActivity, R.id.coordinator_layout, getString(R.string.conversation_send_fail))
                    } finally {
                        enqueueInProgress = false
                    }
                }
            }
        }
        val destination = destinationSupport(did, contact)
        if (preview || smsParts.size > 1 || mms || destination != DestinationSupport.STANDARD) {
            val transport = if (!mms) {
                val bytes = text.toByteArray().size
                val sizeLabel = resources.getQuantityString(R.plurals.outbox_utf8_bytes, bytes, bytes)
                resources.getQuantityString(R.plurals.outbox_sms_parts, parts.size, parts.size, sizeLabel)
            } else if (media.isEmpty()) {
                val characters = text.codePointCount(0, text.length)
                resources.getQuantityString(R.plurals.outbox_text_mms, characters, characters)
            } else {
                val bytes = media.sumOf { net.kourlas.voipms_sms.media.MmsMedia.localFile(this, it).length() }
                val sizeLabel = resources.getQuantityString(R.plurals.outbox_attachment_bytes, bytes.toInt(), bytes)
                resources.getQuantityString(R.plurals.outbox_mms_parts, media.size, media.size, sizeLabel)
            }
            val detail = buildString {
                val rate = SendPreflight.estimate(this@ConversationActivity, account.generation, conversationId, mms, parts.size)
                append(getString(R.string.outbox_preflight, getFormattedPhoneNumber(did), getFormattedPhoneNumber(contact), transport, rate))
                if (!mms && hasOversizedSmsUrl(text, resources.getInteger(R.integer.sms_max_length)))
                    append("\n\n").append(getString(R.string.outbox_long_url))
                when (destination) {
                    DestinationSupport.UNSUPPORTED -> append("\n\n").append(getString(R.string.outbox_destination_unsupported))
                    DestinationSupport.SHORT_CODE -> append("\n\n").append(getString(R.string.outbox_destination_short))
                    DestinationSupport.UNKNOWN -> append("\n\n").append(getString(R.string.outbox_destination_unknown))
                    else -> Unit
                }
                if (scheduled != null) append("\n\n").append(getString(R.string.outbox_schedule_policy))
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.outbox_details)
                .setMessage(detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.outbox_rate_title) { _, _ ->
                    SendPreflight.editRate(this, account.generation, conversationId, mms) {
                        sendMessage(scheduled, preview = true, asMms = mms)
                    }
                }
            var previewDialog: androidx.appcompat.app.AlertDialog? = null
            if (media.isEmpty() && smsParts.size > 1 && text.codePointCount(0, text.length) <= net.kourlas.voipms_sms.media.MmsMedia.MAX_CAPTION) {
                dialog.setView(android.widget.Button(this).apply {
                    this.text = if (mms) getString(R.string.outbox_choose_sms, smsParts.size) else getString(R.string.outbox_choose_mms)
                    setOnClickListener {
                        previewDialog?.dismiss()
                        sendMessage(scheduled, preview = true, asMms = !mms)
                    }
                })
            }
            if (destination != DestinationSupport.UNSUPPORTED) {
                dialog.setPositiveButton(if (scheduled == null) R.string.outbox_queue_send else R.string.outbox_schedule_action) { _, _ -> send() }
            }
            previewDialog = dialog.show()
        } else send()
    }

    override fun onResume() {
        super.onResume()
        enterVisibleConversation()
    }

    private fun enterVisibleConversation() {
        if (isFinishing || !::adapter.isInitialized || resumedConversation != null) return
        val target = conversationId
        resumedConversation = target
        routes?.refresh()
        // Register all dynamic receivers for this activity
        registerNonExportedReceiver(
            this,
            syncCompleteReceiver,
            IntentFilter(getString(R.string.sync_complete_action)),
        )
        registerNonExportedReceiver(
            this,
            sentMessageReceiver,
            IntentFilter(
                applicationContext.getString(
                    R.string.sent_message_action, did, contact
                )
            )
        )

        loadContactPresentation()

        // Load draft message
        val messageText = findViewById<EditText>(R.id.message_edit_text)
        val restoreDraft = !keepReviewedDraft
        keepReviewedDraft = false
        val beforeRestore = messageText.text.toString()
        val pendingSave = lastDraftWrite
        draftRead?.cancel()
        draftRead = lifecycleScope.launch {
            try {
                val draftMessage = withContext(Dispatchers.IO) {
                    pendingSave?.join()
                    Database.getInstance(applicationContext).getConversationDraft(target)
                }
                if (target == conversationId && !isFinishing && restoreDraft && draftMessage != null &&
                    lastDraftWrite === pendingSave && messageText.text.toString() == beforeRestore && draftMessage.text != beforeRestore) {
                    messageText.setText(draftMessage.text)
                    messageText.requestFocus()
                    messageText.setSelection(messageText.text.length)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { threadReadFailure(DiagnosticAction.THREAD_DRAFT, e) }
        }

        // Set max lines
        messageText.maxLines = getMessageTextBoxMaximumSize(this)

        // Mark the conversation as read and cancel any open notifications
        // related to this conversation (unless we are displaying in bubble
        // mode)
        if (!bubble) {
            Notifications.getInstance(applicationContext).cancelNotification(
                conversationId
            )
        } else {
            Notifications.getInstance(applicationContext).validateGroupNotification()
        }
        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Default) {
            try {
                Database.getInstance(applicationContext).markConversationRead(target)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { threadReadFailure(DiagnosticAction.THREAD_READ_STATE, e) }
        }
        adapter.refresh()

        (application as CustomApplication).startVisibleRefresh()
        // Track number of activities
        if (!bubble) {
            (application as CustomApplication)
                .conversationActivityIncrementCount(target)
        }
    }

    private fun threadReadFailure(source: DiagnosticAction, error: Exception) {
        DebugJournal.record(applicationContext, source, DiagnosticStep.FAILED, error = error)
    }

    override fun onPause() {
        super.onPause()
        leaveVisibleConversation()
    }

    private fun leaveVisibleConversation() {
        draftRead?.cancel()
        val target = resumedConversation ?: return
        resumedConversation = null
        // Unregister all dynamic receivers for this activity
        safeUnregisterReceiver(this, syncCompleteReceiver)
        safeUnregisterReceiver(this, sentMessageReceiver)

        (application as CustomApplication).stopVisibleRefresh()
        // Track number of activities
        if (!bubble) {
            (application as CustomApplication)
                .conversationActivityDecrementCount(target)
        }
    }

    override fun onStop() {
        super.onStop()
        if (privateSession && fileRoom != null) findViewById<View>(android.R.id.content).visibility = View.INVISIBLE
        else if (privateSession && !privateDictationPending && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        routes?.close()
        super.onDestroy()
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Create standard options menu
        val inflater = menuInflater
        if (isFinishing || !::adapter.isInitialized) return false
        inflater.inflate(R.menu.conversation, menu)

        val editor = routes?.editor ?: findViewById<EditText>(R.id.message_edit_text)
        val smsRoute = routes?.route?.let { it == ChatRoute.SMS } ?: true
        val composeTools = menu.addSubMenu(R.string.tools_composer)
        if (smsRoute && !BuildConfig.IS_DEMO && !PrivateThreads.isPrivate(this, conversationId)) {
            composeTools.add(R.string.handoff_title).setOnMenuItemClickListener {
                net.kourlas.voipms_sms.companion.CompanionDraftHandoff(this, editor, conversationId).show()
                true
            }
        }
        composeTools.add(R.string.tools_templates).setOnMenuItemClickListener { MessageActions.templates(this, did, editor); true }
        composeTools.add(R.string.tools_translate).setOnMenuItemClickListener {
            val original = editor.text.toString()
            MessageActions.translation(this, original) { translated ->
                if (editor.text.toString() == original) { editor.setText(translated); editor.setSelection(editor.length()) }
            }
            true
        }
        composeTools.add(R.string.tools_dictate).setOnMenuItemClickListener {
            privateDictationPending = privateSession
            dictate.launch(ConversationSpeech.dictate(this).putExtra("thread", conversationId.getId()).putExtra("private", privateSession)); true
        }
        composeTools.add(R.string.tools_remind).setOnMenuItemClickListener { ThreadReminders.choose(this, conversationId); true }
        composeTools.add(R.string.tools_line_profile).setOnMenuItemClickListener { LineProfiles.edit(this, did) { invalidateOptionsMenu() }; true }
        if (intent.hasExtra(net.kourlas.voipms_sms.tools.MessageSearch.ANCHOR)) {
            menu.add(R.string.tools_open_thread).setOnMenuItemClickListener {
                intent.removeExtra(net.kourlas.voipms_sms.tools.MessageSearch.ANCHOR)
                adapter.refresh(""); invalidateOptionsMenu(); true
            }
        }
        if (!BuildConfig.IS_DEMO && smsRoute) {
            menu.add(R.string.outbox_schedule_action).setOnMenuItemClickListener { scheduleComposer(); true }
            menu.add(R.string.outbox_details).setOnMenuItemClickListener { sendMessage(preview = true); true }
            menu.add(R.string.outbox_title).setOnMenuItemClickListener {
                OutboxUi(this) { adapter.refresh() }.show(conversationId); true
            }
        }
        if (!BuildConfig.IS_DEMO) {
            menu.add(R.string.spam_trust).setOnMenuItemClickListener {
                val id = conversationId
                lifecycleScope.launch {
                    Database.getInstance(applicationContext).moveToInboxFolder(listOf(id), false)
                    Toast.makeText(this@ConversationActivity, R.string.spam_trusted, Toast.LENGTH_SHORT).show()
                }
                true
            }
            menu.add(if (privateSession) R.string.private_unlock else R.string.private_lock).setOnMenuItemClickListener {
                net.kourlas.voipms_sms.privacy.PrivateThreads.configure(this, conversationId)
                true
            }
        }
        menu.findItem(R.id.search_button).setOnActionExpandListener(messageBack)
        menu.findItem(R.id.lookup_number).isVisible = !BuildConfig.IS_DEMO && OnlineNumberLookup.available(contact, contactName)

        menu.findItem(R.id.suggest_in_share).apply {
            isVisible = !PrivateThreads.isPrivate(this@ConversationActivity, conversationId)
            isChecked = ShareSuggestions.enabled(this@ConversationActivity, conversationId)
            setOnMenuItemClickListener {
                isChecked = !isChecked
                ShareSuggestions.setEnabled(this@ConversationActivity, conversationId, isChecked)
                CustomApplication.getApplication().applicationScope.launch {
                    Database.getInstance(applicationContext).updateShortcuts()
                }
                true
            }
        }
        // Replace DID menu item with DID
        val didMenuItem = menu.findItem(R.id.did_button)
        didMenuItem.title = getString(
            R.string.conversation_action_did,
            LineProfiles.label(this, did)
        )
        didMenuItem.setOnMenuItemClickListener {
            val clipboard = getSystemService(
                CLIPBOARD_SERVICE
            ) as ClipboardManager
            val clip = ClipData.newPlainText(
                getString(R.string.conversation_did_clipboard_description),
                getFormattedPhoneNumber(did)
            )
            clipboard.setPrimaryClip(clip)
            Toast.makeText(
                this, getString(
                    R.string.conversation_copied_did_toast_message
                ),
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        // Hide the call button on devices without telephony support
        if (!packageManager
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        ) {
            val phoneMenuItem = menu.findItem(R.id.call_button)
            phoneMenuItem.isVisible = false
        }

        // Hide the notifications button on devices running versions of Android
        // prior to Oreo
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val notificationsItem = menu.findItem(R.id.notifications_button)
            notificationsItem.isVisible = false
        }

        // Hide the bubble button if we cannot bubble this conversation, or we
        // are already in a bubble.
        if (bubble
            || !Notifications.getInstance(applicationContext)
                .canBubble(did, contact)
        ) {
            val bubbleItem = menu.findItem(R.id.bubble_button)
            bubbleItem.isVisible = false
        }

        // Hide certain menu options if we are in a bubble, but show a menu
        // option that opens the full view.
        if (bubble) {
            val notificationItem = menu.findItem(R.id.notifications_button)
            notificationItem.isVisible = false
            val exportItem = menu.findItem(R.id.export_button)
            exportItem.isVisible = false
        }

        // Configure the search box to trigger adapter filtering when the
        // text changes
        val searchView = menu.findItem(
            R.id.search_button
        ).actionView as SearchView
        searchView.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean = false

                override fun onQueryTextChange(newText: String): Boolean {
                    adapter.refresh(newText)
                    return true
                }
            })

        // Set cursor color and hint text
        val searchAutoComplete = searchView.findViewById<
            TextView>(
            androidx.appcompat.R.id.search_src_text
        )
        searchAutoComplete.hint = getString(R.string.conversation_action_search)
        searchAutoComplete.setTextColor(
            ContextCompat.getColor(
                applicationContext, android.R.color.white
            )
        )
        searchAutoComplete.setHintTextColor(
            ContextCompat.getColor(
                applicationContext, R.color.search_hint
            )
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val field = TextView::class.java.getDeclaredField(
                    "mCursorDrawableRes"
                )
                field.isAccessible = true
                field.set(searchAutoComplete, R.drawable.search_cursor)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set search cursor drawable", e)
            }
        } else {
            searchAutoComplete.setTextCursorDrawable(R.drawable.search_cursor)
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> return onUpButtonClick()
            R.id.lookup_number -> {
                OnlineNumberLookup.offer(this, contact)
                return true
            }

            R.id.call_button -> {
                onCallButtonClick()
                return true
            }

            R.id.notifications_button -> {
                onNotificationsButtonClick()
                return true
            }

            R.id.bubble_button -> {
                onBubbleButtonClick()
                return true
            }

            R.id.export_button -> {
                onExportButtonClick()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    /**
     * Handles the up button.
     */
    private fun onUpButtonClick(): Boolean {
        // Launch the conversation if we are in a bubble.
        if (bubble) {
            val intent = Intent(this, ConversationActivity::class.java)
            intent.putExtra(
                getString(
                    R.string.conversation_did
                ), did
            )
            intent.putExtra(
                getString(
                    R.string.conversation_contact
                ), contact
            )
            val stackBuilder = TaskStackBuilder.create(this)
            stackBuilder.addNextIntentWithParentStack(intent)
            stackBuilder.startActivities()
            return false
        }

        // Override standard "up" behaviour because this activity
        // has multiple parents
        lifecycleScope.launch(Dispatchers.Default) {
            val clazz = if (Database.getInstance(applicationContext)
                    .isConversationArchived(conversationId)
            ) {
                ConversationsArchivedActivity::class.java
            } else {
                ConversationsActivity::class.java
            }

            ensureActive()

            withContext(Dispatchers.Main) {
                val intent = Intent(applicationContext, clazz)
                // Simulate normal behaviour of up button with these particular
                // flags
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        }
        return true
    }

    /**
     * Handles the call button.
     */
    private fun onCallButtonClick() {
        val intent = Intent(Intent.ACTION_CALL)
        intent.data = "tel:$contact".toUri()

        // Before trying to call the contact's phone number, request the
        // CALL_PHONE permission
        val permission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't yet have the permission, so request it; if granted,
            // this method will be called again
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                0
            )
        } else {
            // We have the permission
            try {
                startActivity(intent)
            } catch (ignored: SecurityException) {
                // Do nothing.
            }
        }
    }

    /**
     * Handles the notifications button.
     */
    private fun onNotificationsButtonClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notifications.getInstance(applicationContext)
                .createDidNotificationChannel(
                    did, contact
                )

            val intent = Intent(
                Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            )
            intent.putExtra(
                Settings.EXTRA_APP_PACKAGE,
                packageName
            )
            intent.putExtra(
                Settings.EXTRA_CHANNEL_ID,
                getString(
                    R.string.notifications_channel_contact,
                    did, contact
                )
            )
            startActivity(intent)
        }
    }

    /**
     * Handles the bubble button.
     */
    private fun onBubbleButtonClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            moveTaskToBack(true)
            lifecycleScope.launch(Dispatchers.Default) {
                Notifications.getInstance(applicationContext)
                    .showNotifications(
                        setOf(conversationId),
                        bubbleOnly = true,
                        autoLaunchBubble = true
                    )
            }
        }
    }

    /**
     * Handles the export button.
     */
    private fun onExportButtonClick() {
        ThreadExportActivity.open(this, conversationId)
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val inflater = mode.menuInflater
        inflater.inflate(R.menu.conversation_secondary, menu)
        menu.add(0, R.id.tools_message_actions, Menu.NONE, R.string.tools_message_actions)
            .setOnMenuItemClickListener {
                val selected = adapter.messageItems.singleOrNull { it.checked }?.message ?: return@setOnMenuItemClickListener true
                mode.finish()
                MessageActions.selected(this, selected)
                true
            }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
        false

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem
    ): Boolean {
        when (item.itemId) {
            R.id.resend_button -> {
                onResendButtonClick(mode)
                return true
            }

            R.id.info_button -> {
                onInfoButtonClick(mode)
                return true
            }

            R.id.copy_button -> {
                onCopyButtonClick(mode)
                return true
            }

            R.id.share_button -> {
                onShareButtonClick(mode)
                return true
            }

            R.id.delete_button -> {
                onDeleteButtonClick(mode)
                return true
            }
        }

        return false
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        // Uncheck all items
        for (i in 0 until adapter.itemCount) {
            adapter[i]?.setChecked(false, i)
        }
        actionMode = null
    }

    /**
     * Handles the resend button.
     */
    private fun onResendButtonClick(mode: ActionMode) {
        val message = adapter.messageItems.firstOrNull { it.checked }?.message
        mode.finish()
        if (message != null) OutboxUi(this) { adapter.refresh() }.reviewMessage(message)
    }

    /**
     * Handles the info button.
     */
    private fun onInfoButtonClick(mode: ActionMode) {
        // Get first checked item
        val message: Message? = adapter.messageItems
            .firstOrNull { it.checked }
            ?.message

        // Display info dialog for that item
        if (message != null) {
            val dateFormat = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
            )

            val dialogText = SpannableStringBuilder()

            if (message.voipId != null) {
                dialogText.append(
                    getString(
                        R.string.conversation_info_id, message.voipId
                    )
                )
                dialogText.append("\n")
            }

            if (message.isIncoming) {
                dialogText.append(
                    getString(
                        R.string.conversation_info_to, getFormattedPhoneNumber(
                            message.did
                        )
                    )
                )
                dialogText.append("\n")
                dialogText.append(
                    getString(
                        R.string.conversation_info_from,
                        getFormattedPhoneNumber(
                            message.contact
                        )
                    )
                )
                dialogText.append("\n")
            } else {
                dialogText.append(
                    getString(
                        R.string.conversation_info_to, getFormattedPhoneNumber(
                            message.contact
                        )
                    )
                )
                dialogText.append("\n")
                dialogText.append(
                    getString(
                        R.string.conversation_info_from,
                        getFormattedPhoneNumber(
                            message.did
                        )
                    )
                )
                dialogText.append("\n")
            }

            dialogText.append(
                getString(
                    R.string.conversation_info_date, dateFormat.format(
                        message.date
                    )
                )
            )

            if (message.medias.isNotEmpty()) dialogText.append("\n").append(message.mediaDisplayText)

            Linkify.addLinks(dialogText, Linkify.WEB_URLS)

            showAlertDialog(
                this,
                getString(R.string.conversation_info_title),
                dialogText.toSpannable(),
                getString(R.string.ok), null, null, null
            )
        }

        mode.finish()
    }

    /**
     * Handles the copy button.
     */
    private fun onCopyButtonClick(mode: ActionMode) {
        // Get first checked item
        val message: Message? = adapter.messageItems
            .firstOrNull { it.checked }
            ?.message

        // Copy text of message to clipboard
        if (message != null) {
            val clipboard = getSystemService(
                CLIPBOARD_SERVICE
            ) as ClipboardManager
            val clip = ClipData.newPlainText(
                getString(R.string.conversation_message_clipboard_description),
                message.fullDisplayText
            )
            clipboard.setPrimaryClip(clip)
        }

        mode.finish()
    }

    /**
     * Handles the share button.
     */
    private fun onShareButtonClick(mode: ActionMode) {
        // Get first checked item
        val message: Message? = adapter.messageItems
            .firstOrNull { it.checked }
            ?.message

        if (message != null) net.kourlas.voipms_sms.media.IncomingShare.share(this, message)

        mode.finish()
    }

    /**
     * Handles the delete button.
     */
    private fun onDeleteButtonClick(mode: ActionMode) {
        // Get the messages that are checked.
        val messages = adapter.messageItems
            .filter { it.checked }
            .map { it.message }
        mode.finish()

        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Default) {
            try {
                // Delete each message.
                for (message in messages) {
                    Database.getInstance(applicationContext)
                        .deleteMessage(
                            message.did, message.databaseId,
                            message.voipId
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete messages", e)
                // Some rows may have changed before the exception.
                lifecycleScope.launch(Dispatchers.Main) {
                    adapter.refresh()
                }
                showErrorMessage(
                    this@ConversationActivity,
                    R.id.coordinator_layout,
                    getString(R.string.conversation_delete_fail)
                )
                return@launch
            }

            lifecycleScope.launch(Dispatchers.Main) {
                adapter.refresh()

                showSnackbar(
                    this@ConversationActivity,
                    R.id.coordinator_layout,
                    resources.getQuantityString(
                        R.plurals.conversation_message_deleted,
                        messages.size,
                        messages.size
                    ),
                    getString(R.string.undo),
                    {
                        CustomApplication.getApplication().applicationScope.launch(
                            Dispatchers.Default
                        ) {
                            try {
                                // Restore the messages.
                                Database.getInstance(applicationContext)
                                    .insertMessagesDatabase(messages)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to restore messages", e)
                                // Some rows may have changed before the exception.
                                lifecycleScope.launch(Dispatchers.Main) {
                                    adapter.refresh()
                                }
                                showErrorMessage(
                                    this@ConversationActivity,
                                    R.id.coordinator_layout,
                                    getString(R.string.conversation_restore_fail)
                                )
                                return@launch
                            }

                            lifecycleScope.launch(Dispatchers.Main) {
                                adapter.refresh()
                            }
                        }
                    })
            }
        }
    }

    fun onItemClick(view: View) {
        val row = recyclerView.findContainingItemView(view) ?: return
        val item = adapter[recyclerView.getChildAdapterPosition(row)] ?: return
        if (actionMode != null) {
            toggleItem(row)
        } else if (!item.message.isIncoming && !item.message.isDelivered) {
            OutboxUi(this) { adapter.refresh() }.reviewMessage(item.message)
        } else {
            val date = row.findViewById<TextView>(R.id.date)
            date.visibility = if (date.isGone) View.VISIBLE else View.GONE
        }
    }

    fun onItemLongClick(view: View) {
        recyclerView.findContainingItemView(view)?.let(::toggleItem)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions,
            grantResults
        )
        if (requestCode == 0) {
            permissions.indices
                .filter { permissions[it] == Manifest.permission.CALL_PHONE }
                .forEach {
                    if (grantResults[it] == PackageManager.PERMISSION_GRANTED) {
                        // If the permission request was granted, try calling
                        // again
                        onCallButtonClick()
                    } else {
                        // Otherwise, show a warning
                        showPermissionSnackbar(
                            this,
                            R.id.coordinator_layout,
                            getString(R.string.conversation_perm_denied_call)
                        )
                    }
                }
        }
    }

    /**
     * Toggles the checked status of the specified view.
     */
    private fun toggleItem(view: View) {
        // Inform the adapter that the item should be checked
        val position = recyclerView.getChildAdapterPosition(view)
        if (position != RecyclerView.NO_POSITION) {
            adapter[position]?.toggle(position) ?: return
        }

        // Turn on or off the action mode depending on how many items are
        // checked
        if (adapter.getCheckedItemCount() == 0) {
            actionMode?.finish()
            actionMode = null
            return
        }
        if (actionMode == null) {
            actionMode = startSupportActionMode(this)
        }

        // If the action mode is enabled, update the visible buttons to match
        // the number of checked items
        updateActionModeButtons()
    }

    /**
     * Update the visible action mode buttons to match the number of checked
     * items.
     */
    private fun updateActionModeButtons() {
        actionMode?.let {
            // The resend button should only be visible if there is a single item
            // checked and that item is in the failed to deliver state
            val resendAction = it.menu.findItem(R.id.resend_button)
            val count = adapter.getCheckedItemCount()
            it.menu.findItem(R.id.tools_message_actions)?.isVisible = count == 1
            resendAction.isVisible = adapter.singleOrNull { item ->
                item.checked && !item.message.isDelivered
                    && !item.message.isDeliveryInProgress
            } != null

            // Certain buttons should not be visible if there is more than one
            // item visible
            val copyAction = it.menu.findItem(R.id.copy_button)
            val shareAction = it.menu.findItem(R.id.share_button)
            val infoAction = it.menu.findItem(R.id.info_button)

            infoAction.isVisible = count < 2
            copyAction.isVisible = count < 2
            shareAction.isVisible = count < 2
        }
    }

    @JsonClass(generateAdapter = true)
    data class ExportableMessages(val messages: List<Message>)
}
