package net.kourlas.voipms_sms.privacy

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated chunks bound to sequence; a required, authenticated empty chunk marks the end. */
internal object BackupEnvelope {
    private val magic = "VSMSBAK1".toByteArray(Charsets.US_ASCII)
    private const val CHUNK = 64 * 1024
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 1_400_000, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, sequence: Int): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(sequence).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(magic)
        }
    }

    fun prepare(password: CharArray): Writer {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return Writer(salt, derive(password, salt))
    }

    /** One archive per derived key and salt. Key setup can finish before truncating a user's document. */
    class Writer internal constructor(private val salt: ByteArray, private val key: SecretKeySpec) {
        private var used = false

        @Synchronized
        fun write(source: InputStream, dest: OutputStream, checkActive: () -> Unit = {}) {
            check(!used)
            used = true
            checkActive()
            val prefix = ByteArray(8).also(SecureRandom()::nextBytes)
            val stream = DataOutputStream(dest)
            stream.write(magic)
            stream.write(salt)
            stream.write(prefix)
            val block = ByteArray(CHUNK)
            try {
                var sequence = 0
                var total = 0L
                while (true) {
                    checkActive()
                    val read = source.read(block)
                    if (read == 0) continue
                    val size = read.coerceAtLeast(0)
                    total += size
                    require(total <= MAX_BYTES)
                    val encrypted = cipher(Cipher.ENCRYPT_MODE, key, prefix, sequence++).doFinal(block, 0, size)
                    stream.writeInt(encrypted.size)
                    stream.write(encrypted)
                    if (read < 0) break
                }
                stream.flush()
            } finally { block.fill(0) }
        }
    }

    fun write(source: InputStream, dest: OutputStream, password: CharArray, checkActive: () -> Unit = {}) {
        checkActive()
        prepare(password).write(source, dest, checkActive)
    }

    fun read(source: InputStream, dest: OutputStream, password: CharArray, maxBytes: Long = MAX_BYTES,
             checkActive: () -> Unit = {}) {
        checkActive()
        val stream = DataInputStream(source)
        fun take(size: Int) = ByteArray(size).also(stream::readFully)
        require(take(magic.size).contentEquals(magic)) { "Unknown backup format" }
        val key = derive(password, take(16))
        val prefix = take(8)
        var sequence = 0
        var total = 0L
        while (true) {
            checkActive()
            val size = stream.readInt()
            require(size in 16..(CHUNK + 16))
            val plain = cipher(Cipher.DECRYPT_MODE, key, prefix, sequence++).doFinal(take(size))
            if (plain.isEmpty()) break
            total += plain.size
            try {
                require(total <= maxBytes)
                dest.write(plain)
            } finally { plain.fill(0) }
        }
        require(stream.read() == -1) { "Trailing backup data" }
    }
}
