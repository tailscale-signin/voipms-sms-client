package net.kourlas.voipms_sms.rich

import android.content.Context
import androidx.core.content.edit
import net.kourlas.voipms_sms.rich.ui.ChatLinks
import net.kourlas.voipms_sms.rich.ui.ChatRoute
import net.kourlas.voipms_sms.rich.ui.RoomLink
import net.kourlas.voipms_sms.sms.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Exercise the same encrypted Keystore preferences as the phone-thread composer on the emulator. */
internal fun verifyPhoneLink(context: Context, owner: String, room: String, peer: String) {
    val thread = ConversationId("4165550100", "4165550101")
    val otherLine = ConversationId("4165550102", thread.contact)
    val link = RoomLink(owner, room, peer)
    val privateThreads = context.getSharedPreferences("private-threads", Context.MODE_PRIVATE)
    val firstDraft = ChatLinks.draftKey("matrix", owner, room, peer, thread.getId())
    val otherDraft = ChatLinks.draftKey("matrix", owner, room, peer, otherLine.getId())
    try {
        assertEquals(ChatRoute.SMS, ChatLinks.route(context, thread))
        assertTrue(ChatLinks.bind(context, thread, link))
        assertEquals(link, ChatLinks.linked(context, thread))
        assertEquals(thread, ChatLinks.linkedThread(context, owner, room))
        assertFalse(ChatLinks.bind(context, otherLine, link))
        assertNull(ChatLinks.linked(context, otherLine))
        assertNull(ChatLinks.linkedThread(context, "another account", room))

        ChatLinks.route(context, thread, ChatRoute.MATRIX)
        ChatLinks.saveDraft(context, firstDraft, "Private draft")
        assertEquals(ChatRoute.SMS, ChatLinks.route(context, otherLine))
        assertEquals("", ChatLinks.draft(context, otherDraft))
        assertTrue(ChatLinks.visibleInHub(context, owner, room))
        privateThreads.edit { putBoolean(thread.getId(), true) }
        assertFalse(ChatLinks.visibleInHub(context, owner, room))
        assertEquals(link, ChatLinks.linked(context, thread))
        ChatLinks.route(context, thread, ChatRoute.SMS)
        assertEquals("Private draft", ChatLinks.draft(context, firstDraft))
    } finally {
        ChatLinks.saveDraft(context, firstDraft, "")
        ChatLinks.unlink(context, thread)
        ChatLinks.route(context, thread, ChatRoute.SMS)
        privateThreads.edit { remove(thread.getId()) }
    }
    assertTrue(ChatLinks.visibleInHub(context, owner, room))
    assertNull(ChatLinks.linkedThread(context, owner, room))
}
