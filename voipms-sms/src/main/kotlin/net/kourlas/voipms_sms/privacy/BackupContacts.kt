package net.kourlas.voipms_sms.privacy

import net.kourlas.voipms_sms.contacts.LocalContacts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Contacts stay encrypted even inside the temporary ZIP used to select conversations. */
internal object BackupContacts {
    const val ENTRY = "contacts.v1.enc"
    private const val NUMBER_BYTES = 20
    private const val NAME_BYTES = LocalContacts.MAX_NAME * 4
    private const val MAX_PLAIN_BYTES = 4 + LocalContacts.MAX_CONTACTS * (4 + NUMBER_BYTES + NAME_BYTES)
    // At most one GCM tag and length per 64 KiB chunk, plus the envelope header and end marker.
    const val MAX_SEALED_BYTES = MAX_PLAIN_BYTES + (MAX_PLAIN_BYTES / (64 * 1024) + 3) * 20 + 32

    private class MemoryBuffer : ByteArrayOutputStream() {
        fun open() = ByteArrayInputStream(buf, 0, count)
        override fun close() {
            buf.fill(0)
            reset()
        }
    }

    fun write(output: OutputStream, contacts: Map<String, String>, password: CharArray, checkActive: () -> Unit) {
        require(contacts.size <= LocalContacts.MAX_CONTACTS)
        MemoryBuffer().use { buffer ->
            val data = DataOutputStream(buffer)
            data.writeInt(contacts.size)
            for ((number, name) in contacts) {
                checkActive()
                require(LocalContacts.validateEntry(number, name))
                writeField(data, number, NUMBER_BYTES)
                writeField(data, name, NAME_BYTES)
            }
            buffer.open().use { BackupEnvelope.write(it, output, password, checkActive) }
        }
    }

    fun read(input: InputStream, password: CharArray): Map<String, String> = MemoryBuffer().use { buffer ->
        BackupEnvelope.read(input, buffer, password, MAX_PLAIN_BYTES.toLong())
        DataInputStream(buffer.open()).use { data ->
            val count = data.readInt()
            require(count in 0..LocalContacts.MAX_CONTACTS)
            buildMap {
                repeat(count) {
                    val number = readField(data, NUMBER_BYTES)
                    val name = readField(data, NAME_BYTES)
                    require(LocalContacts.validateEntry(number, name) && put(number, name) == null)
                }
                require(data.read() == -1)
            }
        }
    }

    private fun writeField(data: DataOutputStream, value: String, max: Int) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        try {
            require(encoded.size in 1..max && String(encoded, Charsets.UTF_8) == value)
            data.writeShort(encoded.size)
            data.write(encoded)
        } finally { encoded.fill(0) }
    }

    private fun readField(data: DataInputStream, max: Int): String {
        val length = data.readUnsignedShort()
        require(length in 1..max)
        val bytes = ByteArray(length)
        return try {
            data.readFully(bytes)
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } finally { bytes.fill(0) }
    }
}
