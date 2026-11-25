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
    private var showOther = false
    private var refreshJob: Job? = null
    private val loader = Dispatchers.IO.limitedParallelism(1)
    private var loadedRows: List<InboxRow> = emptyList()

    // Caches
    private val contactNameCache = mutableMapOf<String, String>()
    private val contactBitmapCache = mutableMapOf<String, Bitmap>()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ConversationViewHolder {
        // There is only one item view type
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.conversations_item, parent, false)
        itemView.clipToOutline = true
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

        holder.viewSwitcher.displayedChild =
            if (conversationItem.checked) 1 else 0
        if (!conversationItem.checked) {
            holder.contactBadge.assignContactFromPhone(message.contact, true)
            holder.contactBadge.setImageBitmap(
                conversationItem.contactBitmap
            )
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
            contactTextBuilder.append(" · ").append(OnlineNumberLookup.description(activity, label))
        } else {
            contactTextBuilder.append(conversationItem.contactName ?: getFormattedPhoneNumber(message.contact))
            conversationItem.row.region?.let { contactTextBuilder.append(" · ").append(it) }
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

        holder.messageTextView.text = messageTextBuilder

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
                val rows = withContext(loader) { loadRows(dids, query) }
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

    private suspend fun loadRows(dids: Set<String>, query: String): List<InboxRow> {
        val previews = if (BuildConfig.IS_DEMO) {
            getConversationsDemoMessages().map { Database.InboxPreview(it, null, it.text, it.isOutgoing) }
        } else {
            Database.getInstance(activity).inboxPreviews(
                dids, query.lowercase(Locale.getDefault()), activity is ConversationsArchivedActivity, contactNameCache
            )
        }
        val size = activity.resources.getDimensionPixelSize(R.dimen.contact_badge)
        val locale = activity.resources.configuration.locales[0]
        return previews.map { preview ->
            val message = preview.message
            val contact = message.contact
            val name = if (BuildConfig.IS_DEMO) net.kourlas.voipms_sms.demo.getContactName(contact)
                       else getContactName(activity, contact, contactNameCache)
            val other = preview.otherFolder ?: InboxRules.suspectedJunk(preview.latestIncoming, preview.hasReply, name != null)
            val region = if (name == null) NumberOrigin.offline(contact, locale) else null
            InboxRow(message, name, getContactPhotoBitmap(activity, name, contact, size, contactBitmapCache), region, other)
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
        val rows = if (archived) loadedRows else loadedRows.filter { it.other == showOther }
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
            currConstraint.isNotEmpty() -> activity.getString(R.string.conversations_no_results, currConstraint)
            !archived && showOther -> activity.getString(R.string.inbox_other_empty)
            getDids(activity, onlyShowInConversationsView = true).isEmpty() -> activity.getString(R.string.conversations_no_dids)
            archived -> activity.getString(R.string.conversations_archived_no_messages)
            else -> activity.getString(R.string.conversations_no_messages)
        }
    }

    internal data class InboxRow(
        val message: Message, val name: String?, val bitmap: Bitmap, val region: String?, val other: Boolean
    )

    inner class ConversationItem internal constructor(internal val row: InboxRow, initiallyChecked: Boolean) {
        val message get() = row.message
        val contactName get() = row.name
        val contactBitmap get() = row.bitmap
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
        // All configurable views on a message item
        internal val viewSwitcher: ViewSwitcher =
            itemView.findViewById(R.id.view_switcher)
        internal val contactBadge: QuickContactBadge =
            itemView.findViewById(R.id.photo)
        internal val contactTextView: TextView =
            itemView.findViewById(R.id.contact)
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
