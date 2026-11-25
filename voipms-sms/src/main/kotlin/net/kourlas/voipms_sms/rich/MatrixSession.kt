package net.kourlas.voipms_sms.rich

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kourlas.voipms_sms.preferences.withSecurePreferences
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.UUID

/** A separate Matrix device for each login. The SDK owns the encrypted crypto/state/event stores. */
internal class MatrixSession(val client: Client, val generation: String) {
    val updates = MutableStateFlow(0L)
    private val poll = CoroutineScope(Dispatchers.IO).launch {
        var retryMs = 1000L
        var reconnecting = true
        while (isActive) {
            try {
                // The SDK persists and reuses its /sync token, processes to-device keys and advances it atomically.
                client.syncOnceV2(SyncSettingsV2(timeoutMs = if (reconnecting) 0uL else 30_000uL))
                if (reconnecting) client.enableAllSendQueues(true)
                updates.value += 1
                reconnecting = false
                retryMs = 1000L
                delay(150)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reconnecting = true
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    suspend fun stop() { poll.cancelAndJoin() }
}

internal object MatrixSessions {
    private const val CONFIG = "matrix_device_session_v1"
    private val guard = Mutex()
    @Volatile private var current: MatrixSession? = null

    private class Config(val json: JSONObject) {
        val id: String get() = json.getString("generation")
        val url: String get() = json.getString("url")
        val user: String get() = json.getJSONObject("session").getString("user")
        val key: ByteArray get() = Base64.decode(json.getString("key"), Base64.NO_WRAP)
    }

    // The SDK must know if the device/token change reached disk. The KTX edit helper discards commit's result.
    @SuppressLint("UseKtx")
    private fun persist(prefs: SharedPreferences, config: JSONObject?): Boolean =
        prefs.edit().putString(CONFIG, config?.toString()).commit()

    private fun read(c: Context): Config? = withSecurePreferences(c) { pref ->
        pref.getString(CONFIG, null)?.let { Config(JSONObject(it)) }
    }
    fun account(c: Context): String? = read(c)?.user
    fun identity(c: Context): String? = read(c)?.let { it.url + "|" + it.user }
    fun active(session: MatrixSession) = current === session

    private fun encode(s: Session) = JSONObject().put("access", s.accessToken)
        .put("refresh", s.refreshToken).put("user", s.userId).put("device", s.deviceId)
        .put("url", s.homeserverUrl).put("oauth", s.oauthData).put("sliding", s.slidingSyncVersion.name)
    private fun decode(j: JSONObject) = Session(j.getString("access"), j.optString("refresh").takeIf { it.isNotEmpty() },
        j.getString("user"), j.getString("device"), j.getString("url"), j.optString("oauth").takeIf { it.isNotEmpty() },
        SlidingSyncVersion.valueOf(j.getString("sliding")))

    private fun path(c: Context, id: String) = File(c.noBackupFilesDir, "matrix/" + UUID.fromString(id))
    private suspend fun build(c: Context, cfg: Config): Client {
        MatrixPlatform.initialize(c)
        val data = path(c, cfg.id)
        val cache = File(c.cacheDir, "matrix/" + cfg.id)
        require(data.isDirectory || data.mkdirs())
        require(cache.isDirectory || cache.mkdirs())
        val delegate = object : ClientSessionDelegate {
            override fun retrieveSessionFromKeychain(userId: String): Session {
                val active = read(c)
                check(active?.id == cfg.id && active.user == userId)
                return decode(active.json.getJSONObject("session"))
            }
            override fun saveSessionInKeychain(session: Session) {
                check(withSecurePreferences(c) { pref ->
                    val saved = pref.getString(CONFIG, null)?.let(::JSONObject)
                    if (saved?.optString("generation") == cfg.id) {
                        persist(pref, saved.put("session", encode(session)))
                    } else true
                } == true)
            }
        }
        return ClientBuilder().homeserverUrl(cfg.url).disableWellKnownLookup(true)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NONE)
            .sqliteStore(SqliteStoreBuilder(data.path, cache.path).key(cfg.key))
            .setSessionDelegate(delegate).build()
    }

    suspend fun open(context: Context): MatrixSession = guard.withLock {
        current?.let { return@withLock it }
        val c = context.applicationContext
        val cfg = requireNotNull(read(c)) { "Sign in to Matrix first." }
        val client = build(c, cfg)
        try {
            client.restoreSession(decode(cfg.json.getJSONObject("session")))
            MatrixSession(client, cfg.id).also { current = it }
        } catch (e: Throwable) { client.close(); throw e }
    }

    suspend fun login(context: Context, raw: String, user: String, password: String) = guard.withLock {
        val c = context.applicationContext
        check(current == null && read(c) == null) { "Sign out before changing Matrix accounts." }
        val url = URI(raw.trim())
        require(url.scheme.equals("https", true) && !url.host.isNullOrBlank() && url.userInfo == null &&
            url.rawQuery == null && url.rawFragment == null) { "Enter the HTTPS address of your homeserver." }
        require(user.isNotBlank() && password.isNotBlank())
        val cfg = Config(JSONObject().put("generation", UUID.randomUUID().toString())
            .put("url", url.toASCIIString().trimEnd('/')).put("key", Base64.encodeToString(
                ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)))
        val client = build(c, cfg)
        var persisted = false
        try {
            client.login(user.trim(), password, "VoIP.ms SMS", null)
            cfg.json.put("session", encode(client.session()))
            check(withSecurePreferences(c) { persist(it, cfg.json) } == true)
            persisted = true
            current = MatrixSession(client, cfg.id)
        } finally {
            if (current == null) {
                client.close()
                if (!persisted) { path(c, cfg.id).deleteRecursively(); File(c.cacheDir, "matrix/" + cfg.id).deleteRecursively() }
            }
        }
    }

    suspend fun logout(context: Context) = guard.withLock {
        val c = context.applicationContext
        val cfg = read(c) ?: return@withLock
        val client = current?.client ?: build(c, cfg).also { it.restoreSession(decode(cfg.json.getJSONObject("session"))) }
        // If the homeserver is unavailable retain the profile so the device can be revoked on retry.
        try { client.logout() } catch (e: Throwable) { if (current == null) client.close(); throw e }
        check(withSecurePreferences(c) { persist(it, null) } == true)
        current?.stop()
        current?.updates?.value = -1L
        current = null
        client.close()
        path(c, cfg.id).deleteRecursively()
        File(c.cacheDir, "matrix/" + cfg.id).deleteRecursively()
        File(c.cacheDir, "matrix_shared/" + cfg.id).deleteRecursively()
    }
}
