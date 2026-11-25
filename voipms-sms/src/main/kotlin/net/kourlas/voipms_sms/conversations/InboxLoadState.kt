package net.kourlas.voipms_sms.conversations

import androidx.lifecycle.ViewModel
import net.kourlas.voipms_sms.sms.ConversationId

internal data class InboxSelection(val account: Long, val dids: Set<String>, val query: String, val private: Boolean, val other: Boolean)

/** Retain a public scroll anchor in memory across a thread visit or rotation. Never save it to the instance-state bundle. */
internal class InboxScrollPosition : ViewModel() {
    data class Anchor(val id: ConversationId, val index: Int, val offset: Int)
    private var saved: Pair<InboxSelection, Anchor>? = null

    fun remember(scope: InboxSelection, anchor: Anchor) {
        saved = if (scope.private) null else scope to anchor
    }

    fun take(scope: InboxSelection): Anchor? {
        val entry = saved
        saved = null
        return entry?.takeIf { it.first == scope && !scope.private }?.second
    }

    fun clear() { saved = null }
}

internal enum class InboxPhase { HIDDEN, LOADING, READY, FAILED }

/** Monotonically identifies requests, including two refreshes of the same folder. Main-thread only. */
internal class InboxLoadState {
    var phase = InboxPhase.HIDDEN
        private set
    private var generation = 0L

    fun begin(): Long {
        phase = InboxPhase.LOADING
        return ++generation
    }

    fun current(request: Long) = request == generation && phase == InboxPhase.LOADING

    fun finish(request: Long, success: Boolean): Boolean {
        if (!current(request)) return false
        phase = if (success) InboxPhase.READY else InboxPhase.FAILED
        return true
    }

    fun hide() {
        generation++
        phase = InboxPhase.HIDDEN
    }
}

/** Indices must refer to the displayed string: lowercasing some Unicode characters increases length. */
internal fun inboxMatch(text: CharSequence, query: String): IntRange? {
    if (query.isEmpty()) return null
    val start = text.indexOf(query, ignoreCase = true)
    return if (start < 0) null else start until start + query.length
}
