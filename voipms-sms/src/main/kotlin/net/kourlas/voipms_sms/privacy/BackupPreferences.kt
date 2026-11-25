package net.kourlas.voipms_sms.privacy

import android.content.Context
import net.kourlas.voipms_sms.utils.DocumentOutput
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DebugTrace
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import kotlin.coroutines.resume

internal class BackupPreferences(val fragment: PreferenceFragmentCompat) {
    private var exportId: String? = null
    private var restoreId: String? = null
    private val create = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        pickerResult(it, true)
    }
    private val open = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { pickerResult(it, false) }

    private fun action(writing: Boolean) =
        if (writing) DiagnosticAction.PORTABLE_EXPORT else DiagnosticAction.PORTABLE_RESTORE

    private fun pickDocument(writing: Boolean) {
        val c = fragment.requireContext()
        val trace = DebugJournal.begin(c, action(writing))
        if (writing) exportId = trace.id else restoreId = trace.id
        trace.step(DiagnosticStep.PICKER_OPENED)
        try {
            if (writing) create.launch("messages.vsms") else open.launch(arrayOf("*/*"))
        } catch (e: RuntimeException) {
            if (writing) exportId = null else restoreId = null
            trace.step(DiagnosticStep.FAILED, error = e)
            Toast.makeText(c, if (writing) R.string.preferences_database_fail_create_document
                else R.string.preferences_database_fail_open_document, Toast.LENGTH_LONG).show()
        }
    }

    private fun pickerResult(uri: Uri?, writing: Boolean) {
        val trace = DebugJournal.resume(fragment.requireContext(), action(writing), if (writing) exportId else restoreId)
        if (writing) exportId = null else restoreId = null
        if (uri == null) {
            trace.step(DiagnosticStep.CANCELLED)
        } else {
            trace.step(DiagnosticStep.PICKER_RETURNED)
            askPassword(uri, writing, trace)
        }
    }

    fun attach() {
        val saved = fragment.savedStateRegistry.consumeRestoredStateForKey("portable_picker")
        exportId = saved?.getString("export")
        restoreId = saved?.getString("restore")
        fragment.savedStateRegistry.registerSavedStateProvider("portable_picker") {
            Bundle().apply { putString("export", exportId); putString("restore", restoreId) }
        }
        val c = fragment.requireContext()
        fun entry(label: Int, summaryRes: Int, action: () -> Unit) {
            fragment.preferenceScreen.addPreference(Preference(c).apply {
                title = c.getString(label)
                summary = c.getString(summaryRes)
                isPersistent = false
                setOnPreferenceClickListener { action(); true }
            })
        }
        entry(R.string.backup_export, R.string.backup_scope) { pickDocument(true) }
        entry(R.string.backup_restore, R.string.backup_restore_scope) { pickDocument(false) }
    }

    private fun askPassword(uri: Uri, writing: Boolean, trace: DebugTrace) {
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
        var submitted = false
        val owner = fragment.viewLifecycleOwner
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        dialog.setOnDismissListener {
            owner.lifecycle.removeObserver(observer)
            input.text.clear()
            repeat.text.clear()
            if (!submitted) trace.step(DiagnosticStep.CANCELLED)
        }
        owner.lifecycle.addObserver(observer)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            trace.step(DiagnosticStep.VALIDATING)
            if (input.length() == 0 || writing && (input.length() < 10 || !TextUtils.equals(input.text, repeat.text))) {
                input.error = c.getString(R.string.backup_mismatch)
            } else {
                val password = CharArray(input.length()) { index -> input.text[index] }
                input.text.clear()
                repeat.text.clear()
                submitted = true
                dialog.dismiss()
                val mode = PortableBackup.ReceivedMode.entries[received.selectedItemPosition.coerceAtLeast(0)]
                process(c.applicationContext, uri, writing, password, mode, contacts.isChecked, trace)
            }
        }
    }

    private fun process(context: Context, uri: Uri, writing: Boolean, password: CharArray,
                        mode: PortableBackup.ReceivedMode, includeContacts: Boolean, trace: DebugTrace) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val progress = android.widget.ProgressBar(fragment.requireContext())
            val job = coroutineContext[kotlinx.coroutines.Job]
            val busy = AlertDialog.Builder(fragment.requireContext()).setTitle(R.string.backup_working).setView(progress)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }.show()
            var preview: PortableBackup.Preview? = null
            var showSummary: Boolean
            var partialRestore = false
            try {
                val status = if (writing) {
                    val report = withContext(Dispatchers.IO) { PortableBackup.export(context, uri, password, mode, includeContacts, trace) }
                    showSummary = report.unavailable > 0 || !report.verified
                    val messageStatus = if (mode == PortableBackup.ReceivedMode.LINKS) context.getString(R.string.backup_saved)
                    else context.getString(R.string.backup_received_result,
                        context.resources.getQuantityString(R.plurals.backup_files_included, report.included, report.included),
                        context.resources.getQuantityString(R.plurals.backup_files_unavailable, report.unavailable, report.unavailable))
                    val includedNames = report.contacts
                    val status = if (includedNames == null) messageStatus else messageStatus + " " +
                        context.resources.getQuantityString(R.plurals.backup_contacts_included, includedNames, includedNames)
                    if (report.verified) status else "$status " + context.getString(R.string.backup_readback_unavailable)
                } else {
                    val parsed = withContext(Dispatchers.IO) {
                        val work = currentCoroutineContext()
                        PortableBackup.preview(context, uri, password, trace) { work.ensureActive() }.also { preview = it }
                    }
                    busy.hide()
                    trace.step(DiagnosticStep.SELECTING)
                    val selected = choose(parsed)
                    if (selected == null) {
                        trace.step(DiagnosticStep.CANCELLED)
                        return@launch
                    }
                    busy.show()
                    busy.getButton(AlertDialog.BUTTON_NEGATIVE).visibility = android.view.View.GONE
                    val result = withContext(Dispatchers.IO) { PortableBackup.restore(context, parsed, selected, trace) }
                    partialRestore = result.contactsFailed
                    showSummary = partialRestore
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
                if (!partialRestore) trace.step(DiagnosticStep.COMPLETED)
                if (showSummary && fragment.isAdded) AlertDialog.Builder(fragment.requireContext())
                    .setTitle(if (writing) R.string.backup_export else R.string.backup_restore).setMessage(status).setPositiveButton(android.R.string.ok, null).show()
                else Toast.makeText(context, status, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                trace.step(DiagnosticStep.CANCELLED)
                throw e
            } catch (e: Exception) {
                trace.step(DiagnosticStep.FAILED, error = e)
                val error = when (e) {
                    is DocumentOutput.Incomplete -> R.string.backup_provider_incomplete
                    is DocumentOutput.Unavailable, is SecurityException -> R.string.backup_provider_unavailable
                    else -> R.string.backup_error
                }
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            } finally {
                busy.dismiss()
                preview?.zip?.delete()
            }
        }.invokeOnCompletion { password.fill('\u0000') }
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
