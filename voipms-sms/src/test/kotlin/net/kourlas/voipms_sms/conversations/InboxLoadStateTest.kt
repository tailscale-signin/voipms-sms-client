package net.kourlas.voipms_sms.conversations

import org.junit.Assert.*
import org.junit.Test

class InboxLoadStateTest {
    @Test fun hiddenOrReplacedRequestsCannotPublishSuccessOrError() {
        val state = InboxLoadState()
        val messages = state.begin()
        val other = state.begin()
        assertFalse(state.finish(messages, false))
        assertFalse(state.finish(messages, true))
        assertTrue(state.current(other))
        state.hide()
        assertFalse(state.finish(other, true))
        assertEquals(InboxPhase.HIDDEN, state.phase)
    }

    @Test fun retryHasItsOwnGenerationAndCanReachReadyWithAnEmptyResult() {
        val state = InboxLoadState()
        val failed = state.begin()
        assertTrue(state.finish(failed, false))
        assertEquals(InboxPhase.FAILED, state.phase)
        val retry = state.begin()
        assertFalse(state.finish(failed, true))
        assertEquals(InboxPhase.LOADING, state.phase)
        assertTrue(state.finish(retry, true))
        assertEquals(InboxPhase.READY, state.phase)
    }

    @Test fun matchingUsesOffsetsInTheOriginalTextAndAlias() {
        assertEquals(1..1, inboxMatch("İX", "x"))
        assertEquals(2..5, inboxMatch("İ Café", "café"))
        assertEquals(2..3, inboxMatch("😀OK", "ok"))
        assertNull(inboxMatch("İX", "missing"))
        assertNull(inboxMatch("İX", ""))
    }
}
