package net.kourlas.voipms_sms.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.preferences.withSecurePreferences
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.utils.HttpClientManager
import net.kourlas.voipms_sms.utils.awaitDecoded
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal fun originOrNull(input: String): String? {
    val url = input.trim().toHttpUrlOrNull() ?: return null
    return url.takeIf {
        it.isHttps && it.encodedPath == "/" && it.encodedUsername.isEmpty() &&
            it.encodedPassword.isEmpty() && it.encodedQuery == null && it.encodedFragment == null
    }?.toString()?.removeSuffix("/")
}

private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    .joinToString("") { "%02x".format(Locale.ROOT, it) }

internal class CompanionFailure(val code: Int) : IOException("Companion HTTP $code")
internal data class CompanionResult(val json: JSONObject, val session: String?)

/** A failed disk write must keep pending privacy changes eligible for retry. KTX edit returns Unit. */
internal fun SharedPreferences.commitCompanionChanges(update: SharedPreferences.Editor.() -> Unit): Boolean {
    val editor = edit()
    editor.update()
    return editor.commit()
}

internal class NativeCompanion(val origin: String, val generation: Long, email: String) {
    val accountTag = digest(email.trim().lowercase(Locale.ROOT))
    val key = "companion.$generation." + digest(origin)

    init { require(originOrNull(origin) == origin) }

    fun session(context: Context) = withSecurePreferences(context) { it.getString(key, null) }

    fun remember(context: Context, session: String): Boolean = withSecurePreferences(context) { prefs ->
        val index = "companion.origins.$generation"
        val known = prefs.getStringSet(index, emptySet()).orEmpty() + origin
        prefs.commitCompanionChanges {
            putString(key, session)
            putStringSet(index, known)
        }
    } == true

    suspend fun api(route: String, token: String? = null, data: JSONObject? = null): CompanionResult {
        val builder = Request.Builder().url("$origin/api/$route")
            .header("Origin", origin).header("Cache-Control", "no-store")
        if (token != null) builder.header("Cookie", "__Host-sms_session=$token")
        if (data != null) builder.post(data.toString().toRequestBody("application/json".toMediaType()))
        return client.newCall(builder.build()).awaitDecoded { response ->
            if (!response.isSuccessful) throw CompanionFailure(response.code)
            val session = response.headers("Set-Cookie").asSequence().map { it.substringBefore(';') }
                .firstOrNull { cookieRegex.matches(it) }?.substringAfter('=')
            val source = response.body.source()
            if (source.request(65_537)) throw CompanionFailure(502)
            CompanionResult(JSONObject(source.readUtf8()), session)
        }
    }

    suspend fun grants(token: String): Map<String, String> {
        val info = api("me", token).json
        if (info.optString("accountTag") != accountTag) throw CompanionFailure(412)
        val lines = info.getJSONArray("lines")
        return buildMap {
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                put(line.getString("did"), line.getString("role"))
            }
        }
    }

    suspend fun verify(token: String, did: String) = grants(token)[did] ?: throw CompanionFailure(403)

    fun fields(id: ConversationId) = JSONObject().put("did", id.did).put("peer", id.contact)
        .put("accountTag", accountTag)

    companion object {
        private val cookieRegex = Regex("__Host-sms_session=[A-Za-z0-9_-]{32}")
        private val client by lazy {
            HttpClientManager.getInstance().client.newBuilder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .callTimeout(12, TimeUnit.SECONDS).build()
        }

        fun enqueue(context: Context, generation: Long, origin: String) {
            val request = OneTimeWorkRequestBuilder<CompanionPrivacyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("generation" to generation, "origin" to origin)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "companion-privacy-$generation-" + digest(origin), ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }
    }
}

class CompanionPrivacyWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val generation = inputData.getLong("generation", -1)
        val origin = originOrNull(inputData.getString("origin").orEmpty()) ?: return Result.failure()
        return try {
            withCurrentAccount(applicationContext, generation) { account ->
                val host = NativeCompanion(origin, generation, account.email)
                val token = host.session(applicationContext) ?: return@withCurrentAccount Result.success()
                val pendingKey = host.key + ".pending"
                val entries = withSecurePreferences(applicationContext) { it.getStringSet(pendingKey, emptySet()).orEmpty().toSet() }
                    ?: return@withCurrentAccount Result.retry()
                if (entries.isEmpty()) return@withCurrentAccount Result.success()
                val permitted = try { host.grants(token) } catch (failure: CompanionFailure) {
                    if (failure.code in listOf(401, 403, 412)) return@withCurrentAccount Result.success()
                    throw failure
                }
                for (entry in entries) {
                    val parts = entry.split('|')
                    if (parts.size != 2 || parts[0] !in permitted) continue
                    val id = ConversationId(parts[0], parts[1])
                    try {
                        host.api("private", token, host.fields(id))
                    } catch (failure: CompanionFailure) {
                        // A new session may regain a grant. Pairing resumes delivery of retained exclusions.
                        if (failure.code == 401 || failure.code == 412) return@withCurrentAccount Result.success()
                        if (failure.code == 403 || failure.code == 404) continue
                        throw failure
                    }
                    val saved = withSecurePreferences(applicationContext) { prefs ->
                        val remaining = prefs.getStringSet(pendingKey, emptySet()).orEmpty() - entry
                        prefs.commitCompanionChanges { putStringSet(pendingKey, remaining) }
                    }
                    if (saved != true) return@withCurrentAccount Result.retry()
                }
                Result.success()
            } ?: Result.success()
        } catch (_: IOException) { Result.retry() }
    }
}
