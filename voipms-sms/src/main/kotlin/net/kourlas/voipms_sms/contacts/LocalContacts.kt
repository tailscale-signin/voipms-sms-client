package net.kourlas.voipms_sms.contacts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only app UI and the explicit passphrase backup consume these aliases. */
internal object LocalContacts {
    const val MAX_CONTACTS = 20_000
    const val MAX_NAME = 120
    private const val KEY = "voipms_sms.local_contacts.v1"
    private const val FILE = "local_contacts.v1"
    private var instance: ContactFile? = null

    private val phonePattern = Regex("""\+?[0-9 ().-]+""")

    fun number(raw: String): String? {
        if (raw.isBlank() || !phonePattern.matches(raw)) return null
        val digits = normalizeVoipMsDid(raw.filter { it in '0'..'9' })
        return digits.takeIf { it.length in 1..20 }
    }

    fun validateEntry(number: String, name: String): Boolean =
        this.number(number) == number && name.isNotBlank() && name.length <= MAX_NAME &&
            name == name.trim() && name.none { it.isISOControl() || it in '\u202a'..'\u202e' ||
                it in '\u2066'..'\u2069' || it == '\u2028' || it == '\u2029' }

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(KEY, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Contact key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    @Synchronized private fun store(context: Context): ContactFile {
        val file = File(context.applicationContext.noBackupFilesDir, FILE)
        return instance?.takeIf { it.file == file } ?: ContactFile(file, ::key).also { instance = it }
    }

    fun snapshot(context: Context): Map<String, String> = store(context).snapshot()
    fun find(context: Context, raw: String): String? {
        val normalized = number(raw) ?: return null
        return try { store(context).find(normalized) } catch (_: Exception) { null }
    }
    fun save(context: Context, raw: String, name: String) =
        store(context).save(requireNotNull(number(raw)), name.trim())
    fun mergeMissing(context: Context, contacts: Map<String, String>) = store(context).mergeMissing(contacts)
}

/** A keystore-protected address book. AtomicFile only ever receives authenticated ciphertext. */
internal class ContactFile(val file: File, private val key: (create: Boolean) -> SecretKey) {
    private val atomic = AtomicFile(file)
    private var cache: Map<String, String>? = null
    private val header = "VSMS contacts 1".toByteArray(Charsets.US_ASCII)

    private fun load(): Map<String, String> {
        cache?.let { return it }
        val encoded = try {
            atomic.openRead().use { input ->
                require(input.channel.size() in 29L..(16L * 1024 * 1024))
                input.readBytes()
            }
        } catch (e: FileNotFoundException) {
            if (file.exists()) throw e
            return emptyMap<String, String>().also { cache = it }
        }
        val iv = encoded.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
            updateAAD(header)
        }
        val plain = cipher.doFinal(encoded, 12, encoded.size - 12)
        try {
            val records = DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val count = input.readInt()
                require(count in 0..LocalContacts.MAX_CONTACTS)
                buildMap {
                    repeat(count) {
                        val number = input.readUTF()
                        val name = input.readUTF()
                        require(LocalContacts.validateEntry(number, name) && put(number, name) == null)
                    }
                    require(input.read() == -1)
                }
            }
            cache = records
            return records
        } finally { plain.fill(0) }
    }

    @Synchronized fun snapshot(): Map<String, String> = load().toMap()
    @Synchronized fun find(number: String): String? = load()[number]

    @Synchronized fun save(number: String, name: String) {
        require(LocalContacts.validateEntry(number, name))
        val existing = load()
        if (existing[number] != name) write(existing + (number to name))
    }

    @Synchronized fun mergeMissing(contacts: Map<String, String>): Int {
        require(contacts.size <= LocalContacts.MAX_CONTACTS && contacts.all { LocalContacts.validateEntry(it.key, it.value) })
        val existing = load()
        val additions = contacts.filterKeys { it !in existing }
        if (additions.isNotEmpty()) write(existing + additions)
        return additions.size
    }

    private fun write(records: Map<String, String>) {
        require(records.size <= LocalContacts.MAX_CONTACTS)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { data ->
            data.writeInt(records.size)
            for ((number, name) in records) { data.writeUTF(number); data.writeUTF(name) }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(true))
            updateAAD(header)
        }
        val cleartext = buffer.toByteArray()
        val ciphertext = try { cipher.doFinal(cleartext) } finally { cleartext.fill(0) }
        check(cipher.iv.size == 12)
        val stream = atomic.startWrite()
        try {
            stream.write(cipher.iv)
            stream.write(ciphertext)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        cache = records
    }
}
