package net.kourlas.voipms_sms.conversations

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
