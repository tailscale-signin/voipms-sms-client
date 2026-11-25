/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2021 Michael Kourlas
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

package net.kourlas.voipms_sms.conversations

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.TextView
import net.kourlas.voipms_sms.ui.MessageBackCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import net.kourlas.voipms_sms.lookup.OnlineNumberLookup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.snackbar.Snackbar
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.CustomApplication
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.newConversation.NewConversationActivity
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.accountConfigured
import net.kourlas.voipms_sms.preferences.activities.AccountPreferencesActivity
import net.kourlas.voipms_sms.preferences.activities.MarkdownPreferencesActivity
import net.kourlas.voipms_sms.preferences.activities.PreferencesActivity
import net.kourlas.voipms_sms.preferences.activities.SynchronizationPreferencesActivity
import net.kourlas.voipms_sms.preferences.didsConfigured
import net.kourlas.voipms_sms.preferences.firstRun
import net.kourlas.voipms_sms.preferences.getActiveDid
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getEmail
import net.kourlas.voipms_sms.preferences.getFirstSyncAfterSignIn
import net.kourlas.voipms_sms.preferences.getSetupCompletedForVersion
import net.kourlas.voipms_sms.preferences.getStartDate
import net.kourlas.voipms_sms.preferences.setActiveDid
import net.kourlas.voipms_sms.preferences.setFirstSyncAfterSignIn
import net.kourlas.voipms_sms.preferences.setSetupCompletedForVersion
import net.kourlas.voipms_sms.signIn.SignInActivity
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.sms.workers.SyncWorker
import net.kourlas.voipms_sms.utils.applyCircularMask
import net.kourlas.voipms_sms.utils.enablePushNotifications
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import net.kourlas.voipms_sms.utils.registerNonExportedReceiver
import net.kourlas.voipms_sms.utils.safeUnregisterReceiver
import net.kourlas.voipms_sms.utils.showErrorMessage
import net.kourlas.voipms_sms.utils.showPermissionSnackbar
import net.kourlas.voipms_sms.utils.showSnackbar
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "ConversationsActivity"
internal const val OPEN_NOTIFICATION_MESSAGES = "open_messages"

/**
 * Activity that contains a generic list of conversations.
 */
open class ConversationsActivity(val archived: Boolean = false) :
    AppCompatActivity(),
    ActionMode.Callback, View.OnClickListener,
    View.OnLongClickListener,
    ActivityCompat.OnRequestPermissionsResultCallback {
    // UI elements
    private lateinit var recyclerView: RecyclerView
    protected lateinit var adapter: ConversationsRecyclerViewAdapter<
        ConversationsActivity>
    private val remindersListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && !key.startsWith("since_") && ::adapter.isInitialized) adapter.refresh()
    }
    private val privacyListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (::adapter.isInitialized) {
            actionMode?.finish()
            adapter.invalidatePrivacy()
        }
    }
    private val messageBack = MessageBackCallback()
    private var actionMode: ActionMode?
        get() = messageBack.selection
        set(value) { messageBack.selection = value }
    private enum class InboxTab(val label: Int, val icon: Int) {
        HOME(R.string.inbox_home, R.drawable.ic_tab_home),
        MESSAGES(R.string.inbox_main, R.drawable.ic_tab_messages),
        OTHER(R.string.inbox_other, R.drawable.ic_tab_other)
    }
    private var selectedTab = InboxTab.HOME
    private val showOther get() = selectedTab == InboxTab.OTHER
    private val homeFeed by lazy {
        net.kourlas.voipms_sms.delivery.HomeCallFeed(this, findViewById(R.id.call_home_container))
    }
    private var visibleRefreshRegistered = false
    private var screenResumed = false
    private var accountCheck: Job? = null
    private var homeFeedVisible = false
    private var inboxSearchItem: MenuItem? = null
    private var countedVisibleList = false
    private fun isMessagesTab() = !archived && selectedTab == InboxTab.MESSAGES
    private var privateInbox = false
    private val vaultGesture by lazy {
        net.kourlas.voipms_sms.privacy.PrivateInboxGesture(156f * resources.displayMetrics.density)
    }
    private var capturedVaultPull = false
    private val vaultBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closePrivateInbox()
    }
    private data class UnlockScope(val account: Long, val activeLine: String, val visibleLines: Set<String>)
    private var unlockScope: UnlockScope? = null
    private fun currentUnlockScope() = readAccountCredentials(this)?.generation?.let {
        UnlockScope(it, getActiveDid(this), getDids(this, onlyShowInConversationsView = true).toSet())
    }
    private val unlockInbox = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        val requested = unlockScope
        unlockScope = null
        if (requested != null && requested == currentUnlockScope() && it.resultCode == RESULT_OK && !isFinishing && isMessagesTab()) {
            DebugJournal.record(this, DiagnosticAction.NAVIGATION, DiagnosticStep.PRIVATE)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            privateInbox = true
            vaultBack.isEnabled = true
            supportActionBar?.subtitle = getString(R.string.private_threads)
            adapter.switchPrivateView(true)
            clearInboxSearch()
            invalidateOptionsMenu()
        }
    }

    @Suppress("DEPRECATION")
    private fun togglePrivateInbox() {
        if (privateInbox) { closePrivateInbox(); return }
        if (!isMessagesTab() || archived || actionMode != null) return
        val proof = getSystemService(android.app.KeyguardManager::class.java).createConfirmDeviceCredentialIntent(
            getString(R.string.private_threads), getString(R.string.private_pull_hint))
        if (proof == null) android.widget.Toast.makeText(this, R.string.private_no_credential, android.widget.Toast.LENGTH_LONG).show()
        else {
            unlockScope = currentUnlockScope() ?: return
            unlockInbox.launch(proof)
        }
    }

    private fun closePrivateInbox() {
        if (!privateInbox) return
        privateInbox = false
        vaultBack.isEnabled = false
        actionMode?.finish()
        supportActionBar?.subtitle = null
        adapter.switchPrivateView(false)
        clearInboxSearch()
        invalidateOptionsMenu()
        // Keep this Activity's recents snapshot protected until the Activity is recreated.
    }

    private fun clearInboxSearch() {
        val item = inboxSearchItem ?: return
        (item.actionView as? SearchView)?.setQuery("", false)
        if (item.isActionViewExpanded) item.collapseActionView()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val kind = event.actionMasked
        if (kind == android.view.MotionEvent.ACTION_DOWN) {
            capturedVaultPull = false
            val bounds = android.graphics.Rect()
            val eligible = ::recyclerView.isInitialized && !archived && isMessagesTab() && actionMode == null &&
                !recyclerView.canScrollVertically(-1) && recyclerView.getGlobalVisibleRect(bounds) &&
                bounds.contains(event.x.toInt(), event.y.toInt())
            vaultGesture.start(event.x, event.y, eligible)
        } else if (kind == android.view.MotionEvent.ACTION_MOVE && !capturedVaultPull &&
            event.pointerCount == 1 && vaultGesture.move(event.x, event.y)) {
            val cancel = android.view.MotionEvent.obtain(event)
            cancel.action = android.view.MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            capturedVaultPull = true
            recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } else if (kind == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            vaultGesture.cancel()
        }
        if (capturedVaultPull) {
            if (kind == android.view.MotionEvent.ACTION_UP) {
                capturedVaultPull = false
                togglePrivateInbox()
            }
            if (kind == android.view.MotionEvent.ACTION_CANCEL) capturedVaultPull = false
            return true
        }
        if (kind == android.view.MotionEvent.ACTION_UP || kind == android.view.MotionEvent.ACTION_CANCEL) vaultGesture.cancel()
        return super.dispatchTouchEvent(event)
    }



    // Mapping between items in the navigation menu and their corresponding DIDs
    private lateinit var navViewMenuItemDidMap: HashMap<MenuItem, String>

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
                        this@ConversationsActivity,
                        R.id.coordinator_layout, it
                    )
                }

                // Turn off refresh icon if this was a complete sync
                if (intent?.getBooleanExtra(
                        getString(
                            R.string.sync_complete_full
                        ), false
                    ) == true
                ) {
                    val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(
                        R.id.swipe_refresh_layout
                    )
                    swipeRefreshLayout.isRefreshing = false
                    adapter.initialSync(false)
                }

                // Refresh adapter to show new messages
                if (::adapter.isInitialized) {
                    adapter.refresh()
                }
            }
        }
    private val pushNotificationsRegistrationCompleteReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getStringArrayListExtra(
                    getString(
                        R.string.push_notifications_reg_complete_failed_dids
                    )
                )
                    ?.let {
                        if (it.isNotEmpty()) {
                            // Some DIDs failed registration
                            showSnackbar(
                                this@ConversationsActivity,
                                R.id.coordinator_layout,
                                getString(
                                    R.string.push_notifications_fail_register
                                )
                            )
                        }
                    } ?: run {
                    // Unknown error
                    showSnackbar(
                        this@ConversationsActivity,
                        R.id.coordinator_layout,
                        getString(R.string.push_notifications_fail_unknown)
                    )
                }

                // Regardless of whether an error occurred, mark setup as
                // complete
                setSetupCompletedForVersion(
                    this@ConversationsActivity,
                    BuildConfig.VERSION_CODE.toLong()
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, messageBack)
        onBackPressedDispatcher.addCallback(this, vaultBack)
        setContentView(R.layout.conversations)
        selectedTab = when {
            archived -> InboxTab.MESSAGES
            savedInstanceState != null -> InboxTab.entries.firstOrNull { it.name == savedInstanceState.getString("inbox_tab") }
                ?: if (savedInstanceState.getBoolean("showOther")) InboxTab.OTHER else InboxTab.MESSAGES
            else -> tabForIntent(intent) ?: InboxTab.HOME
        }

        setupToolbar()
        setupRecyclerViewAndSwipeRefreshLayout()
        setupInboxTabs()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                OnlineNumberLookup.updates.collect { adapter.refreshLookupLabels() }
            }
        }
        setupNewConversationButton()
        setupPermissions()
        setupNavigationView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (privateInbox) clearInboxSearch()
        outState.putString("inbox_tab", selectedTab.name)
        super.onSaveInstanceState(outState)
    }

    private fun tabForIntent(incoming: Intent?): InboxTab? = when {
        incoming?.getBooleanExtra(net.kourlas.voipms_sms.delivery.CallAlerts.SHOW_CALL_HOME, false) == true -> InboxTab.HOME
        incoming?.dataString == "voipms-sms://notifications/inbox" ||
            incoming?.getBooleanExtra(OPEN_NOTIFICATION_MESSAGES, false) == true -> InboxTab.MESSAGES
        else -> null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        unlockScope = null
        if (!archived) tabForIntent(intent)?.let { tab ->
            closePrivateInbox()
            findViewById<TabLayout>(R.id.inbox_tabs).getTabAt(tab.ordinal)?.select()
        }
    }

    private fun setupInboxTabs() {
        if (archived) return
        val tabs = findViewById<TabLayout>(R.id.inbox_tabs)
        tabs.visibility = View.VISIBLE
        InboxTab.entries.forEach { tab ->
            tabs.addTab(tabs.newTab().setText(tab.label), selectedTab == tab)
        }
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@ConversationsActivity, R.font.proxima_nova_medium)
            textSize = resources.getDimension(R.dimen.inbox_tab_text_size)
        }
        val widestLabel = InboxTab.entries.maxOf { labelPaint.measureText(getString(it.label)) }
        tabs.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width <= 0 || width == oldRight - oldLeft) return@addOnLayoutChangeListener
            val density = resources.displayMetrics.density
            val slot = width.toFloat() / InboxTab.entries.size
            val labelsFit = widestLabel + 12 * density <= slot
            val iconsFit = widestLabel + 44 * density <= slot && resources.configuration.fontScale <= 1.2f
            val mode = if (labelsFit) TabLayout.MODE_FIXED else TabLayout.MODE_SCROLLABLE
            if (tabs.tabMode != mode) {
                tabs.tabMode = mode
                tabs.tabGravity = if (labelsFit) TabLayout.GRAVITY_FILL else TabLayout.GRAVITY_START
            }
            for (entry in InboxTab.entries) {
                val tab = tabs.getTabAt(entry.ordinal) ?: continue
                if (iconsFit && tab.icon == null) tab.setIcon(entry.icon)
                else if (!iconsFit && tab.icon != null) tab.setIcon(null)
            }
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                actionMode?.finish()
                if (isMessagesTab()) closePrivateInbox()
                unlockScope = null
                selectedTab = InboxTab.entries.getOrNull(tab.position) ?: return
                DebugJournal.record(this@ConversationsActivity, DiagnosticAction.NAVIGATION, when (selectedTab) {
                    InboxTab.HOME -> DiagnosticStep.HOME
                    InboxTab.MESSAGES -> DiagnosticStep.MESSAGES
                    InboxTab.OTHER -> DiagnosticStep.OTHER
                })
                if (selectedTab == InboxTab.HOME) inboxSearchItem?.takeIf { it.isActionViewExpanded }?.collapseActionView()
                updateTabContent()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        updateTabContent()
    }

    private fun updateTabContent() {
        val home = !archived && selectedTab == InboxTab.HOME
        findViewById<View>(R.id.call_home_container).visibility = if (home) View.VISIBLE else View.GONE
        findViewById<View>(R.id.inbox_content).visibility = if (home) View.GONE else View.VISIBLE
        updateComposeVisibility()
        if (!home && screenResumed) adapter.showFolder(showOther) else adapter.hide()
        setHomeFeedVisible(home && screenResumed)
        markMessageListVisible(!home && screenResumed)
    }

    private fun markMessageListVisible(visible: Boolean) {
        if (visible == countedVisibleList) return
        countedVisibleList = visible
        val app = application as CustomApplication
        if (visible) app.conversationsActivityIncrementCount() else app.conversationsActivityDecrementCount()
    }

    private fun updateComposeVisibility() {
        val available = !archived && selectedTab != InboxTab.HOME &&
            (BuildConfig.IS_DEMO || didsConfigured(applicationContext))
        findViewById<View>(R.id.chat_button).visibility = if (available) View.VISIBLE else View.GONE
    }

    private fun setHomeFeedVisible(visible: Boolean) {
        if (visible == homeFeedVisible) return
        homeFeedVisible = visible
        if (visible) homeFeed.show() else homeFeed.hide()
    }

    override fun onStop() {
        closePrivateInbox()
        setHomeFeedVisible(false)
        super.onStop()
    }

    /**
     * Sets up the activity toolbar.
     */
    private fun setupToolbar() {
        // Set up toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.let {
            if (archived) {
                it.title = getString(R.string.conversations_archived_name)
            }
            it.setDisplayHomeAsUpEnabled(true)
            it.setHomeAsUpIndicator(R.drawable.ic_menu_toolbar_24dp)
        }
    }

    /**
     * Sets up the activity recycler view and swipe refresh layout.
     */
    private fun setupRecyclerViewAndSwipeRefreshLayout() {
        val layoutManager = LinearLayoutManager(this)
        layoutManager.orientation = RecyclerView.VERTICAL
        recyclerView = findViewById(R.id.list)
        adapter = ConversationsRecyclerViewAdapter(
            this, recyclerView,
            layoutManager
        )
        recyclerView.setHasFixedSize(true)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                RecyclerViewSwipeDecorator.Builder(
                    c, recyclerView, viewHolder,
                    dX, dY, actionState,
                    isCurrentlyActive
                )
                    .addSwipeLeftBackgroundColor(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.archive_swipe
                        )
                    )
                    .addSwipeLeftActionIcon(
                        if (archived) R.drawable.ic_unarchive_toolbar_24dp
                        else R.drawable.ic_archive_toolbar_24dp
                    )
                    .addSwipeRightBackgroundColor(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.delete_swipe
                        )
                    )
                    .addSwipeRightActionIcon(R.drawable.ic_delete_toolbar_24dp)
                    .create()
                    .decorate()
                super.onChildDraw(
                    c, recyclerView, viewHolder, dX, dY,
                    actionState, isCurrentlyActive
                )
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(
                viewHolder: RecyclerView.ViewHolder,
                direction: Int
            ) {
                val message = adapter.displayedItem(viewHolder.itemView)?.message ?: return
                when (direction) {
                    ItemTouchHelper.LEFT ->
                        if (archived) unarchiveConversations(listOf(message)) else archiveConversations(listOf(message))
                    ItemTouchHelper.RIGHT -> deleteConversations(listOf(message))
                }
            }
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(
            recyclerView
        )

        val listPadding = recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val systemBarsInsets =
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                systemBarsInsets.left,
                v.paddingTop,
                systemBarsInsets.right,
                listPadding + systemBarsInsets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(
            R.id.swipe_refresh_layout
        )
        swipeRefreshLayout.setOnRefreshListener {
            adapter.refresh()
            SyncWorker.performFullSynchronization(applicationContext)
        }
        swipeRefreshLayout.setColorSchemeResources(R.color.swipe_refresh_icon)
    }

    /**
     * Sets up the new conversation floating action button.
     */
    private fun setupNewConversationButton() {
        if (archived) {
            // Remove new conversation button
            findViewById<View>(R.id.chat_button).visibility = View.GONE
        } else {
            findViewById<FloatingActionButton>(R.id.chat_button).let {
                updateComposeVisibility()
                it.setOnClickListener {
                    val newConversationIntent = Intent(
                        this, NewConversationActivity::class.java
                    )
                    startActivity(newConversationIntent)
                }
                ViewCompat.setOnApplyWindowInsetsListener(it) { v, windowInsets ->
                    val insets =
                        windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    val margin = resources.getDimensionPixelSize(R.dimen.margin)
                    v.updateLayoutParams<MarginLayoutParams> {
                        leftMargin = margin + insets.left
                        rightMargin = margin + insets.right
                        bottomMargin = margin + insets.bottom
                    }
                    WindowInsetsCompat.CONSUMED
                }
            }
        }
    }

    /**
     * Requests the contacts permission.
     */
    private fun setupPermissions() {
        // Notifications permission is required to send notifications
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Contacts permission is required to get contact names and photos
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CONTACTS
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(android.Manifest.permission.READ_CONTACTS)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, permissions.toTypedArray(), 0
            )
        }
    }

    /**
     * Sets up the navigation view.
     */
    private fun setupNavigationView() {
        navViewMenuItemDidMap = HashMap()
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navigationView = findViewById<NavigationView>(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener { menuItem ->
            // Filter the list of conversations to the selected DID
            drawerLayout.closeDrawer(GravityCompat.START)
            navViewMenuItemDidMap[menuItem]?.let {
                val lineChanged = getActiveDid(applicationContext) != it
                if (lineChanged) {
                    actionMode?.finish()
                    closePrivateInbox()
                    unlockScope = null
                }
                setActiveDid(applicationContext, it)
                if (lineChanged && homeFeedVisible) {
                    setHomeFeedVisible(false)
                    setHomeFeedVisible(true)
                }
                menuItem.isChecked = true
                adapter.refresh()
            }
            true
        }
        navigationView.getHeaderView(0).setOnClickListener {
            // If an account is configured, show the account screen; otherwise
            // show the sign-in screen
            if (accountConfigured(applicationContext)) {
                startActivity(
                    Intent(this, AccountPreferencesActivity::class.java)
                )
            } else {
                startActivity(Intent(this, SignInActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        net.kourlas.voipms_sms.tools.ThreadReminders.observe(this, remindersListener, true)
        net.kourlas.voipms_sms.privacy.PrivateThreads.observe(this, privacyListener, true)
        screenResumed = true

        // Register dynamic receivers for this activity
        registerNonExportedReceiver(
            this,
            syncCompleteReceiver,
            IntentFilter(getString(R.string.sync_complete_action))
        )
        registerNonExportedReceiver(
            this,
            pushNotificationsRegistrationCompleteReceiver,
            IntentFilter(
                getString(
                    R.string.push_notifications_reg_complete_action
                )
            )
        )

        // Perform initial setup as well as account and DID check
        performAccountDidCheck()
        val firstSyncRequired = performInitialSetup()

        // Validate the selected line before starting the local load. Full network sync can run independently.
        updateNavigationView()
        updateTabContent()
        if (!visibleRefreshRegistered) {
            (application as CustomApplication).startVisibleRefresh(immediate = !firstSyncRequired)
            visibleRefreshRegistered = true
        }

        // Refresh on resume just in case the contacts permission was newly
        // granted and we need to add the contact names and photos
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CONTACTS
            )
            == PackageManager.PERMISSION_GRANTED
        ) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            CustomApplication.getApplication().applicationScope.launch(
                Dispatchers.Default
            ) {
                try {
                    Database.getInstance(applicationContext).updateShortcuts()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    DebugJournal.record(applicationContext, DiagnosticAction.INBOX_SCREEN, DiagnosticStep.FAILED, error = error)
                }
            }
        }

        // Delete any notification channels and groups that are no longer
        // needed and rename existing channels if necessary
        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Default) {
            try {
                val notifications = Notifications.getInstance(applicationContext)
                notifications.createDefaultNotificationChannel()
                notifications.deleteNotificationChannelsAndGroups()
                notifications.renameNotificationChannels()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                DebugJournal.record(applicationContext, DiagnosticAction.INBOX_SCREEN, DiagnosticStep.FAILED, error = error)
            }
        }

        // Turn off the refreshing indicator in the SwipeRefreshLayout in
        // case we missed a "sync complete" notification
        if (!firstSyncRequired) {
            val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(
                R.id.swipe_refresh_layout
            )
            swipeRefreshLayout.isRefreshing = false
            adapter.initialSync(false)
        }
    }

    /**
     * Performs a check for a configured account and DIDs, and forces the user
     * to configure an account where appropriate.
     */
    private fun performAccountDidCheck() {
        // If there are no DIDs available and the user has not configured an
        // account, then force the user to configure an account
        accountCheck?.cancel()
        fun needsSetup() = firstRun(this) && !didsConfigured(this) && !accountConfigured(this)
        if (!needsSetup()) return
        accountCheck = lifecycleScope.launch {
            try {
                val noStoredLines = withContext(Dispatchers.IO) { Database.getInstance(applicationContext).getDids().isEmpty() }
                if (noStoredLines && screenResumed && !isFinishing && needsSetup()) {
                    startActivity(Intent(this@ConversationsActivity, SignInActivity::class.java)
                        .putExtra(SignInActivity.EXTRA_FIRST_RUN, true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                DebugJournal.record(this@ConversationsActivity, DiagnosticAction.NAVIGATION, DiagnosticStep.FAILED, error = error)
                showSnackbar(this@ConversationsActivity, R.id.coordinator_layout, getString(R.string.inbox_load_error),
                    getString(R.string.inbox_load_retry), { performAccountDidCheck() })
            }
        }
    }

    /**
     * Performs initial setup following sign-in or upgrade.
     */
    private fun performInitialSetup(): Boolean {
        // After the user configures an account, do an initial synchronization
        // with the SwipeRefreshLayout refresh icon
        val firstSyncRequired = getFirstSyncAfterSignIn(this)
        if (getFirstSyncAfterSignIn(this)) {
            val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(
                R.id.swipe_refresh_layout
            )
            swipeRefreshLayout.isRefreshing = true
            SyncWorker.performFullSynchronization(applicationContext)

            adapter.initialSync(true)

            val format = SimpleDateFormat(
                "MMM d, yyyy",
                Locale.getDefault()
            )
            val date = format.format(getStartDate(this))
            showSnackbar(
                this, R.id.coordinator_layout,
                getString(R.string.conversations_sync_date_suggestion, date),
                getString(R.string.change),
                {
                    startActivity(
                        Intent(
                            this,
                            SynchronizationPreferencesActivity::class.java
                        )
                    )
                },
                Snackbar.LENGTH_INDEFINITE
            )

            setFirstSyncAfterSignIn(this, false)
        }

        // Perform special setup for version 145: need to re-enable push
        // notifications
        if (getSetupCompletedForVersion(this) < 145) {
            enablePushNotifications(
                this.applicationContext,
                activityToShowError = this
            )
        }

        return firstSyncRequired
    }

    /**
     * Updates the navigation view.
     */
    private fun updateNavigationView() {
        val navigationView = findViewById<NavigationView>(R.id.nav_view)

        // Apply circular mask to and remove overlay from picture
        // to match Android Messages aesthetic
        val photo = navigationView.getHeaderView(0).findViewById<ImageView>(
            R.id.photo
        )
        applyCircularMask(photo)

        // Set navigation bar email address
        val email = navigationView.getHeaderView(0).findViewById<TextView>(
            R.id.email
        )
        if (accountConfigured(applicationContext)) {
            email.text = getEmail(applicationContext)
        } else {
            email.text = getString(R.string.conversations_no_account)
        }

        // Set navigation bar DIDs
        val dids = getDids(
            applicationContext,
            onlyShowInConversationsView = true
        )
        navigationView.menu.clear()
        navViewMenuItemDidMap.clear()
        if (dids.isNotEmpty()) {
            var activeDid = getActiveDid(applicationContext)
            if (activeDid !in dids) {
                activeDid = ""
                setActiveDid(applicationContext, activeDid)
            }

            for (did in listOf("") + dids.sorted()) {
                val menuItem = navigationView.menu.add(
                    if (did == "") getString(R.string.conversations_all_dids)
                    else net.kourlas.voipms_sms.tools.LineProfiles.styled(this, did)
                )
                navViewMenuItemDidMap[menuItem] = did
                menuItem.isCheckable = true
                if (activeDid == did) {
                    menuItem.isChecked = true
                }
            }
        } else {
            setActiveDid(applicationContext, "")
        }
    }

    override fun onPause() {
        net.kourlas.voipms_sms.tools.ThreadReminders.observe(this, remindersListener, false)
        net.kourlas.voipms_sms.privacy.PrivateThreads.observe(this, privacyListener, false)
        screenResumed = false
        accountCheck?.cancel()
        adapter.hide()
        closePrivateInbox()
        super.onPause()

        // Unregister all dynamic receivers for this activity
        safeUnregisterReceiver(this, syncCompleteReceiver)
        safeUnregisterReceiver(
            this,
            pushNotificationsRegistrationCompleteReceiver
        )

        // Track number of activities
        if (visibleRefreshRegistered) {
            visibleRefreshRegistered = false
            (application as CustomApplication).stopVisibleRefresh()
        }
        markMessageListVisible(false)
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Create standard options menu
        val inflater = menuInflater
        inflater.inflate(R.menu.conversations, menu)

        if (!BuildConfig.IS_DEMO) {
            menu.add(R.string.chat_rooms).setOnMenuItemClickListener {
                startActivity(Intent(this, net.kourlas.voipms_sms.rich.ui.EncryptedRoomsActivity::class.java)); true
            }
            menu.add(R.string.chat_nearby).setOnMenuItemClickListener {
                startActivity(net.kourlas.voipms_sms.nearby.NearbyActivity.intent(this)); true
            }
            menu.add(R.string.tools_search).setOnMenuItemClickListener { net.kourlas.voipms_sms.tools.MessageSearch.open(this); true }
            menu.add(R.string.tools_snoozed).setOnMenuItemClickListener { net.kourlas.voipms_sms.tools.ThreadReminders.showSnoozed(this); true }
        }
        menu.add(R.string.spam_rules).setOnMenuItemClickListener {
            net.kourlas.voipms_sms.privacy.SpamReview.show(this) { adapter.refresh() }
            true
        }
        if (!archived) menu.add(if (privateInbox) R.string.private_return else R.string.private_threads).setOnMenuItemClickListener {
            if (isMessagesTab()) togglePrivateInbox()
            else startActivity(Intent(this, net.kourlas.voipms_sms.privacy.PrivateThreadGateActivity::class.java))
            true
        }
        inboxSearchItem = menu.findItem(R.id.search_button)
        inboxSearchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (!archived && selectedTab == InboxTab.HOME) {
                    findViewById<TabLayout>(R.id.inbox_tabs).getTabAt(InboxTab.MESSAGES.ordinal)?.select()
                }
                return messageBack.onMenuItemActionExpand(item)
            }
            override fun onMenuItemActionCollapse(item: MenuItem) = messageBack.onMenuItemActionCollapse(item)
        })

        // Configure the search box to trigger adapter filtering when the
        // text changes
        val searchView = menu.findItem(R.id.search_button).actionView
            as SearchView
        searchView.inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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
        searchAutoComplete.hint = getString(R.string.conversations_text_hint)
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

        if (archived) {
            menu.findItem(R.id.global_outbox_button).isVisible = false
            menu.findItem(R.id.archived_button).isVisible = false
            menu.findItem(R.id.message_center_button).isVisible = false
            menu.findItem(R.id.preferences_button).isVisible = false
            menu.findItem(R.id.help_button).isVisible = false
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                val drawerLayout = findViewById<DrawerLayout>(
                    R.id.drawer_layout
                )
                drawerLayout.openDrawer(GravityCompat.START)
                return true
            }

            R.id.archived_button -> {
                val intent = Intent(
                    this,
                    ConversationsArchivedActivity::class.java
                )
                startActivity(intent)
                return true
            }

            R.id.global_outbox_button -> {
                net.kourlas.voipms_sms.conversation.OutboxUi(this).show()
                return true
            }
            R.id.message_center_button -> {
                try {
                    val portal = "https://voip.ms/m/sms.php".toUri()
                    startActivity(Intent(Intent.ACTION_VIEW, portal))
                } catch (_: ActivityNotFoundException) {
                    showSnackbar(
                        this, R.id.coordinator_layout,
                        getString(R.string.message_center_no_browser)
                    )
                }
                return true
            }

            R.id.preferences_button -> {
                val intent = Intent(this, PreferencesActivity::class.java)
                startActivity(intent)
                return true
            }

            R.id.help_button -> {
                val intent =
                    Intent(this, MarkdownPreferencesActivity::class.java)
                intent.putExtra(
                    getString(R.string.preferences_markdown_extra),
                    "HELP"
                )
                startActivity(intent)
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val inflater = mode.menuInflater
        inflater.inflate(R.menu.conversations_secondary, menu)
        menu.findItem(R.id.move_inbox_folder).apply {
            isVisible = !archived && !BuildConfig.IS_DEMO
            setTitle(if (showOther) R.string.inbox_move_main else R.string.inbox_move_other)
        }

        if (archived) {
            // Remove the archive button and replace it with an unarchive button
            menu.findItem(R.id.archive_button).isVisible = false
            menu.findItem(R.id.unarchive_button).isVisible = true
        }

        return true
    }

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu
    ): Boolean = false

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem
    ): Boolean {
        when (item.itemId) {
            R.id.move_inbox_folder -> {
                val selected = adapter.filter { it.checked }.map { it.message.conversationId }
                val destination = !showOther
                mode.finish()
                lifecycleScope.launch {
                    try {
                        Database.getInstance(applicationContext).moveToInboxFolder(selected, destination)
                        Database.getInstance(applicationContext).updateShortcuts()
                        adapter.refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to move conversations", e)
                        showSnackbar(this@ConversationsActivity, R.id.coordinator_layout, getString(R.string.conversations_error_refresh))
                    }
                }
                return true
            }
            R.id.lookup_number -> {
                val selected = adapter.singleOrNull { it.checked } ?: return false
                mode.finish()
                OnlineNumberLookup.offer(this, selected.message.contact)
                return true
            }

            R.id.archive_button -> {
                onArchiveButtonClick(mode)
                return true
            }

            R.id.unarchive_button -> {
                onUnarchiveButtonClick(mode)
                return true
            }

            R.id.mark_read_button -> {
                onMarkReadButtonClick(mode)
                return true
            }

            R.id.mark_unread_button -> {
                onMarkUnreadButtonClick(mode)
                return true
            }

            R.id.delete_button -> {
                onDeleteButtonClick()
                return true
            }
        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        // Uncheck all items
        for (i in 0 until adapter.itemCount) {
            adapter[i].setChecked(false, i)
        }
        actionMode = null
    }

    /**
     * Handles the archive button.
     */
    private fun onArchiveButtonClick(mode: ActionMode) {
        val messages = adapter
            .filter { it.checked }
            .map { it.message }

        archiveConversations(messages, mode)
    }

    /**
     * Handles the mark read button.
     */
    private fun onMarkReadButtonClick(mode: ActionMode) {
        val messages = adapter
            .filter { it.checked }
            .map { it.message }
        mode.finish()

        // Mark all selected conversations as read
        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Default) {
            try {
                messages.forEach {
                        Database.getInstance(applicationContext)
                            .markConversationRead(it.conversationId)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark conversations as read", e)
                // Some rows may have changed before the exception.
                lifecycleScope.launch(Dispatchers.Main) {
                    adapter.refresh()
                }
                showErrorMessage(
                    this@ConversationsActivity,
                    R.id.coordinator_layout,
                    getString(R.string.conversations_mark_read_fail)
                )
                return@launch
            }

            lifecycleScope.launch(Dispatchers.Main) {
                adapter.refresh()
            }
        }
    }

    /**
     * Handles the mark unread button.
     */
    private fun onMarkUnreadButtonClick(mode: ActionMode) {
        val messages = adapter
            .filter { it.checked }
            .map { it.message }
        mode.finish()

        // Mark all selected conversations as unread
        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Default) {
            try {
                messages.forEach {
                        Database.getInstance(applicationContext)
                            .markConversationUnread(it.conversationId)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark conversations as unread", e)
                // Some rows may have changed before the exception.
                lifecycleScope.launch(Dispatchers.Main) {
                    adapter.refresh()
                }
                showErrorMessage(
                    this@ConversationsActivity,
                    R.id.coordinator_layout,
                    getString(R.string.conversations_mark_unread_fail)
                )
                return@launch
            }

            lifecycleScope.launch(Dispatchers.Main) {
                adapter.refresh()
            }
        }
    }

    /**
     * Handles the delete button.
     */
    private fun onDeleteButtonClick() {
        val messages = adapter
            .filter { it.checked }
            .map { it.message }

        deleteConversations(messages)
    }

    /**
     * Handles the unarchive button.
     */
    private fun onUnarchiveButtonClick(mode: ActionMode) {
        val messages = adapter
            .filter { it.checked }
            .map { it.message }

        unarchiveConversations(messages, mode)
    }

    override fun onClick(view: View) {
        if (actionMode != null) {
            // Check or uncheck item when action mode is enabled
            toggleItem(view)
        } else {
            // Open the conversation activity for the selected conversation
            val conversationItem = adapter.displayedItem(view) ?: return
            if (privateInbox) net.kourlas.voipms_sms.privacy.PrivateThreads.grant(conversationItem.message.conversationId)
            val intent = Intent(this, ConversationActivity::class.java)
            intent.putExtra(
                getString(R.string.conversation_did),
                conversationItem.message.did
            )
            intent.putExtra(
                getString(R.string.conversation_contact),
                conversationItem.message.contact
            )
            startActivity(intent)
        }
    }

    override fun onLongClick(view: View): Boolean {
        // On long click, toggle selected item
        toggleItem(view)
        return true
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.indices.filter {
                permissions[it] == android.Manifest.permission.POST_NOTIFICATIONS
            }.forEach {
                if (grantResults[it] != PackageManager.PERMISSION_GRANTED) {
                    // Otherwise, show a warning
                    showPermissionSnackbar(
                        this, R.id.coordinator_layout, getString(
                            R.string.conversations_perm_denied_notifications
                        )
                    )
                }
            }
        }

        permissions.indices
            .filter {
                permissions[it] == android.Manifest.permission.READ_CONTACTS
            }
            .forEach {
                if (grantResults[it] == PackageManager.PERMISSION_GRANTED) {
                    // If the permission request was granted, try refreshing
                    // and loading the contact name and photo
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    CustomApplication.getApplication().applicationScope.launch(
                        Dispatchers.Default
                    ) {
                        Database.getInstance(applicationContext)
                            .updateShortcuts()
                    }
                } else {
                    // Otherwise, show a warning
                    showPermissionSnackbar(
                        this,
                        R.id.coordinator_layout,
                        getString(
                            R.string.conversations_perm_denied_contacts
                        )
                    )
                }
            }
    }

    /**
     * Archives the conversations represented by the specified messages.
     */
    private fun archiveConversations(
        messages: List<Message>,
        mode: ActionMode? = null
    ) {
        mode?.finish()

        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Default) {
            try {
                // Archive the conversations.
                for (message in messages) {
                    Database.getInstance(applicationContext)
                        .markConversationArchived(message.conversationId)
                }
                Database.getInstance(applicationContext).updateShortcuts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to archive conversations", e)
                // Some rows may have changed before the exception.
                lifecycleScope.launch(Dispatchers.Main) {
                    adapter.refresh()
                }
                showErrorMessage(
                    this@ConversationsActivity,
                    R.id.coordinator_layout,
                    getString(R.string.conversations_archive_fail)
                )
                return@launch
            }

            lifecycleScope.launch(Dispatchers.Main) {
                adapter.refresh()

                showSnackbar(
                    this@ConversationsActivity,
                    R.id.coordinator_layout,
                    resources.getQuantityString(
                        R.plurals.conversations_archived,
                        messages.size,
                        messages.size
                    ),
                    getString(R.string.undo),
                    {
                        CustomApplication.getApplication().applicationScope.launch(
                            Dispatchers.Default
                        ) {
                            try {
                                for (message in messages) {
                                    Database.getInstance(applicationContext)
                                        .markConversationUnarchived(
                                            message.conversationId
                                        )
                                }
                                Database.getInstance(applicationContext).updateShortcuts()
                                lifecycleScope.launch(Dispatchers.Main) {
                                    adapter.refresh()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to unarchive conversations", e)
                                // Some rows may have changed before the exception.
                                lifecycleScope.launch(Dispatchers.Main) {
                                    adapter.refresh()
                                }
                                showErrorMessage(
                                    this@ConversationsActivity,
                                    R.id.coordinator_layout,
                                    getString(R.string.conversations_unarchive_fail)
                                )
                            }
                        }
                    })
            }
        }
    }

    /**
     * Unarchives the conversations represented by the specified messages.
     */
    private fun unarchiveConversations(
        messages: List<Message>,
        mode: ActionMode? = null
    ) {
        mode?.finish()

        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Default) {
            try {
                // Archive the conversations.
                for (message in messages) {
                    Database.getInstance(applicationContext)
                        .markConversationUnarchived(message.conversationId)
                }
                Database.getInstance(applicationContext).updateShortcuts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unarchive conversations", e)
                // Some rows may have changed before the exception.
                lifecycleScope.launch(Dispatchers.Main) {
                    adapter.refresh()
                }
                showErrorMessage(
                    this@ConversationsActivity,
                    R.id.coordinator_layout,
                    getString(R.string.conversations_unarchive_fail)
                )
                return@launch
            }

            lifecycleScope.launch(Dispatchers.Main) {
                adapter.refresh()

                showSnackbar(
                    this@ConversationsActivity,
                    R.id.coordinator_layout,
                    resources.getQuantityString(
                        R.plurals.conversations_unarchived,
                        messages.size,
                        messages.size
                    ),
                    getString(R.string.undo),
                    {
                        CustomApplication.getApplication().applicationScope.launch(
                            Dispatchers.Default
                        ) {
                            try {
                                for (message in messages) {
                                    Database.getInstance(applicationContext)
                                        .markConversationArchived(
                                            message.conversationId
                                        )
                                }
                                Database.getInstance(applicationContext).updateShortcuts()
                                lifecycleScope.launch(Dispatchers.Main) {
                                    adapter.refresh()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to archive conversations", e)
                                // Some rows may have changed before the exception.
                                lifecycleScope.launch(Dispatchers.Main) {
                                    adapter.refresh()
                                }
                                showErrorMessage(
                                    this@ConversationsActivity,
                                    R.id.coordinator_layout,
                                    getString(R.string.conversations_archive_fail)
                                )
                            }
                        }
                    })
            }
        }
    }

    /**
     * Deletes the conversations represented by the specified messages.
     */
    private fun deleteConversations(
        messages: List<Message>,
        mode: ActionMode? = null
    ) {
        mode?.finish()

        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Default) {
            try {
                // Collect existing state in case we need to undo this.
                val conversations = messages.map {
                    Database.getInstance(applicationContext)
                        .getConversationMessagesUnsorted(it.conversationId)
                }
                val archived = mutableMapOf<ConversationId, Boolean>()
                for (message in messages) {
                    archived[message.conversationId] =
                        Database.getInstance(applicationContext)
                            .isConversationArchived(message.conversationId)
                }
                val drafts = mutableMapOf<ConversationId, Message?>()
                for (message in messages) {
                    drafts[message.conversationId] =
                        Database.getInstance(applicationContext)
                            .getConversationDraft(message.conversationId)
                }

                // Delete the conversations.
                for (message in messages) {
                    Database.getInstance(applicationContext)
                        .deleteConversation(message.conversationId)
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    adapter.refresh()

                    // Show a snackbar to allow the user to undo the action.
                    showSnackbar(
                        this@ConversationsActivity,
                        R.id.coordinator_layout,
                        resources.getQuantityString(
                            R.plurals.conversations_deleted,
                            messages.size,
                            messages.size
                        ),
                        getString(R.string.undo),
                        {
                            CustomApplication.getApplication().applicationScope.launch(
                                Dispatchers.Default
                            ) {
                                try {
                                    // Restore the conversations.
                                    for (conversation in conversations) {
                                        Database.getInstance(applicationContext)
                                            .insertMessagesDatabase(conversation)
                                    }
                                    for ((conversationId, isArchived) in archived) {
                                        if (isArchived) {
                                            Database.getInstance(applicationContext)
                                                .markConversationArchived(
                                                    conversationId
                                                )
                                        }
                                    }
                                    for ((conversationId, draft) in drafts) {
                                        if (draft != null) {
                                            Database.getInstance(applicationContext)
                                                .updateConversationDraft(
                                                    conversationId,
                                                    draft.text
                                                )
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to restore conversations", e)
                                    // Some rows may have changed before the exception.
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        adapter.refresh()
                                    }
                                    showErrorMessage(
                                        this@ConversationsActivity,
                                        R.id.coordinator_layout,
                                        getString(R.string.conversations_restore_fail)
                                    )
                                    return@launch
                                }

                                lifecycleScope.launch(Dispatchers.Main) {
                                    adapter.refresh()
                                }
                            }
                        })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete conversations", e)
                // Some rows may have changed before the exception.
                lifecycleScope.launch(Dispatchers.Main) {
                    adapter.refresh()
                }
                showErrorMessage(
                    this@ConversationsActivity,
                    R.id.coordinator_layout,
                    getString(R.string.conversations_delete_fail)
                )
            }
        }
    }

    /**
     * Toggles the item associated with the specified view. Activates and
     * deactivates the action mode depending on the checked item count.
     */
    private fun toggleItem(view: View) {
        // Inform the adapter that the item should be checked
        val index = recyclerView.getChildAdapterPosition(view)
        val item = adapter.displayedItem(view) ?: return
        item.toggle(index)

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
        // the kind of checked items
        updateActionModeButtons()
    }

    /**
     * Switches between "mark as read" and "mark as unread" buttons for the
     * action mode depending on which items in the RecyclerView are selected.
     */
    private fun updateActionModeButtons() {
        var read = 0
        var unread = 0
        adapter
            .filter { it.checked }
            .forEach {
                if (it.message.isUnread) {
                    unread++
                } else {
                    read++
                }
            }

        val actionMode = actionMode ?: return
        val selected = adapter.singleOrNull { it.checked }
        actionMode.menu.findItem(R.id.lookup_number).isVisible = !BuildConfig.IS_DEMO && selected != null &&
            OnlineNumberLookup.available(selected.message.contact, selected.contactName)
        val markReadButton = actionMode.menu.findItem(
            R.id.mark_read_button
        )
        val markUnreadButton = actionMode.menu.findItem(
            R.id.mark_unread_button
        )
        if (read > unread) {
            // Show mark unread button if there are more read buttons than
            // unread buttons
            markReadButton.isVisible = false
            markUnreadButton.isVisible = true
        } else {
            // Otherwise, show mark read button
            markReadButton.isVisible = true
            markUnreadButton.isVisible = false
        }
    }
}
