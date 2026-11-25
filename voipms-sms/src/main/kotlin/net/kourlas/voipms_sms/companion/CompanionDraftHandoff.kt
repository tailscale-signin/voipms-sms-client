package net.kourlas.voipms_sms.companion

import android.content.Context
import android.os.Build
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.delivery.DeliveryTrace
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.preferences.withSecurePreferences
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import org.json.JSONObject

internal class CompanionDraftHandoff(
    private val activity: ConversationActivity,
    private val editor: EditText,
    private val id: ConversationId
) {
    private fun notify(text: Int) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun eligible(generation: Long): Boolean =
        !activity.isFinishing && !activity.isDestroyed && activity.conversationId == id &&
            !PrivateThreads.isPrivate(activity, id) &&
            readAccountCredentials(activity)?.let { it.configured && it.generation == generation } == true &&
            id.did in getDids(activity)

    private fun run(action: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: CompanionFailure) {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (failure.code == 401) { pairDialog(); return@launch }
                notify(when (failure.code) {
                    403 -> R.string.handoff_denied
                    404 -> R.string.handoff_unshared
                    409 -> R.string.handoff_changed
                    412 -> R.string.handoff_wrong_account
                    else -> R.string.handoff_unavailable
                })
            } catch (_: Exception) { notify(R.string.handoff_unavailable) }
        }
    }

    fun show() {
        val account = readAccountCredentials(activity) ?: return
        if (!eligible(account.generation)) { notify(R.string.handoff_private); return }
        val origin = originOrNull(DeliveryTrace.store(activity).getString("companion_origin", "").orEmpty())
        if (origin == null) { pairDialog(); return }
        val host = NativeCompanion(origin, account.generation, account.email)
        val token = host.session(activity)
        if (token == null) pairDialog() else review(host, token)
    }

    private fun pairDialog() {
        val account = readAccountCredentials(activity) ?: return
        if (!eligible(account.generation)) return
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val originField = EditText(activity).apply {
            setSingleLine(); setHint(R.string.handoff_origin)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(DeliveryTrace.store(activity).getString("companion_origin", ""))
        }
        val code = EditText(activity).apply {
            setSingleLine(); setHint(R.string.handoff_code)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        fields.addView(originField); fields.addView(code)
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.handoff_pair_title)
            .setMessage(R.string.handoff_pair_message).setView(fields)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.handoff_pair, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val origin = originOrNull(originField.text.toString())
                val pairing = code.text.toString().trim()
                if (origin == null || !Regex("[A-Za-z0-9_-]{32}").matches(pairing)) {
                    code.error = activity.getString(R.string.handoff_invalid_pair)
                    return@setOnClickListener
                }
                val host = NativeCompanion(origin, account.generation, account.email)
                dialog.dismiss()
                run {
                    if (!eligible(account.generation)) return@run
                    val result = host.api("pair", data = JSONObject().put("code", pairing)
                        .put("label", "Android app · " + Build.MODEL.take(48)))
                    val token = result.session ?: throw CompanionFailure(401)
                    val granted = host.grants(token)
                    if (id.did !in granted) throw CompanionFailure(403)
                    if (!eligible(account.generation)) return@run
                    withCurrentAccount(activity, host.generation) {
                        if (!host.remember(activity, token)) throw CompanionFailure(503)
                        DeliveryTrace.store(activity).edit().putString("companion_origin", origin).apply()
                        val selected = getDids(activity)
                        val known = PrivateThreads.ids(activity, visibleOnly = false).filter {
                            it.did in selected && it.did in granted
                        }
                        if (known.isNotEmpty()) {
                            val saved = withSecurePreferences(activity) { prefs ->
                                val key = host.key + ".pending"
                                val exclusions = prefs.getStringSet(key, emptySet()).orEmpty() +
                                    known.map { "${it.did}|${it.contact}" }
                                prefs.edit().putStringSet(key, exclusions).commit()
                            }
                            if (saved != true) throw CompanionFailure(503)
                        }
                    } ?: return@run
                    NativeCompanion.enqueue(activity.applicationContext, host.generation, origin)
                    review(host, token)
                }
            }
        }
        dialog.show()
    }

    private fun review(host: NativeCompanion, token: String) = run {
        if (!eligible(host.generation)) return@run
        val role = host.verify(token, id.did)
        if (!eligible(host.generation)) return@run
        val remote = host.api("native-draft", token, host.fields(id)).json
        if (!eligible(host.generation)) return@run
        val desktopText = remote.getString("body")
        val revision = remote.getLong("version")
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.handoff_title)
            .setMessage(activity.getString(R.string.handoff_state, host.origin, id.did, id.contact,
                desktopText.ifEmpty { activity.getString(R.string.handoff_empty) }))
            .setNegativeButton(android.R.string.cancel, null)
        if (desktopText.isNotEmpty()) dialog.setNeutralButton(R.string.handoff_load) { _, _ ->
            val before = editor.text.toString()
            confirm(before.isNotEmpty() && before != desktopText, R.string.handoff_replace_phone) {
                if (!eligible(host.generation) || editor.text.toString() != before) notify(R.string.handoff_changed_local)
                else {
                    editor.setText(desktopText); editor.setSelection(editor.length())
                    notify(R.string.handoff_loaded)
                }
            }
        }
        if (role != "read" && editor.text.isNotBlank()) dialog.setPositiveButton(R.string.handoff_save) { _, _ ->
            val phoneText = editor.text.toString()
            if (phoneText.length > 2048) { notify(R.string.handoff_too_long); return@setPositiveButton }
            confirm(desktopText.isNotEmpty() && desktopText != phoneText, R.string.handoff_replace_pc) {
                run {
                    withCurrentAccount(activity, host.generation) {
                        val currentOrigin = originOrNull(DeliveryTrace.store(activity)
                            .getString("companion_origin", "").orEmpty())
                        if (!eligible(host.generation) || currentOrigin != host.origin || editor.text.toString() != phoneText) {
                            notify(R.string.handoff_changed_local)
                            return@withCurrentAccount
                        }
                        host.api("draft", token, host.fields(id).put("version", revision).put("body", phoneText))
                        if (eligible(host.generation)) notify(R.string.handoff_saved)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirm(required: Boolean, message: Int, action: () -> Unit) {
        if (!required) { action(); return }
        AlertDialog.Builder(activity).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.handoff_replace) { _, _ -> action() }.show()
    }

    companion object {
        fun privateChanged(context: Context, id: ConversationId) {
            val account = readAccountCredentials(context)?.takeIf { it.configured } ?: return
            val generation = account.generation
            val pending = withSecurePreferences(context) { prefs ->
                val hosts = prefs.getStringSet("companion.origins.$generation", emptySet()).orEmpty()
                hosts.filter { origin ->
                    if (originOrNull(origin) != origin) return@filter false
                    val host = NativeCompanion(origin, generation, account.email)
                    if (!prefs.contains(host.key)) return@filter false
                    val key = host.key + ".pending"
                    val ids = prefs.getStringSet(key, emptySet()).orEmpty() + "${id.did}|${id.contact}"
                    prefs.edit().putStringSet(key, ids).commit()
                }
            }.orEmpty()
            for (origin in pending) NativeCompanion.enqueue(context.applicationContext, generation, origin)
        }
    }
}
