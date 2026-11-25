package net.kourlas.voipms_sms.privacy

import android.content.Context
import android.net.Uri
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import kotlin.coroutines.resume

internal class BackupPreferences(val fragment: PreferenceFragmentCompat) {
    private val create = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) askPassword(uri, true)
    }
    private val open = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askPassword(uri, false)
    }

    fun attach() {
        val c = fragment.requireContext()
        fun entry(label: Int, summaryRes: Int, action: () -> Unit) {
            fragment.preferenceScreen.addPreference(Preference(c).apply {
                title = c.getString(label)
                summary = c.getString(summaryRes)
                isPersistent = false
                setOnPreferenceClickListener { action(); true }
            })
        }
        entry(R.string.backup_export, R.string.backup_scope) { create.launch("messages.vsms") }
        entry(R.string.backup_restore, R.string.backup_restore_scope) { open.launch(arrayOf("*/*")) }
    }

    private fun askPassword(uri: Uri, writing: Boolean) {
        val c = fragment.requireContext()
        fun field() = EditText(c).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        val input = field().apply { hint = c.getString(R.string.backup_password) }
        val repeat = field().apply { hint = c.getString(R.string.backup_repeat) }
        val layout = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(input)
            if (writing) addView(repeat)
        }
        val dialog = AlertDialog.Builder(c).setTitle(if (writing) R.string.backup_export else R.string.backup_restore)
            .setView(layout).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.length() == 0 || writing && (input.length() < 10 || input.text.toString() != repeat.text.toString())) {
                input.error = c.getString(R.string.backup_mismatch)
            } else {
                val password = CharArray(input.length()) { index -> input.text[index] }
                input.text.clear()
                repeat.text.clear()
                dialog.dismiss()
                process(c.applicationContext, uri, writing, password)
            }
        }
    }

    private fun process(context: Context, uri: Uri, writing: Boolean, password: CharArray) =
        fragment.lifecycleScope.launch {
            val progress = android.widget.ProgressBar(fragment.requireContext())
            val busy = AlertDialog.Builder(fragment.requireContext()).setTitle(R.string.backup_working).setView(progress)
                .setCancelable(false).show()
            var preview: PortableBackup.Preview? = null
            try {
                val status = if (writing) {
                    withContext(Dispatchers.IO) { PortableBackup.export(context, uri, password) }
                    context.getString(R.string.backup_saved)
                } else {
                    val parsed = withContext(Dispatchers.IO) { PortableBackup.preview(context, uri, password) }
                    preview = parsed
                    busy.hide()
                    val selected = choose(parsed) ?: return@launch
                    busy.show()
                    val count = withContext(Dispatchers.IO) {
                        Database.getInstance(context).restorePortableThreads(parsed, selected)
                    }
                    context.getString(R.string.backup_restored, count)
                }
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, R.string.backup_error, Toast.LENGTH_LONG).show()
            } finally {
                busy.dismiss()
                password.fill('\u0000')
                preview?.zip?.delete()
            }
        }

    private suspend fun choose(preview: PortableBackup.Preview): Set<ConversationId>? = suspendCancellableCoroutine { wait ->
        val c = fragment.requireContext()
        val ids = preview.counts.keys.filterNot { PrivateThreads.isPrivate(c, it) }
        val labels = ids.map { "${getFormattedPhoneNumber(it.contact)} · ${getFormattedPhoneNumber(it.did)} (${preview.counts[it]})" }
        val selection = ids.toMutableSet()
        val dialog = AlertDialog.Builder(c).setTitle(R.string.backup_pick)
            .setMultiChoiceItems(labels.toTypedArray(), BooleanArray(ids.size) { true }) { _, index, checked ->
                if (checked) selection.add(ids[index]) else selection.remove(ids[index])
            }.setPositiveButton(R.string.backup_restore) { _, _ -> if (wait.isActive) wait.resume(selection) }
            .setNegativeButton(android.R.string.cancel, null).setOnDismissListener { if (wait.isActive) wait.resume(null) }.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        wait.invokeOnCancellation { dialog.dismiss() }
    }
}
