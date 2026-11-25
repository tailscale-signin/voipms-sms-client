package net.kourlas.voipms_sms.nearby

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

internal object NearbyKeys {
    private const val STORE = "AndroidKeyStore"
    private const val HISTORY = "sms_nearby_history_v1"
    private val storage get() = KeyStore.getInstance(STORE).apply { load(null) }

    data class Identity(
        val context: SSLContext,
        val id: String,
    )

    @Synchronized fun identity(accountScope: String): Identity {
        // A new VoIP account generation must present a different identity to remote peers.
        val identityAlias = "sms_nearby_tls_v1_$accountScope"
        val store = storage
        if (!store.containsAlias(identityAlias)) {
            val now = System.currentTimeMillis()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply {
                initialize(
                    KeyGenParameterSpec
                        .Builder(identityAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setCertificateSubject(X500Principal("CN=Nearby device"))
                        .setCertificateSerialNumber(BigInteger(128, SecureRandom()).setBit(0))
                        .setCertificateNotBefore(Date(now - 86400000L))
                        .setCertificateNotAfter(Date(now + 30L * 365 * 86400000))
                        .build(),
                )
                generateKeyPair()
            }
        }
        val entry = store.getEntry(identityAlias, null) as KeyStore.PrivateKeyEntry
        val certificate = entry.certificate as X509Certificate
        val keys =
            object : X509KeyManager {
                override fun getPrivateKey(alias: String?): PrivateKey? = entry.privateKey.takeIf { alias == identityAlias }

                override fun getCertificateChain(alias: String?) = if (alias == identityAlias) arrayOf(certificate) else null

                override fun getClientAliases(
                    type: String?,
                    issuers: Array<out Principal>?,
                ) = aliases(type)

                override fun getServerAliases(
                    type: String?,
                    issuers: Array<out Principal>?,
                ) = aliases(type)

                override fun chooseServerAlias(
                    type: String?,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ) = aliases(type)?.first()

                override fun chooseClientAlias(
                    types: Array<out String>?,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ) = types?.firstNotNullOfOrNull { aliases(it)?.first() }

                private fun aliases(type: String?) = if (type.equals("EC", true)) arrayOf(identityAlias) else null
            }
        val tls =
            SSLContext.getInstance("TLS").apply {
                init(arrayOf<KeyManager>(keys), arrayOf<TrustManager>(NearbyWire.DeviceCertificates), SecureRandom())
            }
        return Identity(tls, NearbyWire.keyId(certificate))
    }

    private val historyKey: SecretKey by lazy {
        val store = storage
        (store.getKey(HISTORY, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run {
            init(
                KeyGenParameterSpec
                    .Builder(HISTORY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    fun seal(
        text: String,
        identity: String,
    ): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, historyKey)
            updateAAD(identity.toByteArray(Charsets.UTF_8))
            iv + doFinal(text.toByteArray(Charsets.UTF_8))
        }

    fun unseal(
        ciphertext: ByteArray,
        identity: String,
    ): String =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, historyKey, GCMParameterSpec(128, ciphertext, 0, 12))
            updateAAD(identity.toByteArray(Charsets.UTF_8))
            doFinal(ciphertext, 12, ciphertext.size - 12).toString(Charsets.UTF_8)
        }
}
