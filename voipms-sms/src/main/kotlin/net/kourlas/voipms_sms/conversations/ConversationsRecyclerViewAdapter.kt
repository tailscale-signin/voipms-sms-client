/*
 * VoIP.ms SMS
 * Copyright (C) 2015-2021 Michael Kourlas
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

import net.kourlas.voipms_sms.contacts.LocalContacts
import net.kourlas.voipms_sms.contacts.ContactNameDialog
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.lookup.NumberOrigin
import net.kourlas.voipms_sms.sms.InboxRules
import net.kourlas.voipms_sms.lookup.OnlineNumberLookup
import net.kourlas.voipms_sms.ui.ChatFont
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.demo.getConversationsDemoMessages
import net.kourlas.voipms_sms.preferences.getActiveDid
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.utils.*
import java.util.*

private const val TAG = "ConversationsAdapter"

/**
 * Recycler view adapter used by [ConversationsActivity] and
 * [ConversationsArchivedActivity].
 *
 * @param activity The source [ConversationsActivity] or
 * [ConversationsArchivedActivity].
 * @param recyclerView The recycler view used by the activity.
 * @param layoutManager The layout manager used by the recycler view.
 */
class ConversationsRecyclerViewAdapter<T>(
    private val activity: T,
    private val recyclerView: RecyclerView,
    private val layoutManager: LinearLayoutManager
) :
    RecyclerView.Adapter<ConversationsRecyclerViewAdapter<T>.ConversationViewHolder>(),
    Iterable<ConversationsRecyclerViewAdapter<T>.ConversationItem>
    where T : AppCompatActivity, T : View.OnClickListener, T : View.OnLongClickListener {
    // List of items shown by the adapter; the index of each item
    // corresponds to the location of each item in the adapter
    private val _conversationItems = mutableListOf<ConversationItem>()
    val conversationItems: List<ConversationItem>
        get() = _conversationItems

    private var currConstraint = ""
    private var requestedConstraint = ""
    private var privateView = false
    private var showOther = false

    fun switchPrivateView(open: Boolean) {
        if (open == privateView) return
        refreshJob?.cancel()
        privateView = open
        // No private row can remain visible during the next database read or an item removal animation.
        recyclerView.itemAnimator?.let { animator ->
            recyclerView.itemAnimator = null
            recyclerView.doOnNextLayout { recyclerView.itemAnimator = animator }
        }
        loadedRows = emptyList()
        val previousCount = itemCount
        _conversationItems.clear()
        if (previousCount > 0) notifyItemRangeRemoved(0, previousCount)
        refresh("")
    }
    private var refreshJob: Job? = null
    private val loader = Dispatchers.IO.limitedParallelism(1)
    private var loadedRows: List<InboxRow> = emptyList()

    // Caches
    private val contactNameCache = mutableMapOf<String, String>()
    // Keep photos only for recently visited rows; a large inbox need not allocate an avatar per thread.
    private val avatarLoader = Dispatchers.IO.limitedParallelism(2)
    private val avatarSize = activity.resources.getDimensionPixelSize(R.dimen.contact_badge)
    private val avatars = object : android.util.LruCache<Pair<String, String?>, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, String?>, value: Bitmap) = value.allocationByteCount
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ConversationViewHolder {
        // There is only one item view type
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.conversations_item, parent, false)
        itemView.clipToOutline = true
        androidx.core.view.ViewCompat.setScreenReaderFocusable(itemView, true)
        return ConversationViewHolder(itemView)
    }

    override fun onBindViewHolder(
        holder: ConversationViewHolder,
        position: Int
    ) {
        // Set up view to match message at position
        updateViewHolderContactBadge(holder, position)
        updateViewHolderContactText(holder, position)
        updateViewHolderMessageText(holder, position)
        updateViewHolderDateText(holder, position)
        val item = conversationItems[position]
        val restore = holder.restoreThread
        restore.visibility = if (showOther && !privateView && !BuildConfig.IS_DEMO) View.VISIBLE else View.GONE
        restore.setOnClickListener(if (restore.visibility != View.VISIBLE) null else View.OnClickListener {
            if (conversationItems.any { it.checked }) {
                holder.itemView.performClick()
                return@OnClickListener
            }
            activity.lifecycleScope.launch {
                try {
                    val database = Database.getInstance(activity)
                    database.moveToInboxFolder(listOf(item.message.conversationId), false)
                    refresh()
                    database.updateShortcuts()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    android.widget.Toast.makeText(activity, R.string.conversations_error_refresh,
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /**
     * Changes the contact badge to a check mark, a photo, or a material design
     * letter.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    fun updateViewHolderContactBadge(
        holder: ConversationViewHolder,
        position: Int
    ) {
        val conversationItem = conversationItems[position]
        val message = conversationItem.message

        holder.unreadDot.visibility = if (message.isUnread) View.VISIBLE else View.GONE
        holder.itemView.isActivated = conversationItem.checked
        androidx.core.view.ViewCompat.setStateDescription(holder.itemView, when {
            conversationItem.checked -> activity.getString(R.string.thread_state_selected)
            message.isUnread -> activity.getString(R.string.thread_state_unread)
            else -> null
        })
        holder.viewSwitcher.displayedChild =
            if (conversationItem.checked) 1 else 0
        if (!conversationItem.checked) {
            holder.contactBadge.assignContactFromPhone(message.contact, true)
            val name = conversationItem.contactName
            val key = message.contact to name
            if (holder.avatarKey != key) {
                holder.avatarJob?.cancel()
                holder.avatarKey = key
                val cached = avatars.get(key)
                if (cached != null) {
                    holder.contactBadge.setImageBitmap(cached)
                } else {
                    holder.contactBadge.setImageResource(R.drawable.ic_account_box_settings_24dp)
                    holder.avatarJob = activity.lifecycleScope.launch {
                        val bitmap = withContext(avatarLoader) {
                            getContactPhotoBitmap(activity.applicationContext, name, message.contact, avatarSize)
                        }
                        avatars.put(key, bitmap)
                        if (holder.avatarKey == key) holder.contactBadge.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    /**
     * Displays the contact name or phone number associated with the
     * conversation on the view holder. Selects and highlights part of the
     * text if a filter is configured. Marks text as bold if unread.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderContactText(
        holder: ConversationViewHolder,
        position: Int
    ) {
        val conversationItem = conversationItems[position]
        val message = conversationItem.message

        val contactTextBuilder = SpannableStringBuilder()
        val label = OnlineNumberLookup.label(activity, message.contact, conversationItem.contactName)
        if (label != null) {
            contactTextBuilder.append(OnlineNumberLookup.blueNumber(activity, message.contact))
        } else {
            contactTextBuilder.append(conversationItem.contactName ?: getFormattedPhoneNumber(message.contact))
        }

        // Highlight text that matches filter
        if (currConstraint != "") {
            val index = contactTextBuilder.indexOf(currConstraint, ignoreCase = true)
            if (index >= 0 && label != null) {
                // Keep the blue lookup number readable when a search matches its digits.
                contactTextBuilder.setSpan(UnderlineSpan(), index, index + currConstraint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (index >= 0) {
                contactTextBuilder.setSpan(
                    BackgroundColorSpan(
                        ContextCompat.getColor(activity, R.color.highlight)
                    ),
                    index,
                    index + currConstraint.length,
                    SpannableString.SPAN_INCLUSIVE_EXCLUSIVE
                )
                contactTextBuilder.setSpan(
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

        holder.contactTextView.text = contactTextBuilder
        if (!BuildConfig.IS_DEMO && LocalContacts.number(message.contact) != null) {
            val editName = View.OnClickListener {
                if (conversationItems.any { it.checked }) holder.itemView.performClick()
                else ContactNameDialog.show(activity, message.contact) { refresh() }
            }
            holder.contactTextView.setOnClickListener(editName)
            holder.contactBadge.setOnClickListener(editName)
            holder.contactTextView.setOnLongClickListener { holder.itemView.performLongClick() }
        } else {
            holder.contactTextView.setOnClickListener(null)
            holder.contactTextView.isClickable = false
            holder.contactTextView.setOnLongClickListener(null)
            holder.contactTextView.isLongClickable = false
            holder.contactBadge.setOnClickListener { holder.itemView.performClick() }
        }
        val detail = label?.let { OnlineNumberLookup.description(activity, it) }
            ?: conversationItem.row.region?.let { activity.getString(R.string.lookup_offline_region, it) }
        val line = net.kourlas.voipms_sms.tools.LineProfiles.name(activity, message.did)
        val lineDetails = SpannableStringBuilder(listOfNotNull(detail, line).joinToString(" · "))
        if (line != null) {
            val accent = net.kourlas.voipms_sms.tools.LineProfiles.accent(activity, message.did, holder.senderRegion.currentTextColor)
            lineDetails.setSpan(ForegroundColorSpan(accent), lineDetails.length - line.length, lineDetails.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        holder.senderRegion.text = lineDetails
        holder.senderRegion.visibility = if (lineDetails.isBlank()) View.GONE else View.VISIBLE

        // Mark text as bold if unread
        ChatFont.setInboxWeight(holder.contactTextView, message.isUnread)
    }

    fun refreshLookupLabels() {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first >= 0 && last >= first) notifyItemRangeChanged(first, last - first + 1)
    }

    /**
     * Displays the text of the displayed message of the conversation on the
     * view holder. Selects and highlights part of the text if a filter is
     * configured. Marks text as bold if unread.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderMessageText(
        holder: ConversationViewHolder,
        position: Int
    ) {
        val conversationItem = conversationItems[position]
        val message = conversationItem.message

        val text = message.summaryDisplayText
        val messageTextBuilder = SpannableStringBuilder()

        if (message.text != "") {
            // Highlight text that matches filter
            val index = text.lowercase(Locale.getDefault()).indexOf(
                currConstraint.lowercase(Locale.getDefault())
            )
            if (currConstraint != "" && index != -1) {
                var nonMessageOffset = index
                if (message.isOutgoing) {
                    // Preface with "You: " if outgoing
                    val youStr = activity.getString(
                        R.string.conversations_message_you
                    ) + " "
                    messageTextBuilder.insert(0, youStr)
                    nonMessageOffset += youStr.length
                }

                // If match is in the middle of the string, show partial string
                var substringOffset = index - 20
                if (substringOffset > 0) {
                    messageTextBuilder.append("…")
                    nonMessageOffset += 1

                    while (text[substringOffset] != ' '
                        && substringOffset < index - 1
                    ) {
                        substringOffset += 1
                    }
                    substringOffset += 1
                } else {
                    substringOffset = 0
                }

                messageTextBuilder.append(text.substring(substringOffset))
                messageTextBuilder.setSpan(
                    BackgroundColorSpan(
                        ContextCompat.getColor(
                            activity,
                            R.color.highlight
                        )
                    ),
                    nonMessageOffset - substringOffset,
                    nonMessageOffset - substringOffset + currConstraint.length,
                    SpannableString.SPAN_INCLUSIVE_EXCLUSIVE
                )
                messageTextBuilder.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            activity, android.R.color.black
                        )
                    ),
                    nonMessageOffset - substringOffset,
                    nonMessageOffset - substringOffset + currConstraint.length,
                    SpannableString.SPAN_INCLUSIVE_EXCLUSIVE
                )
            } else {
                if (message.isOutgoing) {
                    // Preface with "You: " if outgoing
                    messageTextBuilder.append(
                        activity.getString(
                            R.string.conversations_message_you
                        )
                    )
                    messageTextBuilder.append(" ")
                }
                messageTextBuilder.append(text)
            }
        } else {
            if (message.isOutgoing) {
                // Preface with "You: " if outgoing
                messageTextBuilder.append(
                    activity.getString(
                        R.string.conversations_message_you
                    )
                )
                messageTextBuilder.append(" ")
            }

            val nonMessageOffset = messageTextBuilder.length

            messageTextBuilder.append(text)

            messageTextBuilder.setSpan(
                StyleSpan(Typeface.ITALIC), nonMessageOffset,
                messageTextBuilder.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }

        holder.messageTextView.text = conversationItem.row.reason?.let { "$it · $messageTextBuilder" } ?: messageTextBuilder

        // Mark text as bold and supporting additional lines if unread
        ChatFont.setInboxWeight(holder.messageTextView, message.isUnread)
        holder.messageTextView.maxLines = if (message.isUnread) 3 else 1
    }

    /**
     * Displays the date of the displayed message of the conversation
     * on the view holder. Shows special text if the message is a draft,
     * is sending or is not sent.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderDateText(
        holder: ConversationViewHolder,
        position: Int
    ) {
        val conversationItem = conversationItems[position]
        val message = conversationItem.message

        if (message.isDraft) {
            // Show indication that the first message is a draft
            val dateTextBuilder = SpannableStringBuilder()
            dateTextBuilder.append(
                activity.getString(
                    R.string.conversations_message_draft
                )
            )
            dateTextBuilder.setSpan(
                StyleSpan(Typeface.ITALIC), 0,
                dateTextBuilder.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
            holder.dateTextView.text = dateTextBuilder
        } else if (!message.isDelivered) {
            if (!message.isDeliveryInProgress) {
                // Show indication that the first message has not yet been sent
                val dateTextBuilder = SpannableStringBuilder()
                dateTextBuilder.append(
                    activity.getString(
                        R.string.conversations_message_not_sent
                    )
                )
                dateTextBuilder.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            activity,
                            R.color.send_error
                        )
                    ),
                    0, dateTextBuilder.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                dateTextBuilder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    dateTextBuilder.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                holder.dateTextView.text = dateTextBuilder
            } else {
                // Show indication that the first message is being sent
                holder.dateTextView.text = activity.getString(
                    R.string.conversations_message_sending
                )
            }
        } else {
            // Show date of message
            holder.dateTextView.text = getConversationsViewDate(
                activity, message.date
            )
        }
    }

    override fun getItemCount(): Int = conversationItems.size

    /**
     * Gets the number of items in the adapter that are checked.
     */
    fun getCheckedItemCount(): Int =
        conversationItems.filter { it.checked }.size

    operator fun get(i: Int): ConversationItem = conversationItems[i]

    override fun iterator(): Iterator<ConversationItem> =
        conversationItems.iterator()

    fun refresh(constraint: String = requestedConstraint) {
        requestedConstraint = constraint.trim()
        val query = requestedConstraint
        val activeDid = getActiveDid(activity)
        val dids = if (activeDid.isEmpty()) getDids(activity, onlyShowInConversationsView = true) else setOf(activeDid)
        refreshJob?.cancel()
        refreshJob = activity.lifecycleScope.launch {
            try {
                val vault = privateView
                val rows = withContext(loader) { loadRows(dids, query, vault) }
                loadedRows = rows
                val searchChanged = currConstraint != query
                currConstraint = query
                publishRows(searchChanged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load conversations", e)
                showSnackbar(activity, R.id.coordinator_layout, activity.getString(R.string.conversations_error_refresh))
            }
        }
    }

    override fun onViewRecycled(holder: ConversationViewHolder) {
        holder.avatarJob?.cancel()
        holder.avatarJob = null
        holder.avatarKey = null
        holder.contactBadge.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    private suspend fun loadRows(dids: Set<String>, query: String, vault: Boolean): List<InboxRow> {
        val previews = if (BuildConfig.IS_DEMO) {
            getConversationsDemoMessages().map { Database.InboxPreview(it, null, it.text, it.isOutgoing) }
        } else {
            val db = Database.getInstance(activity)
            val filter = query.lowercase(Locale.getDefault())
            val regular = db.inboxPreviews(dids, filter, activity is ConversationsArchivedActivity, contactNameCache)
            if (vault) (regular + db.inboxPreviews(dids, filter, true, contactNameCache)).sortedByDescending { it.message.date }
            else regular
        }
        val locale = activity.resources.configuration.locales[0]
        val profiles = net.kourlas.voipms_sms.privacy.JunkProfiles(activity)
        return previews.filterNot {
            net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(activity, it.message.conversationId) != vault ||
                (!vault && net.kourlas.voipms_sms.tools.ThreadReminders.hidden(activity, it.message.conversationId))
        }.map { preview ->
            val message = preview.message
            val contact = message.contact
            val name = if (BuildConfig.IS_DEMO) net.kourlas.voipms_sms.demo.getContactName(contact)
                       else LocalContacts.find(activity, contact) ?: getContactName(activity, contact, contactNameCache)
            val assessment = InboxRules.assess(preview.latestIncoming, profiles.line(message.did))
            val other = preview.otherFolder ?: assessment.shouldQuarantine(preview.hasReply, name != null)
            val reason = when (preview.otherFolder) {
                true -> activity.getString(R.string.spam_manual)
                false -> null
                null -> assessment.description
            }
            val region = if (name == null) NumberOrigin.offline(contact, locale) else null
            InboxRow(message, name, region, other, reason)
        }
    }

    fun showFolder(other: Boolean) {
        if (showOther == other) return
        showOther = other
        publishRows(false)
        layoutManager.scrollToPositionWithOffset(0, 0)
    }

    private fun publishRows(searchChanged: Boolean) {
        val archived = activity is ConversationsArchivedActivity
        val rows = if (archived || privateView) loadedRows else loadedRows.filter { it.other == showOther }
        val old = _conversationItems.toList()
        val selected = old.filter { it.checked }.mapTo(mutableSetOf()) { it.message.conversationId }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = rows.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].message.conversationId == rows[b].message.conversationId
            override fun areContentsTheSame(a: Int, b: Int) = !searchChanged && old[a].row == rows[b]
        }, false)
        _conversationItems.clear()
        rows.mapTo(_conversationItems) { ConversationItem(it, it.message.conversationId in selected) }
        diff.dispatchUpdatesTo(this)
        activity.findViewById<TextView>(R.id.empty_text).text = when {
            rows.isNotEmpty() -> ""
            privateView -> activity.getString(R.string.private_empty)
            currConstraint.isNotEmpty() -> activity.getString(R.string.conversations_no_results, currConstraint)
            !archived && showOther -> activity.getString(R.string.inbox_other_empty)
            getDids(activity, onlyShowInConversationsView = true).isEmpty() -> activity.getString(R.string.conversations_no_dids)
            archived -> activity.getString(R.string.conversations_archived_no_messages)
            else -> activity.getString(R.string.conversations_no_messages)
        }
    }

    internal data class InboxRow(
        val message: Message, val name: String?, val region: String?, val other: Boolean, val reason: String? = null
    )

    inner class ConversationItem internal constructor(internal val row: InboxRow, initiallyChecked: Boolean) {
        val message get() = row.message
        val contactName get() = row.name
        private var _checked = initiallyChecked
        val checked: Boolean
            get() = _checked

        /**
         * Sets whether or not the conversation item is checked.
         *
         * @param position The position of the message item in the adapter.
         */
        fun setChecked(checked: Boolean, position: Int) {
            val previous = _checked
            _checked = checked

            if ((previous && !_checked) || (!previous && _checked)) {

                recyclerView.findViewHolderForAdapterPosition(position)?.let {
                    @Suppress("UNCHECKED_CAST")
                    updateViewHolderContactBadge(
                        it as ConversationsRecyclerViewAdapter<T>
                        .ConversationViewHolder, position
                    )
                } ?: run {
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
     * A container for the views associated with a conversation item.
     *
     * @param itemView The primary view of the conversation item.
     */
    inner class ConversationViewHolder internal constructor(itemView: View) :
        RecyclerView.ViewHolder(itemView) {
        val restoreThread: View = itemView.findViewById(R.id.restore_inbox_thread)
        internal val unreadDot: View = itemView.findViewById(R.id.unread_dot)
        internal var avatarJob: Job? = null
        internal var avatarKey: Pair<String, String?>? = null

        // All configurable views on a message item
        internal val viewSwitcher: ViewSwitcher =
            itemView.findViewById(R.id.view_switcher)
        internal val contactBadge: QuickContactBadge =
            itemView.findViewById(R.id.photo)
        internal val contactTextView: TextView =
            itemView.findViewById(R.id.contact)
        internal val senderRegion: TextView = itemView.findViewById(R.id.sender_region)
        internal val messageTextView: TextView =
            itemView.findViewById(R.id.message)
        internal val dateTextView: TextView =
            itemView.findViewById(R.id.date)

        init {
            // Allow the conversation view itself to be clickable and
            // selectable
            itemView.isClickable = true
            itemView.setOnClickListener(activity)
            itemView.isLongClickable = true
            itemView.setOnLongClickListener(activity)

            // Apply circular mask to and remove overlay from contact badge
            // to match Android Messages aesthetic; in addition, set up
            // check box image
            applyCircularMask(contactBadge)
            contactBadge.setOverlay(null)
            val contactBadgeChecked = itemView
                .findViewById<ImageView>(R.id.conversations_photo_checked)
            applyCircularMask(contactBadgeChecked)
        }
    }
}
