package net.kourlas.voipms_sms.preferences

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CredentialMigrationTest {
    private lateinit var plain: SharedPreferences
    private lateinit var legacy: SharedPreferences
    private lateinit var destination: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        plain = context.getSharedPreferences("plain", Context.MODE_PRIVATE)
        legacy = context.getSharedPreferences("legacy", Context.MODE_PRIVATE)
        destination = context.getSharedPreferences("destination", Context.MODE_PRIVATE)
    }

    @Test
    fun unavailableKeyPreservesRemainingSourceAndNextAttemptCompletes() {
        plain.edit().putString("email", "test@example.invalid").commit()
        legacy.edit().putString("password", "ciphertext").putString("unrelated", "keep").commit()
        val keys = listOf("email", "password")
        assertThrows(IOException::class.java) {
            migrateCredentialKeys(keys, plain, legacy, destination) { throw IOException("key unavailable") }
        }
        assertFalse(plain.contains("email"))
        assertEquals("test@example.invalid", destination.getString("email", null))
        assertEquals("ciphertext", legacy.getString("password", null))
        assertFalse(destination.contains("password"))

        migrateCredentialKeys(keys, plain, legacy, destination) { "decrypted" }
        assertFalse(legacy.contains("password"))
        assertEquals("decrypted", destination.getString("password", null))
        assertEquals("keep", legacy.getString("unrelated", null))
    }

    @Test
    fun failedDestinationCommitLeavesPlaintextAvailableForRetry() {
        plain.edit().putString("email", "test@example.invalid").commit()
        val failing = object : SharedPreferences by destination {
            override fun edit(): SharedPreferences.Editor {
                val delegate = destination.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        assertThrows(IOException::class.java) {
            migrateCredentialKeys(listOf("email"), plain, legacy, failing) { error("unused") }
        }
        assertEquals("test@example.invalid", plain.getString("email", null))
        assertFalse(destination.contains("email"))
    }

    @Test
    fun verifiedSignOutIsNeverReplacedByStaleLegacyValues() {
        destination.edit().putString("password", "").commit()
        legacy.edit().putString("password", "old-encrypted").commit()
        migrateCredentialKeys(listOf("password"), plain, legacy, destination) { error("old key not needed") }
        assertFalse(legacy.contains("password"))
        assertEquals("", destination.getString("password", null))
    }
}
