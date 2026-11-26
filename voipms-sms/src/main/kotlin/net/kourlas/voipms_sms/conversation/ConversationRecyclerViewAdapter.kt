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

import android.os.Build
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.QuickContactBadge
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import net.kourlas.voipms_sms.delivery.CallFeedItem
import net.kourlas.voipms_sms.delivery.CallPlayback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import net.kourlas.voipms_sms.rich.ui.ChatRoute
import net.kourlas.voipms_sms.rich.ui.ChatHolder
import net.kourlas.voipms_sms.rich.ui.ThreadChatEvent
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.demo.getConversationDemoMessages
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.sms.VerificationCode
import net.kourlas.voipms_sms.ui.FastScroller
import net.kourlas.voipms_sms.utils.applyCircularMask
import net.kourlas.voipms_sms.utils.getConversationViewDate
import net.kourlas.voipms_sms.utils.getConversationViewTopDate
import net.kourlas.voipms_sms.utils.getScrollBarDate
import net.kourlas.voipms_sms.utils.showSnackbar
import java.util.Locale

/**
 * Recycler view adapter used by [ConversationActivity].
 *
 * @param activity The source [ConversationActivity].
 * @param recyclerView The recycler view used by the activity.
 * @param layoutManager The layout manager used by the recycler view.
 * @param conversationId The conversation ID of the conversation displayed by
 * the activity, consisting of the DID and contact.
 */
class ConversationRecyclerViewAdapter(
    private val activity: ConversationActivity,
    private val recyclerView: RecyclerView,
    private val layoutManager: LinearLayoutManager,
    private val conversationId: ConversationId
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>(),
    Iterable<ConversationRecyclerViewAdapter.MessageItem>,
    FastScroller.SectionTitleProvider {

    init {
        // Optional call/chat feeds may arrive before the saved SMS viewport can be restored.
        stateRestorationPolicy = StateRestorationPolicy.PREVENT
    }

    private val thumbnails = object : android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private var retrySnackbar: Snackbar? = null

    // List of items shown by the adapter; the index of each item
    // corresponds to the location of each item in the adapter.
    private val _messageItems = mutableListOf<MessageItem>()
    val messageItems: List<MessageItem>
        get() = _messageItems

    private sealed interface Row {
        val key: String
        val timestamp: Long
    }
    private data class TextRow(val item: MessageItem) : Row {
        override val key = if (BuildConfig.IS_DEMO) "demo/" + item.message.databaseId + "/" + item.message.text
            else "sms/" + item.message.databaseId
        override val timestamp = item.message.date.time
    }
    private data class CallRow(val event: CallFeedItem) : Row {
        override val key = "call/" + event.id
        override val timestamp = event.receivedAt
    }
    private val junkProfile by lazy {
        net.kourlas.voipms_sms.privacy.JunkProfiles(activity).line(conversationId.did)
    }
    private data class ChatRow(val event: ThreadChatEvent) : Row {
        override val key = "chat/" + event.route.name + "/" + event.key
        override val timestamp = event.time
    }
    private val chat = mutableMapOf<ChatRoute, List<ThreadChatEvent>>()

    internal fun updateChat(route: ChatRoute, events: List<ThreadChatEvent>) {
        if (events == chat[route] || events.isEmpty() && chat[route].isNullOrEmpty()) return
        chat[route] = events
        renderTimeline()
    }
    private var timeline: List<Row> = emptyList()
    private var calls = emptyList<CallFeedItem>()
    private var visibleEventLimit = ADDITIONAL_ITEMS_INCREMENT.toInt()

    internal fun updateCalls(events: List<CallFeedItem>) {
        val updated = matchingCalls(events, conversationId)
        if (updated == calls) return
        calls = updated
        renderTimeline()
    }

    private fun renderTimeline(filterChanged: Boolean = false) {
        val previous = timeline
        val atBottom = previous.isNotEmpty() && !recyclerView.canScrollVertically(1)
        val bounds = messageItems.map { it.message.date.time }
        val oldest = if (showAnchor || currLimit < maxLimit) bounds.minOrNull() ?: Long.MIN_VALUE else Long.MIN_VALUE
        val newest = if (showAnchor) bounds.maxOrNull() ?: Long.MAX_VALUE else Long.MAX_VALUE
        val visibleCalls = if (currConstraint.isEmpty()) calls.asSequence()
            .filter { it.receivedAt in oldest..newest }
            .take(visibleEventLimit).map { CallRow(it) }.toList() else emptyList()
        val visibleChat = chat.values.asSequence().flatten()
            .filter { it.time in oldest..newest && it.body.contains(currConstraint, ignoreCase = true) }
            .sortedByDescending { it.time }.take(visibleEventLimit).map(::ChatRow).toList()
        val current = (messageItems.map { TextRow(it) } + visibleCalls + visibleChat)
            .sortedWith(compareBy<Row> { it.timestamp }.thenBy { if (it is TextRow) 0 else 1 }
                .thenBy { (it as? TextRow)?.item?.message?.databaseId ?: 0L }.thenBy { it.key })
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = current.size
            override fun areItemsTheSame(old: Int, new: Int) = previous[old].key == current[new].key
            override fun areContentsTheSame(old: Int, new: Int): Boolean {
                val a = previous[old]
                val b = current[new]
                if (a is TextRow && b is TextRow) return !filterChanged && a.item.message == b.item.message &&
                    previous.getOrNull(old - 1)?.key == current.getOrNull(new - 1)?.key
                return a == b
            }
        }, false)
        timeline = current
        diff.dispatchUpdatesTo(this)
        val empty = activity.findViewById<TextView>(R.id.empty_text)
        if (current.isNotEmpty()) empty.text = "" else empty.setText(
            if (currConstraint.isEmpty()) R.string.conversation_no_messages else R.string.conversation_no_results)
        if (atBottom && current.isNotEmpty() && !showAnchor) layoutManager.scrollToPosition(current.lastIndex)
    }

    // Current and previous filter constraint.
    private val anchorId = activity.intent.getLongExtra(net.kourlas.voipms_sms.tools.MessageSearch.ANCHOR, -1)
    private var showAnchor = anchorId > 0
    private var positionedAnchor = false
    private var currConstraint: String = ""

    // The total number of items that can be retrieved and which have been
    // retrieved.
    private var maxLimit = 0L
    private var currLimit = ADDITIONAL_ITEMS_INCREMENT

    // Whether the adapter is currently loading additional items.
    var loadingMoreItems = false

    private var contactPhoto: Bitmap? = null

    fun updateContactPhoto(photo: Bitmap) {
        contactPhoto = photo
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            (recyclerView.getChildViewHolder(child) as? MessageViewHolder)?.contactBadge?.setImageBitmap(photo)
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        if (viewType == R.layout.thread_chat_entry) return ChatHolder(
            LayoutInflater.from(parent.context).inflate(viewType, parent, false))
        if (viewType == R.layout.thread_call_entry) return CallHolder(
            LayoutInflater.from(parent.context).inflate(viewType, parent, false))
        // Inflate the appropriate view, given the view type
        val itemView = when (viewType) {
            R.layout.conversation_item_incoming -> {
                LayoutInflater.from(parent.context)
                    .inflate(
                        R.layout.conversation_item_incoming,
                        parent, false
                    )
            }

            R.layout.conversation_item_outgoing -> {
                LayoutInflater.from(parent.context)
                    .inflate(
                        R.layout.conversation_item_outgoing,
                        parent, false
                    )
            }

            else -> throw Exception("Unknown view type $viewType")
        }
        return MessageViewHolder(itemView, viewType, activity, thumbnails)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {
        val row = timeline[position]
        if (row is ChatRow && holder is ChatHolder) {
            holder.bind(row.event, activity::openRichMedia)
            return
        }
        if (row is CallRow && holder is CallHolder) {
            val event = row.event
            holder.name.setText(if (event.voicemail) R.string.thread_voicemail_event else R.string.thread_missed_event)
            holder.time.text = if (event.dateOnly) event.providerDate
                else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.receivedAt))
            holder.controls.removeAllViews()
            if (event.voicemail) holder.controls.addView(CallPlayback.controls(activity, event))
            return
        }
        if (holder !is MessageViewHolder || row !is TextRow) return
        // Set up view to match message at position
        updateViewHolderViewHeight(holder, position)
        updateViewHolderContactBadge(holder, position)
        updateViewHolderMessageText(holder, position)
        holder.warning?.let { warning ->
            val assessment = net.kourlas.voipms_sms.sms.InboxRules.assess(row.item.message.text, junkProfile)
            warning.text = assessment.description
            warning.visibility = if (assessment.description != null) View.VISIBLE else View.GONE
        }
        val code = VerificationCode.extract(row.item.message.text)
        holder.copyCode.visibility = if (code == null) View.GONE else View.VISIBLE
        holder.copyCode.setOnClickListener(if (code == null) null else View.OnClickListener {
            if (net.kourlas.voipms_sms.privacy.CodePrivacy.copyCode(activity, code) &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(activity, R.string.code_copied, Toast.LENGTH_SHORT).show()
            }
        })
        updateViewHolderDateText(holder, position)
        updateViewHolderColours(holder, position)
    }

    /**
     * Sets the height of the view represented by the view holder to the
     * appropriate height, depending on whether the message is the first
     * message in a group.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderViewHeight(
        holder: MessageViewHolder,
        position: Int
    ) {
        val marginParams = holder.itemView.layoutParams
            as ViewGroup.MarginLayoutParams
        marginParams.topMargin =
            if (isFirstMessageInGroup(
                    position,
                    combineIncomingOutgoing = false
                )
            ) {
                activity.resources.getDimension(
                    R.dimen.conversation_item_margin_top_primary
                ).toInt()
            } else {
                activity.resources.getDimension(
                    R.dimen.conversation_item_margin_top_secondary
                ).toInt()
            }
    }

    /**
     * Displays or hides the contact badge on the view holder. If the view
     * holder is displayed, sets the content of the view holder to a photo
     * or a material design letter.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderContactBadge(
        holder: MessageViewHolder,
        position: Int
    ) {
        val messageItem = get(position) ?: return
        val message = messageItem.message

        val contactBadge = holder.contactBadge
        if (contactBadge != null) {
            // Show contact badge if first message in group
            if (isFirstMessageInGroup(
                    position,
                    combineIncomingOutgoing = false
                )
            ) {
                holder.contactBadge.visibility = View.VISIBLE
                contactBadge.assignContactFromPhone(message.contact, true)
                if (!BuildConfig.IS_DEMO) contactBadge.setOnClickListener { activity.editLocalContact() }
                val photo = contactPhoto
                if (photo != null) contactBadge.setImageBitmap(photo)
                else contactBadge.setImageResource(R.drawable.ic_account_box_settings_24dp)
            } else {
                holder.contactBadge.visibility = View.INVISIBLE
            }
        }
    }

    /**
     * Displays the text of the message on the view holder. Selects and
     * highlights part of the text if a filter is configured.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderMessageText(
        holder: MessageViewHolder,
        position: Int
    ) {
        val messageItem = get(position) ?: return
        val message = messageItem.message

        val messageText = holder.messageText
        val messageTextBuilder = SpannableStringBuilder()

        if (message.text == "" && message.medias.isEmpty()) {
            messageTextBuilder.append(message.fullDisplayText)
            messageTextBuilder.setSpan(
                StyleSpan(Typeface.ITALIC), 0,
                messageTextBuilder.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        } else {
            // Highlight text that matches filter
            messageTextBuilder.append(message.text)
            if (currConstraint != "") {
                val index = message.text.indexOf(currConstraint, ignoreCase = true)
                if (index != -1) {
                    messageTextBuilder.setSpan(
                        BackgroundColorSpan(
                            ContextCompat.getColor(
                                activity, R.color.highlight
                            )
                        ),
                        index,
                        index + currConstraint.length,
                        SpannableString.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                    messageTextBuilder.setSpan(
                        ForegroundColorSpan(
                            ContextCompat.getColor(
                                activity, android.R.color.black
                            )
                        ),
                        index,
                        index + currConstraint.length,
                        SpannableString.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                }
            }


        }

        holder.attachments.bind(message.databaseId, message.medias)
        messageText.visibility = if (messageTextBuilder.isEmpty() && message.medias.isNotEmpty()) View.GONE else View.VISIBLE
        messageText.text = messageTextBuilder

        Linkify.addLinks(
            messageText,
            Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS or Linkify.WEB_URLS
        )
        messageText.setOnClickListener { textView ->
            activity.onItemClick(textView)
        }
        messageText.setOnLongClickListener { textView ->
            activity.onItemLongClick(textView)
            true
        }
        messageText.movementMethod =
            BetterLinkMovementMethod.newInstance().apply {
                setOnLinkLongClickListener { _, _ ->
                    true
                }
            }
    }

    /**
     * Displays the date and time for the message. Optionally displays a
     * the status of the message in certain cases instead, such as when the
     * message is being sent or has failed to send. Optionally hides it
     * altogether, depending on the message's placement in a group.
     *
     * Optionally displays the date and time of the conversation group,
     * depending on the message's placement in the group.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderDateText(
        holder: MessageViewHolder,
        position: Int
    ) {
        val messageItem = get(position) ?: return
        val message = messageItem.message

        // Set per-message date and time
        val dateText = holder.dateText
        if (message.outboxState != null && !message.isDelivered) {
            val state = message.outboxState
            val dueAt = message.outboxDueAt ?: 0
            dateText.text = when (state) {
                net.kourlas.voipms_sms.database.entities.OutboxPart.UNKNOWN -> activity.getString(R.string.outbox_unknown)
                net.kourlas.voipms_sms.database.entities.OutboxPart.REJECTED -> activity.getString(R.string.outbox_rejected)
                net.kourlas.voipms_sms.database.entities.OutboxPart.ATTEMPTING -> activity.getString(R.string.outbox_submitting)
                else -> if (dueAt > System.currentTimeMillis() + net.kourlas.voipms_sms.sms.workers.Outbox.UNDO_MS)
                    activity.getString(R.string.outbox_scheduled) + " · " + java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(dueAt))
                else activity.getString(R.string.outbox_queued)
            }
            dateText.visibility = View.VISIBLE
        } else if (!message.isDelivered) {
            if (!message.isDeliveryInProgress) {
                // Show tried but failed to send text
                val dateTextBuilder = SpannableStringBuilder()
                dateTextBuilder.append(
                    activity.getString(
                        R.string.conversation_message_not_sent
                    )
                )
                dateTextBuilder.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            activity,
                            R.color.send_error
                        )
                    ),
                    0,
                    dateTextBuilder.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                dateTextBuilder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    dateTextBuilder.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                dateText.text = dateTextBuilder
            } else {
                // Show sending text
                dateText.text = activity.getString(
                    R.string.conversation_message_sending
                )
            }
            dateText.visibility = View.VISIBLE
        } else {
            val date = getConversationViewDate(activity, message.date)
            dateText.text = if (!message.isIncoming && message.voipId != null)
                activity.getString(R.string.outbox_provider_accept, date) else date
        }

        // Set conversation group date and time
        val topDateText = holder.topDateText
        if (isFirstMessageInGroup(position, combineIncomingOutgoing = true)) {
            topDateText.text = getConversationViewTopDate(message.date)
            topDateText.visibility = View.VISIBLE
        } else {
            topDateText.visibility = View.GONE
        }
    }

    /**
     * Selects the checked or normal fill on the incoming or outgoing bubble.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    fun updateViewHolderColours(holder: MessageViewHolder, position: Int) {
        val item = get(position) ?: return
        val isSearchMatch = showAnchor && item.message.databaseId == anchorId
        holder.smsContainer.isActivated = item.checked || isSearchMatch
        androidx.core.view.ViewCompat.setStateDescription(holder.smsContainer,
            if (item.checked) activity.getString(R.string.thread_state_selected) else null)
    }

    override fun getItemViewType(position: Int): Int = when (val row = timeline[position]) {
        is ChatRow -> R.layout.thread_chat_entry
        is CallRow -> R.layout.thread_call_entry
        is TextRow -> if (row.item.message.isIncoming) R.layout.conversation_item_incoming else R.layout.conversation_item_outgoing
    }
    override fun getItemCount(): Int = timeline.size

    /**
     * Gets the number of items in the adapter that are checked.
     */
    fun getCheckedItemCount(): Int = messageItems.filter { it.checked }.size

    operator fun get(i: Int): MessageItem? = (timeline.getOrNull(i) as? TextRow)?.item

    override fun iterator(): Iterator<MessageItem> = messageItems.iterator()

    override fun getSectionTitle(position: Int): String =
        timeline.getOrNull(position)?.let { getScrollBarDate(java.util.Date(it.timestamp)) }.orEmpty()

    private var requestedConstraint = ""
    private var loadJob: Job? = null
    private var revision = 0L

    private data class Page(val messages: List<Message>, val total: Long, val anchored: Boolean = false)

    private suspend fun loadPage(query: String, limit: Long, anchor: Boolean): Page {
        if (BuildConfig.IS_DEMO) {
            val demo = getConversationDemoMessages(activity.bubble)
            return Page(demo, demo.size.toLong())
        }
        val db = Database.getInstance(activity.applicationContext)
        if (anchor && query.isEmpty()) {
            val messages = db.messagesAtAnchor(conversationId, anchorId)
            if (messages.isNotEmpty()) return Page(messages, messages.size.toLong(), anchored = true)
        }
        val total = db.getConversationMessagesFilteredCount(conversationId, query)
        return Page(db.getConversationMessagesFiltered(conversationId, query, limit).asReversed(), total)
    }

    fun refresh() = load(paging = false)

    fun refresh(constraint: String) {
        val changed = requestedConstraint != constraint.trim()
        requestedConstraint = constraint.trim()
        showAnchor = false
        if (changed) {
            currLimit = ADDITIONAL_ITEMS_INCREMENT
            visibleEventLimit = ADDITIONAL_ITEMS_INCREMENT.toInt()
        }
        refresh()
    }

    private fun load(paging: Boolean) {
        val request = ++revision
        loadJob?.cancel()
        retrySnackbar?.dismiss()
        retrySnackbar = null
        loadingMoreItems = true
        val query = requestedConstraint
        val limit = currLimit
        val anchor = showAnchor
        val trace = DebugJournal.begin(activity, DiagnosticAction.THREAD_LOAD)
        loadJob = activity.lifecycleScope.launch {
            try {
                trace.step(if (paging) DiagnosticStep.PAGING else DiagnosticStep.LOADING)
                val page = withContext(Dispatchers.IO) {
                    trace.step(DiagnosticStep.QUERYING)
                    loadPage(query.lowercase(Locale.getDefault()), limit, anchor)
                }
                if (request != revision || recyclerView.adapter !== this@ConversationRecyclerViewAdapter || activity.isFinishing) {
                    trace.step(DiagnosticStep.SUPERSEDED)
                    return@launch
                }
                trace.step(DiagnosticStep.RENDERING, page.messages.size.toLong())
                val checked = messageItems.filter { it.checked }.map { it.message.databaseId }.toSet()
                val changed = query != currConstraint
                currConstraint = query
                maxLimit = page.total
                currLimit = limit.coerceAtMost(maxLimit).coerceAtLeast(ADDITIONAL_ITEMS_INCREMENT)
                showAnchor = page.anchored
                _messageItems.clear()
                page.messages.mapTo(_messageItems) { MessageItem(it, it.databaseId in checked) }
                renderTimeline(changed)
                stateRestorationPolicy = StateRestorationPolicy.ALLOW
                if (showAnchor && !positionedAnchor) {
                    val at = timeline.indexOfFirst { it is TextRow && it.item.message.databaseId == anchorId }
                    if (at >= 0) {
                        layoutManager.scrollToPositionWithOffset(at, recyclerView.height / 3)
                        positionedAnchor = true
                    }
                }
                trace.step(if (timeline.isEmpty()) DiagnosticStep.EMPTY else DiagnosticStep.COMPLETED, timeline.size.toLong())
            } catch (e: CancellationException) {
                trace.step(if (request == revision) DiagnosticStep.CANCELLED else DiagnosticStep.SUPERSEDED)
                throw e
            } catch (e: Exception) {
                trace.step(DiagnosticStep.FAILED, error = e)
                if (request == revision && recyclerView.adapter === this@ConversationRecyclerViewAdapter && !activity.isFinishing) {
                    if (timeline.isEmpty()) activity.findViewById<TextView>(R.id.empty_text).setText(R.string.thread_loading_failed)
                    retrySnackbar = showSnackbar(activity, R.id.coordinator_layout, activity.getString(R.string.thread_loading_failed),
                        activity.getString(R.string.inbox_load_retry), View.OnClickListener {
                            if (recyclerView.adapter === this@ConversationRecyclerViewAdapter) {
                                trace.step(DiagnosticStep.RETRY)
                                refresh()
                            }
                        }, if (timeline.isEmpty()) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG)
                }
            } finally {
                if (request == revision) loadingMoreItems = false
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MessageViewHolder) holder.attachments.clear()
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        loadJob?.cancel()
        thumbnails.evictAll()
        retrySnackbar?.dismiss()
        retrySnackbar = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun loadMoreItems() {
        val remainingEvents = visibleEventLimit < calls.size || visibleEventLimit < chat.values.sumOf { it.size }
        if (!loadingMoreItems && (currLimit < maxLimit || remainingEvents)) {
            visibleEventLimit += ADDITIONAL_ITEMS_INCREMENT.toInt()
            // A call-only thread may acquire its first SMS while paging.
            currLimit = minOf(maxLimit, currLimit + ADDITIONAL_ITEMS_INCREMENT).coerceAtLeast(ADDITIONAL_ITEMS_INCREMENT)
            load(paging = true)
        }
    }

    /**
     * Returns true if the message at the specified position is the first
     * message in a group, which is a collection of messages that are
     * spaced together based on whether the message is incoming or outgoing,
     * as well as the time between the messages.
     *
     * @param position The position of the specified message.
     * @param combineIncomingOutgoing If true, both incoming and outgoing
     * messages are considered to be part of the same group.
     */
    private fun isFirstMessageInGroup(
        position: Int, combineIncomingOutgoing: Boolean
    ): Boolean {
        val message = get(position)?.message ?: return true
        val previousMessage = get(position - 1)?.message
        return previousMessage == null
            || (!combineIncomingOutgoing
            && message.isIncoming != previousMessage.isIncoming)
            || message.date.time - previousMessage.date.time > 60000
    }

    /**
     * A container for a message item in the adapter that also tracks whether
     * the item is checked in addition to the message itself.
     *
     * @param message The message represented by the message item.
     */
    inner class MessageItem(val message: Message, initialChecked: Boolean = false) {
        private var _checked = initialChecked
        val checked: Boolean
            get() = _checked

        /**
         * Sets whether or not the message item is checked.
         *
         * @param checked Whether the message item is checked.
         * @param position The position of the message item in the adapter.
         */
        fun setChecked(checked: Boolean, position: Int) {
            val previous = _checked
            _checked = checked

            val holder = recyclerView.findViewHolderForAdapterPosition(position)
                as? MessageViewHolder

            if ((previous && !_checked) || (!previous && _checked)) {
                if (holder != null) {
                    updateViewHolderColours(holder, position)
                } else {
                    notifyItemChanged(position)
                }
            }
        }

        /**
         * Toggles the checked state of this message item.
         *
         * @param position The position of the message item in the adapter.
         */
        fun toggle(position: Int) = setChecked(!_checked, position)
    }

    /**
     * A container for the views associated with a message item.
     *
     * @param itemView The primary view of the message item.
     */
    private class CallHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.call_entry_name)
        val time: TextView = view.findViewById(R.id.call_entry_time)
        val controls: android.widget.FrameLayout = view.findViewById(R.id.call_entry_controls)
    }

    class MessageViewHolder internal constructor(
        itemView: View,
        viewType: Int,
        activity: ConversationActivity,
        thumbnails: android.util.LruCache<String, Bitmap>
    ) : RecyclerView.ViewHolder(
        itemView
    ) {
        // All configurable views on a message item
        internal val contactBadge: QuickContactBadge? =
            if (viewType == R.layout.conversation_item_incoming) {
                itemView.findViewById(R.id.photo)
            } else {
                null
            }
        internal val attachments = net.kourlas.voipms_sms.media.InlineMmsAttachments(
            activity, itemView.findViewById(R.id.message_attachments), thumbnails)
        internal val smsContainer: View =
            itemView.findViewById(R.id.sms_container)
        internal val warning: TextView? = itemView.findViewById(R.id.message_risk_warning)
        internal val copyCode: View = itemView.findViewById(R.id.copy_sms_code)
        internal val messageText: TextView =
            itemView.findViewById(R.id.message)
        internal val dateText: TextView =
            itemView.findViewById(R.id.date)
        internal val topDateText: TextView =
            itemView.findViewById(R.id.top_date)

        init {
            // Allow the message view itself to be selectable and add rounded
            // corners to it
            smsContainer.isClickable = true
            smsContainer.isLongClickable = true
            androidx.core.view.ViewCompat.setScreenReaderFocusable(smsContainer, true)

            // Apply circular mask to and remove overlay from contact badge
            // to match Android Messages aesthetic
            if (contactBadge != null) {
                applyCircularMask(contactBadge)
                contactBadge.setOverlay(null)
            }
        }
    }

    companion object {
        internal fun matchingCalls(events: List<CallFeedItem>, thread: ConversationId): List<CallFeedItem> =
            events.filter { it.did == thread.did && it.peer == thread.contact }.distinctBy { it.id }
                .sortedWith(compareByDescending<CallFeedItem> { it.receivedAt }.thenBy { it.id })

        // The number of additional items to retrieve when loadMoreItems is
        // called.
        private const val ADDITIONAL_ITEMS_INCREMENT = 100L

        // When the message with this index is shown, we should start to load
        // more items.
        const val START_LOAD_INDEX = ADDITIONAL_ITEMS_INCREMENT / 4
    }
}
