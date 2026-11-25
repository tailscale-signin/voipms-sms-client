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
        val received = android.widget.Spinner(c).apply {
            adapter = android.widget.ArrayAdapter.createFromResource(c, R.array.backup_received_options,
                android.R.layout.simple_spinner_dropdown_item)
            contentDescription = c.getString(R.string.backup_received_label)
        }
        val contacts = android.widget.CheckBox(c).apply {
            setText(R.string.backup_include_contacts)
            isChecked = true
        }
        val layout = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(input)
            if (writing) {
                addView(repeat)
                addView(contacts)
                addView(android.widget.TextView(c).apply { setText(R.string.backup_received_label) })
                addView(received)
                addView(android.widget.TextView(c).apply { setText(R.string.backup_received_limit) })
            }
        }
        val dialog = AlertDialog.Builder(c).setTitle(if (writing) R.string.backup_export else R.string.backup_restore)
            .setView(android.widget.ScrollView(c).apply { addView(layout) }).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.length() == 0 || writing && (input.length() < 10 || input.text.toString() != repeat.text.toString())) {
                input.error = c.getString(R.string.backup_mismatch)
            } else {
                val password = CharArray(input.length()) { index -> input.text[index] }
                input.text.clear()
                repeat.text.clear()
                dialog.dismiss()
                val mode = PortableBackup.ReceivedMode.entries[received.selectedItemPosition.coerceAtLeast(0)]
                process(c.applicationContext, uri, writing, password, mode, contacts.isChecked)
            }
        }
    }

    private fun process(context: Context, uri: Uri, writing: Boolean, password: CharArray,
                        mode: PortableBackup.ReceivedMode, includeContacts: Boolean) =
        fragment.lifecycleScope.launch {
            val progress = android.widget.ProgressBar(fragment.requireContext())
            val job = coroutineContext[kotlinx.coroutines.Job]
            val busy = AlertDialog.Builder(fragment.requireContext()).setTitle(R.string.backup_working).setView(progress)
                .setCancelable(false).apply {
                    if (writing) setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
                }.show()
            var preview: PortableBackup.Preview? = null
            var showSummary = false
            try {
                val status = if (writing) {
                    val report = withContext(Dispatchers.IO) { PortableBackup.export(context, uri, password, mode, includeContacts) }
                    showSummary = report.unavailable > 0
                    val messageStatus = if (mode == PortableBackup.ReceivedMode.LINKS) context.getString(R.string.backup_saved)
                    else context.getString(R.string.backup_received_result,
                        context.resources.getQuantityString(R.plurals.backup_files_included, report.included, report.included),
                        context.resources.getQuantityString(R.plurals.backup_files_unavailable, report.unavailable, report.unavailable))
                    val includedNames = report.contacts
                    if (includedNames == null) messageStatus else messageStatus + " " +
                        context.resources.getQuantityString(R.plurals.backup_contacts_included, includedNames, includedNames)
                } else {
                    val parsed = withContext(Dispatchers.IO) { PortableBackup.preview(context, uri, password) }
                    preview = parsed
                    busy.hide()
                    val selected = choose(parsed) ?: return@launch
                    busy.show()
                    val result = withContext(Dispatchers.IO) { PortableBackup.restore(context, parsed, selected) }
                    showSummary = result.contactsFailed
                    val messages = context.resources.getQuantityString(R.plurals.backup_restored, result.messages, result.messages)
                    when {
                        result.contactsFailed -> context.getString(R.string.backup_contacts_failed, messages)
                        selected.contacts.isEmpty() -> messages
                        else -> {
                            val names = context.resources.getQuantityString(R.plurals.backup_contacts_restored, result.contacts, result.contacts)
                            if (selected.threads.isEmpty()) names else "$messages $names"
                        }
                    }
                }
                if (showSummary && fragment.isAdded) AlertDialog.Builder(fragment.requireContext())
                    .setTitle(if (writing) R.string.backup_export else R.string.backup_restore).setMessage(status).setPositiveButton(android.R.string.ok, null).show()
                else Toast.makeText(context, status, Toast.LENGTH_LONG).show()
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

    private enum class Scope { BOTH, THREADS, CONTACTS }

    private suspend fun choose(preview: PortableBackup.Preview): PortableBackup.Selection? {
        val c = fragment.requireContext()
        val ids = preview.counts.keys.filterNot { PrivateThreads.isPrivate(c, it) }
        val scope = when {
            preview.contacts.isEmpty() -> Scope.THREADS
            ids.isEmpty() -> Scope.CONTACTS
            else -> chooseScope() ?: return null
        }
        val threads = if (scope == Scope.CONTACTS || ids.isEmpty()) emptySet() else {
            val labels = withContext(Dispatchers.Default) {
                ids.map { "${getFormattedPhoneNumber(it.contact)} · ${getFormattedPhoneNumber(it.did)} (${preview.counts[it]})" }
            }
            pick(R.string.backup_pick, ids, labels) ?: return null
        }
        val contacts = if (scope == Scope.THREADS) emptySet() else {
            val (numbers, labels) = withContext(Dispatchers.Default) {
                val order = compareBy<Map.Entry<String, String>, String>(String.CASE_INSENSITIVE_ORDER) { it.value }.thenBy { it.key }
                val names = preview.contacts.entries.sortedWith(order)
                names.map { it.key } to names.map { "${it.value} · ${getFormattedPhoneNumber(it.key)}" }
            }
            pick(R.string.backup_pick_contacts, numbers, labels) ?: return null
        }
        return PortableBackup.Selection(threads, contacts)
    }

    private suspend fun chooseScope(): Scope? = suspendCancellableCoroutine { wait ->
        val c = fragment.requireContext()
        val dialog = AlertDialog.Builder(c).setTitle(R.string.backup_restore_contents)
            .setItems(R.array.backup_restore_options) { _, i -> if (wait.isActive) wait.resume(Scope.entries[i]) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (wait.isActive) wait.resume(null) }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        wait.invokeOnCancellation { dialog.dismiss() }
    }

    private suspend fun <T> pick(title: Int, items: List<T>, labels: List<String>): Set<T>? =
        suspendCancellableCoroutine { wait ->
            val c = fragment.requireContext()
            val selection = items.toMutableSet()
            val checkedItems = BooleanArray(items.size) { true }
            val dialog = AlertDialog.Builder(c).setTitle(title)
                .setMultiChoiceItems(labels.toTypedArray(), checkedItems) { dialog, index, checked ->
                    if (checked) selection.add(items[index]) else selection.remove(items[index])
                    (dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setText(if (selection.size == items.size) R.string.backup_clear_selection else R.string.backup_select_all)
                }.setPositiveButton(android.R.string.ok) { _, _ -> if (wait.isActive) wait.resume(selection.toSet()) }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.backup_clear_selection, null)
                .setOnDismissListener { if (wait.isActive) wait.resume(null) }.create()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val check = selection.size != items.size
                if (check) selection.addAll(items) else selection.clear()
                checkedItems.fill(check)
                for (index in items.indices) dialog.listView.setItemChecked(index, check)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setText(if (check) R.string.backup_clear_selection else R.string.backup_select_all)
            }
            wait.invokeOnCancellation { dialog.dismiss() }
        }
}
