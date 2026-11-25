package net.kourlas.voipms_sms.conversations

import net.kourlas.voipms_sms.sms.ConversationId
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

    @Test fun scrollRestoresOnceForTheSamePublicAccountLineQueryAndFolder() {
        val scroll = InboxScrollPosition()
        val scope = InboxSelection(2, setOf("line"), "", false, false)
        val anchor = InboxScrollPosition.Anchor(ConversationId("line", "contact"), 8, 11)
        scroll.remember(scope, anchor)
        assertEquals(anchor, scroll.take(scope))
        assertNull(scroll.take(scope))
        listOf(scope.copy(account=3), scope.copy(dids=emptySet()), scope.copy(query="search"),
            scope.copy(other=true), scope.copy(private=true)).forEach { different ->
            scroll.remember(scope, anchor)
            assertNull(scroll.take(different))
        }
        scroll.remember(scope.copy(private=true), anchor)
        assertNull(scroll.take(scope.copy(private=true)))
    }

    @Test fun matchingUsesOffsetsInTheOriginalTextAndAlias() {
        assertEquals(1..1, inboxMatch("İX", "x"))
        assertEquals(2..5, inboxMatch("İ Café", "café"))
        assertEquals(2..3, inboxMatch("😀OK", "ok"))
        assertNull(inboxMatch("İX", "missing"))
        assertNull(inboxMatch("İX", ""))
    }
}
