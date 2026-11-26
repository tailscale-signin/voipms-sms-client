/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2022 Michael Kourlas
 *
 * Portions copyright (C) 2017 adorsys GmbH & Co. KG (taken from
 * SecurePreferences.java and KeystoreTool.java)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.notifications.PushRegistration
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.privacy.CodePrivacy
import net.kourlas.voipms_sms.sms.workers.SyncWorker
import net.kourlas.voipms_sms.utils.subscribeToDidTopics
import net.kourlas.voipms_sms.utils.normalizeVoipMsDid
import java.io.IOException
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher

private const val TAG = "Preferences"

private val securePreferencesLock = Any()
private var encryptedPreferences: SharedPreferences? = null
private var legacyPreferencesMigrated = false

private const val ACCOUNT_GENERATION = "account_session_generation"
private val accountChanges = Mutex()

internal class AccountCredentials(val email: String, val password: String, val generation: Long) {
    val configured get() = email.isNotEmpty() && password.isNotEmpty()
}

/** Null means secure storage could not be read. Empty fields represent a signed-out account. */
internal fun readAccountCredentials(context: Context): AccountCredentials? =
    withSecurePreferences(context) { encrypted ->
        if (!legacyPreferencesMigrated) {
            migrateLegacySecurePreferences(context.applicationContext, encrypted)
            legacyPreferencesMigrated = true
        }
        val generation = encrypted.getLong(ACCOUNT_GENERATION, 0)
        reconcileAccountSelection(context, generation)
        AccountCredentials(
            encrypted.getString(context.getString(R.string.preferences_account_email_key), null).orEmpty(),
            encrypted.getString(context.getString(R.string.preferences_account_password_key), null).orEmpty(),
            generation
        )
    }

fun firstRun(context: Context): Boolean = getBooleanPreference(
    context,
    context.getString(
        R.string.preferences_first_run
    ),
    true
)

fun accountConfigured(context: Context): Boolean =
    readAccountCredentials(context)?.configured == true

/** Coalesce legacy line keys before any worker, FCM registration, or screen reads them. */
internal fun migrateDidPreferences(context: Context) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val setKey = context.getString(R.string.preferences_dids_key)
    val singleKey = context.getString(R.string.preferences_dids_did_key)
    val activeKey = context.getString(R.string.preferences_dids_active_did_key)
    val entries = prefs.all
    prefs.edit {
        for (key in listOf(singleKey, activeKey)) {
            val old = entries[key] as? String ?: continue
            val did = normalizeVoipMsDid(old)
            if (old != did) putString(key, did)
        }
        val oldDids = prefs.getStringSet(setKey, null)
        if (oldDids != null) {
            val dids = oldDids.mapTo(mutableSetOf(), ::normalizeVoipMsDid)
            if (dids != oldDids) putStringSet(setKey, dids)
        }
        val flags = Regex("""preferences_did_(\+?[0-9]+)_(show_in_conversations_view|retrieve_messages|show_notifications)""")
        val migrated = mutableMapOf<String, Boolean>()
        for ((key, value) in entries) {
            if (value !is Boolean) continue
            val match = flags.matchEntire(key) ?: continue
            val (raw, flag) = match.destructured
            val canonical = "preferences_did_${normalizeVoipMsDid(raw)}_${flag}"
            if (canonical == key) continue
            // If two aliases disagree, preserve the explicit opt-out.
            migrated[canonical] = value && migrated.getOrDefault(canonical, entries[canonical] != false)
            remove(key)
        }
        for ((key, value) in migrated) putBoolean(key, value)
    }
}

fun didsConfigured(context: Context): Boolean = getDids(context).isNotEmpty()

fun getActiveDid(context: Context): String =
    getStringPreference(
        context,
        context.getString(
            R.string.preferences_dids_active_did_key
        ),
        ""
    )

fun getAppTheme(context: Context): String =
    getStringPreference(
        context,
        context.getString(R.string.preferences_theme_key),
        context.getString(R.string.preferences_theme_default_value)
    )

fun getConnectTimeout(context: Context): Int =
    getStringPreference(
        context,
        context.getString(R.string.preferences_network_connect_timeout_key),
        context.getString(
            R.string.preferences_network_connect_timeout_default_value
        )
    )
        .toIntOrNull() ?: 15

fun getDids(
    context: Context,
    onlyShowInConversationsView: Boolean = false,
    onlyRetrieveMessages: Boolean = false,
    onlyShowNotifications: Boolean = false
): Set<String> {
    val did = getStringPreference(
        context, context.getString(
            R.string.preferences_dids_did_key
        ), ""
    )
    val default: Set<String> = if (did != "") {
        setOf(did)
    } else {
        emptySet()
    }
    var set = getStringSetPreference(
        context, context.getString(
            R.string.preferences_dids_key
        ), default
    )
    if (onlyShowInConversationsView) {
        set = set.filter { getDidShowInConversationsView(context, it) }.toSet()
    }
    if (onlyRetrieveMessages) {
        set = set.filter { getDidRetrieveMessages(context, it) }.toSet()
    }
    if (onlyShowNotifications) {
        set = set.filter { getDidShowNotifications(context, it) }.toSet()
    }
    return set
}

fun getEmail(context: Context): String = readAccountCredentials(context)?.email.orEmpty()

fun getFirstSyncAfterSignIn(context: Context): Boolean =
    getBooleanPreference(
        context,
        context.getString(R.string.preferences_first_sync_after_sign_in_key),
        false
    )

fun getMessageTextBoxMaximumSize(context: Context): Int =
    getStringPreference(
        context,
        context.getString(
            R.string.preferences_message_text_box_maximum_size_key
        ),
        context.getString(
            R.string.preferences_message_text_box_maximum_size_default_value
        )
    )
        .toIntOrNull() ?: 0

@Deprecated(
    "Remove when Android versions earlier than Oreo are no longer supported."
)
fun getNotificationsEnabled(context: Context): Boolean {
    val value: String = context.getString(
        R.string.preferences_notifications_enable_default_value
    )
    return getBooleanPreference(
        context,
        context.getString(R.string.preferences_notifications_enable_key),
        value.toBoolean()
    )
}

@Deprecated(
    "Remove when Android versions earlier than Oreo are no longer supported."
)
fun getNotificationVibrateEnabled(context: Context): Boolean {
    val value: String = context.getString(
        R.string.preferences_notifications_vibrate_default_value
    )
    return getBooleanPreference(
        context,
        context.getString(
            R.string.preferences_notifications_vibrate_key
        ),
        value.toBoolean()
    )
}

@Deprecated(
    "Remove when Android versions earlier than Oreo are no longer supported."
)
fun getNotificationSound(context: Context): String =
    getStringPreference(
        context,
        context.getString(
            R.string.preferences_notifications_sound_key
        ),
        context.getString(
            R.string.preferences_notifications_sound_default_value
        )
    )

fun getPassword(context: Context): String = readAccountCredentials(context)?.password.orEmpty()

fun getReadTimeout(context: Context): Int =
    getStringPreference(
        context,
        context.getString(R.string.preferences_network_read_timeout_key),
        context.getString(
            R.string.preferences_network_read_timeout_default_value
        )
    )
        .toIntOrNull() ?: 15

fun getRetrieveDeletedMessages(context: Context): Boolean {
    val value: String = context.getString(
        R.string.preferences_sync_retrieve_deleted_messages_default_value
    )
    return getBooleanPreference(
        context,
        context.getString(
            R.string.preferences_sync_retrieve_deleted_messages_key
        ),
        value.toBoolean()
    )
}

fun getRetrieveOnlyRecentMessages(context: Context): Boolean {
    val value: String = context.getString(
        R.string.preferences_sync_retrieve_only_recent_messages_default_value
    )
    return getBooleanPreference(
        context,
        context.getString(
            R.string.preferences_sync_retrieve_only_recent_messages_key
        ),
        value.toBoolean()
    )
}

fun getSetupCompletedForVersion(context: Context): Long =
    getLongPreference(
        context,
        context.getString(R.string.preferences_setup_completed_for_version_key),
        context.getString(
            R.string.preferences_setup_completed_for_version_default_value
        )
            .toLong()
    )

fun getStartDate(context: Context): Date {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, -30)

    try {
        return try {
            val dateString = getStringPreference(
                context, context.getString(
                    R.string.preferences_sync_start_date_key
                ), ""
            )
            if (dateString != "") {
                val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                sdf.parse(dateString) ?: throw Exception(
                    "Failed to parse date $dateString"
                )
            } else {
                setStartDate(context, calendar.time)
                calendar.time
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read synchronization start date", e)
            setStartDate(context, calendar.time)
            calendar.time
        }
    } catch (_: ClassCastException) {
        val milliseconds = getLongPreference(
            context, context.getString(
                R.string.preferences_sync_start_date_key
            ), Long.MIN_VALUE
        )
        return if (milliseconds != java.lang.Long.MIN_VALUE) {
            val date = Date(milliseconds)
            setStartDate(context, date)
            date
        } else {
            setStartDate(context, calendar.time)
            calendar.time
        }
    }
}

fun getSyncInterval(context: Context): Double {
    val default = context.getString(R.string.preferences_sync_interval_default_value)
    return getStringPreference(context, context.getString(R.string.preferences_sync_interval_key), default)
        .toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: default.toDouble()
}

fun setFirstRun(context: Context, firstRun: Boolean) {
    setBooleanPreference(
        context,
        context.getString(R.string.preferences_first_run),
        firstRun
    )
}

fun setActiveDid(context: Context, did: String) {
    setStringPreference(
        context, context.getString(
            R.string.preferences_dids_active_did_key
        ), did
    )
}

fun setDids(context: Context, selected: Set<String>) {
    val dids = selected.mapTo(mutableSetOf(), ::normalizeVoipMsDid)
    val currentDids = getDids(context)
    val newDids = dids.filter { it !in currentDids }

    setStringSetPreference(
        context, context.getString(
            R.string.preferences_dids_key
        ), dids
    )
    for (did in newDids) {
        CodePrivacy.watchLine(context, currentDirectoryAccountGeneration(context), did, System.currentTimeMillis())
        setDidShowInConversationsView(context, did, true)
        setDidRetrieveMessages(context, did, true)
        setDidShowNotifications(context, did, true)
    }

    subscribeToDidTopics(context)
    SyncWorker.scheduleBackgroundSync(context)
}

fun setDidRetrieveMessages(context: Context, did: String, value: Boolean) {
    setBooleanPreference(
        context,
        context.getString(
            R.string.preferences_did_retrieve_messages_key,
            did
        ),
        value
    )
}

fun getDidRetrieveMessages(context: Context, did: String): Boolean {
    if (did !in getDids(context)) {
        return false
    }

    return getBooleanPreference(
        context,
        context.getString(
            R.string.preferences_did_retrieve_messages_key, did
        ),
        true
    )
}

fun setDidShowInConversationsView(
    context: Context, did: String,
    value: Boolean
) {
    setBooleanPreference(
        context,
        context.getString(
            R.string.preferences_did_show_in_conversations_view_key,
            did
        ),
        value
    )
}

fun getDidShowInConversationsView(context: Context, did: String): Boolean {
    if (did !in getDids(context)) {
        return false
    }

    return getBooleanPreference(
        context,
        context.getString(
            R.string.preferences_did_show_in_conversations_view_key,
            did
        ),
        true
    )
}

fun setDidShowNotifications(context: Context, did: String, value: Boolean) {
    setBooleanPreference(
        context, context.getString(
            R.string.preferences_did_show_notifications_key, did
        ), value
    )
}

fun getDidShowNotifications(context: Context, did: String): Boolean {
    if (did !in getDids(context)) {
        return false
    }

    return getBooleanPreference(
        context,
        context.getString(
            R.string.preferences_did_show_notifications_key, did
        ),
        true
    )
}


@Deprecated(
    "Remove when Android versions earlier than Oreo are no longer supported."
)
fun setNotificationSound(context: Context, uri: String) {
    setStringPreference(
        context,
        context.getString(R.string.preferences_notifications_sound_key),
        uri
    )
}

fun setFirstSyncAfterSignIn(context: Context, firstSyncAfterSignIn: Boolean) {
    setBooleanPreference(
        context,
        context.getString(R.string.preferences_first_sync_after_sign_in_key),
        firstSyncAfterSignIn
    )
}

fun setSetupCompletedForVersion(context: Context, version: Long) {
    if (getLongPreference(
            context,
            context.getString(
                R.string.preferences_setup_completed_for_version_key
            ),
            0
        ) < version
    ) {
        setLongPreference(
            context,
            context.getString(
                R.string.preferences_setup_completed_for_version_key
            ),
            version
        )
    }
}

fun setStartDate(context: Context, date: Date) {
    setStringPreference(
        context,
        context.getString(R.string.preferences_sync_start_date_key),
        SimpleDateFormat("MM/dd/yyyy", Locale.US).format(date)
    )
}

fun setRawSyncInterval(context: Context, string: String) {
    setStringPreference(
        context, context.getString(
            R.string.preferences_sync_interval_key
        ), string
    )
}

/** An interrupted switch is reconciled before any new account work reads its DIDs. */
/** The account generation mirrored when account-specific preferences are reconciled. */
internal fun currentDirectoryAccountGeneration(context: Context): Long =
    PreferenceManager.getDefaultSharedPreferences(context).getLong(ACCOUNT_GENERATION, 0L)

internal fun reconcileAccountSelection(context: Context, generation: Long) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    if (prefs.getLong(ACCOUNT_GENERATION, 0L) == generation) return
    val saved = prefs.edit()
        .putLong(ACCOUNT_GENERATION, generation)
        .putStringSet(context.getString(R.string.preferences_dids_key), emptySet())
        .remove(context.getString(R.string.preferences_dids_did_key))
        .remove(context.getString(R.string.preferences_dids_active_did_key))
        .remove(PushRegistration.KEY)
        .commit()
    if (!saved) throw IOException("Could not reset the previous account's number selection")
}

/** Local history stays usable offline. A delayed write cannot cross an account switch. */
internal suspend fun withDirectoryAccountGeneration(context: Context, generation: Long, action: suspend () -> Unit) {
    accountChanges.withLock {
        if (currentDirectoryAccountGeneration(context) == generation) action()
    }
}

/** Keep credential changes mutually exclusive with applying a completed network response. */
internal suspend fun <T> withCurrentAccount(context: Context, generation: Long, action: suspend (AccountCredentials) -> T): T? =
    accountChanges.withLock {
        val current = readAccountCredentials(context) ?: throw IOException("Account storage unavailable")
        if (current.generation == generation && current.configured) action(current) else null
    }

suspend fun saveAccountCredentials(context: Context, email: String, password: String): Boolean =
    withContext(Dispatchers.IO) {
        accountChanges.withLock {
            val previous = readAccountCredentials(context) ?: return@withLock false
            val changed = !previous.email.equals(email, ignoreCase = true) ||
                previous.configured != (email.isNotEmpty() && password.isNotEmpty())
            val generation = if (changed) previous.generation + 1 else previous.generation
            val saved = withSecurePreferences(context) { encrypted ->
                val emailKey = context.getString(R.string.preferences_account_email_key)
                val passwordKey = context.getString(R.string.preferences_account_password_key)
                val committed = encrypted.edit().putString(emailKey, email).putString(passwordKey, password)
                    .putLong(ACCOUNT_GENERATION, generation).commit()
                if (!committed || encrypted.getString(emailKey, null) != email ||
                    encrypted.getString(passwordKey, null) != password) return@withSecurePreferences false
                reconcileAccountSelection(context, generation)
                true
            } == true
            if (saved) {
                if (changed) {
                    Notifications.getInstance(context.applicationContext).cancelMessageNotifications()
                    net.kourlas.voipms_sms.delivery.CallAlerts.cancel(context.applicationContext)
                }
                SyncWorker.scheduleBackgroundSync(context)
                subscribeToDidTopics(context)
            }
            saved
        }
    }

internal fun <T> withSecurePreferences(
    context: Context, block: (SharedPreferences) -> T
): T? = synchronized(securePreferencesLock) {
    try {
        val prefs = encryptedPreferences ?: openEncryptedPreferences(context.applicationContext)
            .also { encryptedPreferences = it }
        block(prefs)
    } catch (e: Exception) {
        // Retry opening on the next access. Never clear credential or keyset files on failure.
        encryptedPreferences = null
        legacyPreferencesMigrated = false
        Log.w(TAG, "Secure account storage is unavailable (${e.javaClass.simpleName})")
        null
    }
}

private fun getBooleanPreference(
    context: Context, key: String,
    default: Boolean
): Boolean {
    return PreferenceManager.getDefaultSharedPreferences(
        context.applicationContext
    ).getBoolean(key, default)
}

private fun getLongPreference(
    context: Context, key: String,
    default: Long
): Long {
    return PreferenceManager.getDefaultSharedPreferences(
        context.applicationContext
    ).getLong(key, default)
}

private fun getStringPreference(
    context: Context, key: String,
    default: String
): String {
    return PreferenceManager.getDefaultSharedPreferences(
        context.applicationContext
    ).getString(key, null) ?: default
}

private fun getStringSetPreference(
    context: Context, key: String,
    default: Set<String>
): Set<String> {
    return PreferenceManager.getDefaultSharedPreferences(
        context.applicationContext
    ).getStringSet(key, null) ?: default
}

private fun setBooleanPreference(
    context: Context, key: String,
    value: Boolean
) {
    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        .edit { putBoolean(key, value) }
}

private fun setStringPreference(
    context: Context, key: String,
    value: String
) {
    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        .edit { putString(key, value) }
}

private fun setLongPreference(context: Context, key: String, value: Long) {
    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        .edit { putLong(key, value) }
}

private fun setStringSetPreference(
    context: Context, key: String,
    value: Set<String>
) {
    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        .edit { putStringSet(key, value) }
}

private fun openEncryptedPreferences(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
        context, context.getString(R.string.preferences_encrypted_file_name, context.packageName),
        masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

fun removePreference(context: Context, key: String) {
    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        .edit { remove(key) }
}

private const val LEGACY_KEYSTORE_TYPE = "AndroidKeyStore"
private const val LEGACY_SECURE_SHARED_PREFERENCES_NAME = "SecurePreferences"
private const val LEGACY_KEY_CIPHER_MARSHMALLOW_PROVIDER =
    "AndroidKeyStoreBCWorkaround"
private const val LEGACY_KEY_TRANSFORMATION_ALGORITHM = "RSA/ECB/PKCS1Padding"
private const val LEGACY_KEY_ALIAS = "adorsysKeyPair"

private fun migrateLegacySecurePreferences(context: Context, encrypted: SharedPreferences) {
    val plain = PreferenceManager.getDefaultSharedPreferences(context)
    val legacy = context.getSharedPreferences(LEGACY_SECURE_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
    val keys = listOf(context.getString(R.string.preferences_account_email_key),
        context.getString(R.string.preferences_account_password_key))
    val hadLegacyValues = keys.any(legacy::contains)
    migrateCredentialKeys(keys, plain, legacy, encrypted) { value ->
        val keyStore = KeyStore.getInstance(LEGACY_KEYSTORE_TYPE).apply { load(null) }
        val key = keyStore.getKey(LEGACY_KEY_ALIAS, null) ?: throw IOException("Legacy key unavailable")
        val cipher = Cipher.getInstance(LEGACY_KEY_TRANSFORMATION_ALGORITHM, LEGACY_KEY_CIPHER_MARSHMALLOW_PROVIDER)
        cipher.init(Cipher.DECRYPT_MODE, key)
        cipher.doFinal(Base64.decode(value, Base64.DEFAULT)).toString(Charsets.UTF_8)
    }
    if (hadLegacyValues && legacy.all.isEmpty()) {
        try {
            KeyStore.getInstance(LEGACY_KEYSTORE_TYPE).apply {
                load(null)
                deleteEntry(LEGACY_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove the retired credential key")
        }
    }
}

/** Keep each source until its replacement is committed and can be read back. */
// The migration checks the Boolean result of each synchronous disk write.
@SuppressLint("UseKtx")
internal fun migrateCredentialKeys(
    keys: List<String>, plain: SharedPreferences, legacy: SharedPreferences,
    destination: SharedPreferences, decrypt: (String) -> String
) {
    for (key in keys) {
        if (!plain.contains(key) && !legacy.contains(key)) continue
        val value = destination.getString(key, null) ?: plain.getString(key, null)?.takeIf { it.isNotEmpty() }
            ?: legacy.getString(key, null)?.takeIf { it.isNotEmpty() }?.let(decrypt) ?: ""
        if (!destination.edit().putString(key, value).commit() || destination.getString(key, null) != value) {
            throw IOException("Credential migration could not be verified")
        }
        if (plain.contains(key) && !plain.edit().remove(key).commit()) throw IOException("Legacy cleanup failed")
        if (legacy.contains(key) && !legacy.edit().remove(key).commit()) throw IOException("Legacy cleanup failed")
    }
}
