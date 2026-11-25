package net.kourlas.voipms_sms.nearby

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket

internal class NearbyLink : Closeable {
    private val resources = CopyOnWriteArrayList<Closeable>()

    @Volatile private var closed = false

    private fun <T : Closeable> track(resource: T): T {
        resources.add(resource)
        if (closed) {
            resource.close()
            throw SocketException("Disconnected")
        }
        return resource
    }

    override fun close() {
        closed = true
        // Interrupt the underlying sockets before TLS shutdown touches a blocked reader.
        resources.forEach { runCatching { it.close() } }
    }

    private suspend fun peerSocket(
        host: Boolean,
        owner: InetAddress,
    ): Socket {
        require(!owner.isAnyLocalAddress && !owner.isLoopbackAddress && !owner.isMulticastAddress)
        if (host) {
            return track(ServerSocket()).use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(owner, NearbyWire.PORT), 1)
                server.soTimeout = 120_000
                track(server.accept())
            }
        }
        for (attempt in 1..12) {
            val client = track(Socket())
            try {
                client.connect(InetSocketAddress(owner, NearbyWire.PORT), 2500)
                return client
            } catch (e: java.io.IOException) {
                client.close()
                if (attempt == 12 || closed) throw e
                delay(450)
            }
        }
        error("Connection failed")
    }

    suspend fun exchange(
        c: Context,
        account: String,
        host: Boolean,
        owner: InetAddress,
        approve: suspend (String, String, String) -> Boolean,
        connected: suspend (String, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val identity = NearbyKeys.identity(account)
        val raw = peerSocket(host, owner)
        val socket = track(identity.context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket)
        NearbyWire.configure(socket, host)
        socket.soTimeout = 15_000
        socket.startHandshake()
        val peer = NearbyWire.keyId(socket.session.peerCertificates.first() as X509Certificate)
        val code = NearbyWire.comparison(identity.id, peer)
        val reader = DataInputStream(socket.inputStream.buffered())
        val writer = DataOutputStream(socket.outputStream.buffered())
        val name =
            Build.MODEL
                .orEmpty()
                .take(30)
                .ifBlank { "Android" }
        NearbyWire.hello(writer, name)
        val label = NearbyWire.readHello(reader)
        socket.soTimeout = 120_000
        if (!approve(peer, label, code)) return@withContext
        writer.writeByte(1)
        writer.flush()
        if (reader.readUnsignedByte() != 1) return@withContext
        NearbyStore.remember(c, account, peer, label)
        connected(peer, label)
        socket.soTimeout = 0
        coroutineScope {
            val sender =
                launch(Dispatchers.IO) {
                    try {
                        val inFlight = mutableSetOf<String>()
                        NearbyStore.outbox(c, account, peer).collect { pending ->
                            inFlight.retainAll(pending.map { it.id }.toSet())
                            for (msg in pending) {
                                if (inFlight.add(msg.id)) {
                                    check(NearbyStore.scope(c) == account)
                                    synchronized(
                                        writer,
                                    ) { NearbyWire.write(writer, NearbyWire.Frame.Text(UUID.fromString(msg.id), msg.text)) }
                                }
                            }
                        }
                    } finally {
                        socket.close()
                    }
                }
            try {
                while (!closed) {
                    when (val frame = NearbyWire.read(reader)) {
                        is NearbyWire.Frame.Text -> {
                            NearbyStore.receive(c, account, peer, frame)
                            synchronized(writer) { NearbyWire.write(writer, NearbyWire.Frame.Receipt(frame.id)) }
                        }
                        is NearbyWire.Frame.Receipt -> NearbyStore.acknowledge(c, account, peer, frame.id)
                    }
                }
            } finally {
                sender.cancel()
                socket.close()
            }
        }
    }
}
