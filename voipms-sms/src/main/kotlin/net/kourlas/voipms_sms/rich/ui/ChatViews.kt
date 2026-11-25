package net.kourlas.voipms_sms.rich.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.rich.RichEvent
import java.text.DateFormat
import java.util.Date

internal data class ThreadChatEvent(val key: String, val time: Long, val body: String, val mine: Boolean,
    val route: ChatRoute, val sender: String, val status: String, val attachment: Boolean = false, val warning: String? = null)

internal fun RichEvent.toThreadEvent(profile: net.kourlas.voipms_sms.sms.InboxRules.Profile = net.kourlas.voipms_sms.sms.InboxRules.Profile()): ThreadChatEvent {
    val warning = if (mine) null else net.kourlas.voipms_sms.sms.InboxRules.assess(body, profile).description
    return ThreadChatEvent(id, timestamp, body, mine, ChatRoute.MATRIX, sender, status, mime != null, warning)
}

/** One lifecycle collector per screen. A changed recipient cancels both previous transport subscriptions. */
internal class ResumedChatFeed(activity: AppCompatActivity) {
    private val subscription = MutableStateFlow<(suspend CoroutineScope.() -> Unit)?>(null)

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                subscription.collectLatest { block ->
                    if (block != null) coroutineScope(block)
                }
            }
        }
    }

    fun observe(block: suspend CoroutineScope.() -> Unit) { subscription.value = block }
    fun clear() { subscription.value = null }
}

internal fun AppCompatActivity.chatAction(onError: Int = R.string.chat_error, block: suspend () -> Unit): Job =
    lifecycleScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (!isFinishing && !isDestroyed) Toast.makeText(this@chatAction, onError, Toast.LENGTH_LONG).show() }
    }

internal class ChatHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val root = view as LinearLayout
    private val body: TextView = view.findViewById(R.id.chat_event_body)
    private val meta: TextView = view.findViewById(R.id.chat_event_meta)
    private val warning: TextView = view.findViewById(R.id.chat_event_warning)

    fun bind(event: ThreadChatEvent, showMedia: (ThreadChatEvent) -> Unit) {
        val c = itemView.context
        root.gravity = if (event.mine) Gravity.END else Gravity.START
        body.setBackgroundResource(if (event.mine) R.drawable.bubble_outgoing else R.drawable.bubble_incoming)
        body.setTextColor(c.getColor(if (event.mine) R.color.on_message_outgoing else R.color.on_message_incoming))
        body.text = event.body.ifBlank { c.getString(R.string.chat_media_label) }
        body.setOnClickListener(if (event.attachment) View.OnClickListener { showMedia(event) } else null)
        body.isClickable = event.attachment
        warning.visibility = if (event.warning == null) View.GONE else View.VISIBLE
        warning.text = event.warning
        val label = c.getString(if (event.route == ChatRoute.NEARBY) R.string.chat_nearby else R.string.chat_matrix)
        val who = if (event.mine || event.sender.isBlank()) label else c.getString(R.string.chat_meta, label, event.sender)
        val whenSent = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.time))
        meta.text = if (event.status.isBlank()) c.getString(R.string.chat_meta, who, whenSent)
            else c.getString(R.string.chat_meta_state, who, whenSent, event.status)
    }
}

internal fun AppCompatActivity.previewChatMedia(load: suspend () -> Uri) {
    AlertDialog.Builder(this).setMessage(R.string.chat_external_preview)
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
            chatAction(R.string.chat_preview_error) {
                val uri = load()
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, null))
            }
        }.show()
}

internal fun Context.chatDp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

internal fun android.widget.EditText.importantForAutofillCompat() {
    if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
}

internal fun confirmMedia(activity: AppCompatActivity, uri: Uri, recipient: String, send: suspend () -> Unit) {
    if (uri.scheme != "content") return
    activity.chatAction {
        val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } ?: activity.getString(R.string.chat_media_label)
        AlertDialog.Builder(activity).setMessage(activity.getString(R.string.chat_media_confirm, name, recipient))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chat_send) { _, _ -> activity.chatAction(block = send) }.show()
    }
}
