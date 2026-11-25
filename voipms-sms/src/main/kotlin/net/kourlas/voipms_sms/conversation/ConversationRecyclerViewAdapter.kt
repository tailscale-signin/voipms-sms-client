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

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.QuickContactBadge
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import net.kourlas.voipms_sms.delivery.CallFeedItem
import net.kourlas.voipms_sms.delivery.CallPlayback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
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
import kotlin.math.max

private const val TAG = "ConversationAdapter"

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
    Filterable,
    Iterable<ConversationRecyclerViewAdapter.MessageItem>,
    FastScroller.SectionTitleProvider {

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
        val atBottom = previous.isEmpty() || !recyclerView.canScrollVertically(1)
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
    private var prevConstraint: String = ""

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
        return MessageViewHolder(itemView, viewType)
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
            val clip = ClipData.newPlainText(activity.getString(R.string.clipboard_code), code)
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
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
                val index =
                    message.text.lowercase(Locale.getDefault()).indexOf(
                        currConstraint.lowercase(Locale.getDefault())
                    )
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

        val gallery = holder.itemView.findViewById<android.widget.Button>(R.id.message_gallery)
        gallery.visibility = if (message.medias.isEmpty()) View.GONE else View.VISIBLE
        if (message.medias.isNotEmpty()) {
            gallery.text = activity.getString(R.string.mms_gallery_count, message.medias.size)
            gallery.setOnClickListener {
                net.kourlas.voipms_sms.media.MmsGallery.show(activity, message.databaseId, message.medias, 0)
            }
        } else gallery.setOnClickListener(null)
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
        getScrollBarDate(java.util.Date(timeline[position].timestamp))

    override fun getFilter(): Filter = messageFilter

    private val messageFilter = object : Filter() {
        /**
         * Perform filtering using the specified filter constraint.
         */
        fun doFiltering(constraint: CharSequence): ConversationFilter {
            // Get filtered messages
            val resultsObject = ConversationFilter()
            if (!BuildConfig.IS_DEMO) {
                runBlocking {
                    val filterString = constraint.toString()
                        .trim()
                        .lowercase(Locale.getDefault())
                    if (showAnchor && filterString.isEmpty()) {
                        resultsObject.messages.addAll(Database.getInstance(activity).messagesAtAnchor(conversationId, anchorId))
                        if (resultsObject.messages.isNotEmpty()) return@runBlocking
                        showAnchor = false
                    }
                    maxLimit = Database.getInstance(activity)
                        .getConversationMessagesFilteredCount(
                            conversationId, filterString
                        )
                    if (currLimit > maxLimit) {
                        currLimit = max(maxLimit, ADDITIONAL_ITEMS_INCREMENT)
                    }
                    resultsObject.messages.addAll(
                        Database.getInstance(activity)
                            .getConversationMessagesFiltered(
                                conversationId,
                                filterString,
                                currLimit
                            ).asReversed()
                    )
                }
            } else {
                resultsObject.messages.addAll(
                    getConversationDemoMessages(
                        activity.bubble
                    )
                )
            }
            return resultsObject
        }

        override fun performFiltering(
            constraint: CharSequence
        ): FilterResults = try {
            val resultsObject = doFiltering(constraint)

            // Return filtered messages
            val results = FilterResults()
            results.count = resultsObject.messages.size
            results.values = resultsObject
            results
        } catch (e: Exception) {
            Log.e(TAG, "Failed to filter messages", e)
            FilterResults()
        }

        override fun publishResults(
            constraint: CharSequence,
            results: FilterResults?
        ) {
            if (constraint.toString() != requestedConstraint) return
            if (results?.values == null) {
                showSnackbar(
                    activity, R.id.coordinator_layout,
                    activity.getString(
                        R.string.new_conversation_error_refresh
                    )
                )
                return
            }

            // Process new filter string
            prevConstraint = currConstraint
            currConstraint = constraint.toString().trim()

            // The Android results interface uses type Any, so we have
            // no choice but to use an unchecked cast
            val resultsObject = results.values as ConversationFilter
            val newMessages = resultsObject.messages

            val checked = messageItems.filter { it.checked }.map { it.message.databaseId }.toSet()
            _messageItems.clear()
            newMessages.mapTo(_messageItems) { MessageItem(it, it.databaseId in checked) }
            renderTimeline(prevConstraint != currConstraint)

            if (showAnchor && !positionedAnchor) {
                val at = timeline.indexOfFirst { it is TextRow && it.item.message.databaseId == anchorId }
                if (at >= 0) {
                    layoutManager.scrollToPositionWithOffset(at, recyclerView.height / 3)
                    positionedAnchor = true
                }
            }
            loadingMoreItems = false
        }
    }

    /**
     * Refreshes the adapter using the currently defined filter constraint.
     */
    private var requestedConstraint = ""

    fun refresh() = filter.filter(requestedConstraint)

    /**
     * Refreshes the adapter using the specified filter constraint.
     */
    fun refresh(constraint: String) {
        showAnchor = false
        requestedConstraint = constraint
        refresh()
    }

    /**
     * Loads additional items from the database.
     */
    fun loadMoreItems() {
        val remainingEvents = visibleEventLimit < calls.size || visibleEventLimit < chat.values.sumOf { it.size }
        if (!loadingMoreItems && (currLimit < maxLimit || remainingEvents)) {
            visibleEventLimit += ADDITIONAL_ITEMS_INCREMENT.toInt()
            loadingMoreItems = true
            currLimit = minOf(maxLimit, currLimit + ADDITIONAL_ITEMS_INCREMENT)
            refresh()
        }
    }

    /**
     * Helper class used to store messages.
     */
    class ConversationFilter {
        internal val messages = mutableListOf<Message>()
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
        viewType: Int
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
