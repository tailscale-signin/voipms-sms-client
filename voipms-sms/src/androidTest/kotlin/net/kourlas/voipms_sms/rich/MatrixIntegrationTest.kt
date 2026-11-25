package net.kourlas.voipms_sms.rich

import android.util.Base64
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncListenerV2
import org.matrix.rustcomponents.sdk.SyncResponseV2
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(AndroidJUnit4::class)
class MatrixIntegrationTest {
    @Test fun exchangeEncryptedMessagesAndMedia() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        // This test only runs with the disposable server and a CA supplied by the CI runner.
        val encodedCa = args.getString("matrixCa")
        org.junit.Assume.assumeNotNull(encodedCa)
        val ca = Base64.decode(encodedCa, Base64.NO_WRAP)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://localhost:18443"
        val password = requireNotNull(args.getString("matrixPassword"))
        matrixLoopbackTestCa = ca
        val bob = ClientBuilder().homeserverUrl(url).addRootCertificates(listOf(ca))
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NONE).inMemoryStore().build()
        try {
            withTimeout(180_000) {
                MatrixChat.login(context, url, "alice", password)
                bob.login("bob", password, "Matrix CI peer", null)
                val bobId = bob.userId()
                val bobSync = bob.syncV2(SyncSettingsV2(15_000uL), object : SyncListenerV2 {
                    override fun onUpdate(response: SyncResponseV2) = Unit
                })
                try {
                    val room = MatrixChat.create(context, "Encrypted integration", listOf(bobId))
                    while (bob.getRoom(room)?.use { it.membership() } != Membership.INVITED) delay(300)
                    requireNotNull(bob.getRoom(room)).use { it.join() }
                    while (bob.getRoom(room)?.use { it.membership() } != Membership.JOINED) delay(300)
                    val aliceId = requireNotNull(MatrixChat.account(context))
                    val listed = MatrixChat.rooms(context).first { it.id == room }
                    assertEquals(bobId, listed.directPeer)
                    verifyPhoneLink(context, requireNotNull(MatrixChat.identity(context)), room, bobId)
                    requireNotNull(bob.getRoom(room)).use { remote ->
                        remote.timeline().use { timeline ->
                            val message = "Only the two SDK clients can read this text."
                            val inbound = async(start = CoroutineStart.UNDISPATCHED) {
                                roomUpdates(remote, timeline).first { it.events.any { e -> e.body == message } }.events.first { it.body == message }
                            }
                            MatrixChat.send(context, room, message, bobId)
                            val received = inbound.await()
                            assertEquals(aliceId, received.sender)
                            val wire = rawEvent(url, room, received.id, bob.session().accessToken, ca)
                            assertEquals("m.room.encrypted", wire.getString("type"))
                            assertFalse(wire.toString().contains(message))
                            val typing = async(start = CoroutineStart.UNDISPATCHED) {
                                roomUpdates(remote, timeline).first { aliceId in it.typing }
                            }
                            MatrixChat.typing(context, room, true, bobId)
                            typing.await()
                            MatrixChat.typing(context, room, false, bobId)
                            remote.markAsRead(ReceiptType.READ)
                            MatrixChat.observe(context, room, bobId).first { state ->
                                state.events.any { it.body == message && it.status == "Read" }
                            }
                            messageEventContentFromMarkdown("Peer reply").use { timeline.send(it).close() }
                            MatrixChat.observe(context, room, bobId).first { state ->
                                state.events.any { !it.mine && it.body == "Peer reply" }
                            }
                            val bytes = "Private attachment contents".toByteArray()
                            val file = File(context.cacheDir, "mms_preview/matrix-check.txt")
                            file.parentFile!!.mkdirs()
                            file.writeBytes(bytes)
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".message_media", file)
                            val attachment = async(start = CoroutineStart.UNDISPATCHED) {
                                roomUpdates(remote, timeline).first { state -> state.events.any { it.mime != null } }.events.first { it.mime != null }
                            }
                            MatrixChat.attach(context, room, uri, bobId)
                            val event = attachment.await()
                            assertEquals("m.room.encrypted", rawEvent(url, room, event.id, bob.session().accessToken, ca).getString("type"))
                            val opened = MatrixChat.media(context, room, event.id, bobId)
                            assertArrayEquals(bytes, context.contentResolver.openInputStream(opened)!!.use { it.readBytes() })
                        }
                    }
                    val group = MatrixChat.create(context, "Private group", listOf(bobId, "@charlie:matrix.local"))
                    assertNull(MatrixChat.rooms(context).first { it.id == group }.directPeer)
                    var refused = false
                    try { MatrixChat.send(context, group, "must stay out of group", bobId) }
                    catch (_: IllegalStateException) { refused = true }
                    assertTrue(refused)
                    MatrixChat.logout(context)
                    assertNull(MatrixChat.account(context))
                } finally { bobSync.cancel(); bobSync.close() }
            }
        } finally {
            bob.close()
            matrixLoopbackTestCa = null
        }
    }

    private fun rawEvent(url: String, room: String, event: String, token: String, ca: ByteArray): JSONObject {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("local", CertificateFactory.getInstance("X.509").generateCertificate(ca.inputStream()))
        }
        factory.init(trust)
        val tls = SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        val path = "/_matrix/client/v3/rooms/" + URLEncoder.encode(room, "UTF-8") +
            "/event/" + URLEncoder.encode(event, "UTF-8")
        val req = URL(url + path).openConnection() as HttpsURLConnection
        return try {
            req.sslSocketFactory = tls.socketFactory
            req.setRequestProperty("Authorization", "Bearer $token")
            req.connectTimeout = 8000
            req.readTimeout = 8000
            JSONObject(req.inputStream.bufferedReader().use { it.readText() })
        } finally { req.disconnect() }
    }
}
