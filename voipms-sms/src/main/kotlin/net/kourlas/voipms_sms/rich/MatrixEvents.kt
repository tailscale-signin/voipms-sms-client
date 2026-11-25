package net.kourlas.voipms_sms.rich

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener

internal data class RichRoom(val id: String, val title: String, val invited: Boolean, val directPeer: String?)
internal data class RichTimeline(val events: List<RichEvent>, val typing: List<String>)
internal data class RichEvent(val id: String, val sender: String, val body: String, val timestamp: Long,
                             val mine: Boolean, val status: String, val mime: String?)

internal fun MessageType.mimeType(): String? = when (this) {
    is MessageType.Image -> content.info?.mimetype ?: "image/*"
    is MessageType.Audio -> content.info?.mimetype ?: "audio/*"
    is MessageType.Video -> content.info?.mimetype ?: "video/*"
    is MessageType.File -> content.info?.mimetype ?: "application/octet-stream"
    else -> null
}

private fun TimelineItem.event(): RichEvent? {
    val value = asEvent() ?: return null
    try {
        val kind = (value.content as? TimelineItemContent.MsgLike)?.content?.kind ?: return null
        val message = (kind as? MsgLikeKind.Message)?.content
        val body = when {
            message != null -> message.body
            kind is MsgLikeKind.UnableToDecrypt -> "Waiting for an encryption key"
            kind is MsgLikeKind.Redacted -> "Message removed"
            else -> return null
        }
        val id = when (val identifier = value.eventOrTransactionId) {
            is EventOrTransactionId.EventId -> identifier.eventId
            is EventOrTransactionId.TransactionId -> identifier.transactionId
        }
        val status = when {
            !value.isOwn -> ""
            value.localSendState is EventSendState.SendingFailed -> "Send failed"
            value.localSendState is EventSendState.NotSentYet -> "Queued"
            value.readReceipts.keys.any { it != value.sender } -> "Read"
            value.isRemote || value.localSendState is EventSendState.Sent -> "Sent"
            else -> "Queued"
        }
        return RichEvent(id, value.sender, body, value.timestamp.toLong(), value.isOwn, status, message?.msgType?.mimeType())
    } finally { value.destroy() }
}

internal fun roomUpdates(room: Room, timeline: Timeline): Flow<RichTimeline> = callbackFlow {
    // Keep placeholders for virtual (date/read marker) items: native diff offsets include them.
    val entries = mutableListOf<RichEvent?>()
    var typing = emptyList<String>()
    val lock = Any()
    fun publish() { trySend(RichTimeline(entries.filterNotNull(), typing)) }
    val messages = timeline.addListener(object : TimelineListener {
        override fun onUpdate(diff: List<TimelineDiff>) {
            synchronized(lock) {
                try {
                    for (change in diff) when (change) {
                        is TimelineDiff.Append -> entries.addAll(change.values.map { it.event() })
                        is TimelineDiff.Reset -> { entries.clear(); entries.addAll(change.values.map { it.event() }) }
                        is TimelineDiff.Clear -> entries.clear()
                        is TimelineDiff.PushBack -> entries.add(change.value.event())
                        is TimelineDiff.PushFront -> entries.add(0, change.value.event())
                        is TimelineDiff.PopBack -> entries.removeAt(entries.lastIndex)
                        is TimelineDiff.PopFront -> entries.removeAt(0)
                        is TimelineDiff.Insert -> entries.add(change.index.toInt(), change.value.event())
                        is TimelineDiff.Set -> entries[change.index.toInt()] = change.value.event()
                        is TimelineDiff.Remove -> entries.removeAt(change.index.toInt())
                        is TimelineDiff.Truncate -> entries.subList(change.length.toInt(), entries.size).clear()
                    }
                    publish()
                } catch (e: Exception) { close(e) }
                finally { diff.forEach { it.destroy() } }
            }
        }
    })
    try {
        val own = room.ownUserId()
        val users = room.subscribeToTypingNotifications(object : TypingNotificationsListener {
            override fun call(typingUserIds: List<String>) = synchronized(lock) {
                typing = typingUserIds.filter { it != own }
                publish()
            }
        })
        try { awaitClose() } finally { users.cancel(); users.close() }
    } finally { messages.cancel(); messages.close() }
}.conflate()
