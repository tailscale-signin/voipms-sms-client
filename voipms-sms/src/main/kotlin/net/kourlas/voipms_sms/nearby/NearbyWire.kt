package net.kourlas.voipms_sms.nearby

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ProtocolException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.UUID
import javax.net.ssl.X509TrustManager

/** Framing inside mutual TLS. The peer still needs out-of-band comparison before any message is exchanged. */
internal object NearbyWire {
    const val PORT = 40847
    const val MAX_TEXT = 8192
    private const val VERSION = 0x564e4201
    private const val MESSAGE = 1
    private const val ACK = 2

    sealed interface Frame {
        val id: UUID

        data class Text(
            override val id: UUID,
            val body: String,
        ) : Frame

        data class Receipt(
            override val id: UUID,
        ) : Frame
    }

    fun configure(
        socket: javax.net.ssl.SSLSocket,
        server: Boolean,
    ) {
        socket.useClientMode = !server
        if (server) socket.needClientAuth = true
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        socket.enabledCipherSuites =
            socket.supportedCipherSuites
                .filter {
                    it.startsWith("TLS_AES_") ||
                        it.startsWith("TLS_CHACHA20_") ||
                        it.startsWith("TLS_ECDHE_ECDSA_WITH_AES_") &&
                        it.contains("_GCM_")
                }.toTypedArray()
    }

    fun validText(text: String) = text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT

    private fun data(
        input: DataInputStream,
        limit: Int,
    ): String {
        val size = input.readUnsignedShort()
        if (size !in 1..limit) throw ProtocolException("Invalid field length")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return Charsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun field(
        output: DataOutputStream,
        text: String,
        max: Int,
    ) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..max)
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    fun hello(
        output: DataOutputStream,
        deviceName: String,
    ) {
        output.writeInt(VERSION)
        field(output, deviceName, 128)
        output.flush()
    }

    fun readHello(input: DataInputStream): String {
        if (input.readInt() != VERSION) throw ProtocolException("Incompatible peer")
        return data(input, 128)
            .filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
            .trim()
            .take(64)
            .ifEmpty { throw ProtocolException("Missing peer name") }
    }

    fun write(
        output: DataOutputStream,
        frame: Frame,
    ) {
        output.writeByte(if (frame is Frame.Text) MESSAGE else ACK)
        output.writeLong(frame.id.mostSignificantBits)
        output.writeLong(frame.id.leastSignificantBits)
        if (frame is Frame.Text) {
            require(validText(frame.body))
            field(output, frame.body, MAX_TEXT)
        }
        output.flush()
    }

    fun read(input: DataInputStream): Frame {
        val type = input.readUnsignedByte()
        if (type != MESSAGE && type != ACK) throw ProtocolException("Unknown frame")
        val id = UUID(input.readLong(), input.readLong())
        if (type == ACK) return Frame.Receipt(id)
        val body = data(input, MAX_TEXT)
        if (!validText(body)) throw ProtocolException("Empty message")
        return Frame.Text(id, body)
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    fun keyId(cert: X509Certificate): String = digest(cert.publicKey.encoded)

    /** 128 bits from the identities actually proven by TLS. Both users compare all groups independently. */
    fun comparison(
        first: String,
        second: String,
    ): String {
        require(first != second)
        return digest(
            ("VoIP.ms Nearby v1|" + listOf(first, second).sorted().joinToString("|"))
                .toByteArray(Charsets.US_ASCII),
        ).take(32).uppercase(java.util.Locale.ROOT).chunked(4).joinToString(" ")
    }

    /** Only a self-signed device identity is accepted; user comparison/pinning authenticates the first pairing. */
    object DeviceCertificates : X509TrustManager {
        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.singleOrNull() ?: throw CertificateException("Expected one device certificate")
            val publicKey = leaf.publicKey as? ECPublicKey ?: throw CertificateException("Expected EC identity")
            if (publicKey.params.curve.field.fieldSize != 256 ||
                leaf.issuerX500Principal != leaf.subjectX500Principal ||
                !leaf.sigAlgOID.equals("1.2.840.10045.4.3.2")
            ) {
                throw CertificateException("Invalid device identity")
            }
            leaf.checkValidity()
            try {
                leaf.verify(publicKey)
            } catch (
                e: java.security.GeneralSecurityException,
            ) {
                throw CertificateException("Invalid device signature", e)
            }
        }

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = check(chain)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = check(chain)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
