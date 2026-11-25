package net.kourlas.voipms_sms.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class NearbyWireTest {
    @Test fun refusesUnknownAndOversizedFrames() {
        fun decode(bytes: ByteArray) = NearbyWire.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertThrows(java.net.ProtocolException::class.java) { decode(byteArrayOf(7)) }
        assertThrows(java.net.ProtocolException::class.java) { decode(byteArrayOf(1) + ByteArray(16) + byteArrayOf(0x7f, 0xff.toByte())) }
        assertFalse(NearbyWire.validText(" "))
        assertFalse(NearbyWire.validText("❤".repeat(3000)))
        assertTrue(NearbyWire.validText("a".repeat(8192)))
    }

    @Test fun comparisonDependsOnBothPeersAndIsSymmetric() {
        assertEquals(NearbyWire.comparison("a", "b"), NearbyWire.comparison("b", "a"))
        assertNotEquals(NearbyWire.comparison("a", "b"), NearbyWire.comparison("a", "c"))
        assertThrows(IllegalArgumentException::class.java) { NearbyWire.comparison("a", "a") }
    }

    /** Uses two fresh EC identities and the production certificate, TLS and message framing code. */
    @Test fun confirmedMutualTlsTransfersUnicodeTextAndPeerReceipt() {
        val dir = Files.createTempDirectory("nearby-tls").toFile()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val (left, leftId) = makeIdentity(dir, "left")
            val (right, rightId) = makeIdentity(dir, "right")
            val expected = NearbyWire.comparison(leftId, rightId)
            val message = NearbyWire.Frame.Text(UUID.randomUUID(), "Olá · rendez-vous à onze heures")
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 12000
                val result =
                    pool.submit<String> {
                        server.accept().use { plain ->
                            (left.socketFactory.createSocket(plain, "localhost", plain.port, true) as SSLSocket).use { socket ->
                                socket.soTimeout = 12000
                                NearbyWire.configure(socket, true)
                                socket.startHandshake()
                                val actual = NearbyWire.keyId(socket.session.peerCertificates.first() as X509Certificate)
                                val read = DataInputStream(socket.inputStream)
                                val write = DataOutputStream(socket.outputStream)
                                NearbyWire.hello(write, "left")
                                assertEquals("right", NearbyWire.readHello(read))
                                val code = NearbyWire.comparison(leftId, actual)
                                assertEquals(expected, code)
                                write.writeByte(1)
                                write.flush()
                                assertEquals(1, read.readUnsignedByte())
                                assertEquals(message, NearbyWire.read(read))
                                NearbyWire.write(write, NearbyWire.Frame.Receipt(message.id))
                                code
                            }
                        }
                    }
                (right.socketFactory.createSocket() as SSLSocket).use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 10000)
                    socket.soTimeout = 12000
                    NearbyWire.configure(socket, false)
                    socket.startHandshake()
                    val actual = NearbyWire.keyId(socket.session.peerCertificates.first() as X509Certificate)
                    val read = DataInputStream(socket.inputStream)
                    val write = DataOutputStream(socket.outputStream)
                    NearbyWire.hello(write, "right")
                    assertEquals("left", NearbyWire.readHello(read))
                    assertEquals(expected, NearbyWire.comparison(rightId, actual))
                    write.writeByte(1)
                    write.flush()
                    assertEquals(1, read.readUnsignedByte())
                    NearbyWire.write(write, message)
                    assertEquals(NearbyWire.Frame.Receipt(message.id), NearbyWire.read(read))
                }
                assertEquals(expected, result.get(12, TimeUnit.SECONDS))
            }
        } finally {
            pool.shutdownNow()
            dir.deleteRecursively()
        }
    }

    private fun makeIdentity(
        dir: File,
        name: String,
    ): Pair<SSLContext, String> {
        val file = File(dir, "$name.p12")
        val pass = "fixture-password"
        val tool = File(System.getProperty("java.home"), "bin/keytool").path
        val command =
            ProcessBuilder(
                tool,
                "-genkeypair",
                "-alias",
                name,
                "-keystore",
                file.path,
                "-storetype",
                "PKCS12",
                "-storepass",
                pass,
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-sigalg",
                "SHA256withECDSA",
                "-dname",
                "CN=$name",
                "-validity",
                "2",
                "-noprompt",
            ).redirectErrorStream(true).start()
        val log = command.inputStream.bufferedReader().readText()
        assertTrue(log, command.waitFor(20, TimeUnit.SECONDS) && command.exitValue() == 0)
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, pass.toCharArray()) } }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, pass.toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(NearbyWire.DeviceCertificates), null) } to
            NearbyWire.keyId(store.getCertificate(name) as X509Certificate)
    }
}
