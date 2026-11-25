package net.kourlas.voipms_sms.privacy

import androidx.core.content.edit
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber

internal object PrivateThreads {
    private fun prefs(c: Context) = c.getSharedPreferences("private-threads", Context.MODE_PRIVATE)
    fun isPrivate(context: Context, id: ConversationId) = prefs(context).getBoolean(id.getId(), false)
    fun hasPrivate(context: Context) = prefs(context).all.values.any { it == true }
    private var permitted: Pair<ConversationId, Long>? = null

    @Synchronized fun grant(id: ConversationId) { permitted = id to SystemClock.elapsedRealtime() }
    @Synchronized fun consume(id: ConversationId): Boolean {
        val entry = permitted ?: return false
        permitted = null
        return entry.first == id && SystemClock.elapsedRealtime() - entry.second < 5000
    }

    fun ids(context: Context, visibleOnly: Boolean = true): List<ConversationId> = prefs(context).all.filterValues { it == true }.keys
        .mapNotNull {
            val parts = it.split('_')
            if (parts.size == 2) ConversationId(parts[0], parts[1]) else null
        }.filter { !visibleOnly || it.did in getDids(context, onlyShowInConversationsView = true) }.sortedBy { it.getId() }

    fun configure(activity: ConversationActivity, id: ConversationId) {
        val turnOn = !isPrivate(activity, id)
        if (turnOn && !activity.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            Toast.makeText(activity, R.string.private_no_credential, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity).setTitle(if (turnOn) R.string.private_lock else R.string.private_unlock)
            .setMessage(if (turnOn) R.string.private_scope else R.string.private_remove_notice)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs(activity).edit { putBoolean(id.getId(), turnOn) }
                if (turnOn) net.kourlas.voipms_sms.companion.CompanionDraftHandoff.privateChanged(activity.applicationContext, id)
                Notifications.getInstance(activity).cancelNotification(id)
                if (turnOn) net.kourlas.voipms_sms.delivery.CallAlerts.cancel(activity, id)
                net.kourlas.voipms_sms.CustomApplication.getApplication().applicationScope.launch(Dispatchers.IO) {
                    val database = Database.getInstance(activity.applicationContext)
                    if (turnOn) database.clearSearchOcr(id)
                    database.updateShortcuts()
                }
                activity.finish()
            }.show()
    }
}

class PrivateThreadGateActivity : AppCompatActivity() {
    private var unlocked = false
    private val confirm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            unlocked = true
            showThreads()
        } else finish()
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState == null || savedInstanceState.getBoolean("listed")) {
            val challenge = getSystemService(KeyguardManager::class.java)
                .createConfirmDeviceCredentialIntent(getString(R.string.private_threads), null)
            if (challenge == null) finish() else confirm.launch(challenge)
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("listed", unlocked)
        super.onSaveInstanceState(state)
    }

    override fun onStop() {
        super.onStop()
        if (unlocked && !isChangingConfigurations) finish()
    }

    private fun showThreads() {
        val original = androidx.core.content.IntentCompat.getParcelableExtra(intent, "target", Intent::class.java)
        if (original != null) {
            val did = intent.getStringExtra("did") ?: return finish()
            val peer = intent.getStringExtra("peer") ?: return finish()
            open(ConversationId(did, peer), original)
            return
        }
        val ids = PrivateThreads.ids(this)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.private_threads)
        if (ids.isEmpty()) dialog.setMessage(R.string.private_empty)
        else dialog.setItems(ids.map { getFormattedPhoneNumber(it.contact) + " · " + getFormattedPhoneNumber(it.did) }.toTypedArray()) { _, index ->
            val id = ids[index]
            open(id, Intent(this, ConversationActivity::class.java)
                .putExtra(getString(R.string.conversation_did), id.did)
                .putExtra(getString(R.string.conversation_contact), id.contact))
        }
        dialog.setNegativeButton(android.R.string.cancel, null).setOnDismissListener { finish() }.show()
    }

    private fun open(id: ConversationId, target: Intent) {
        PrivateThreads.grant(id)
        target.setClass(this, ConversationActivity::class.java)
        target.flags = 0
        startActivity(target)
        finish()
    }
}
