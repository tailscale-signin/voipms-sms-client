package net.kourlas.voipms_sms.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.CustomApplication
import net.kourlas.voipms_sms.R
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class DiagnosticPreferences(private val fragment: PreferenceFragmentCompat) {
    private lateinit var toggle: SwitchPreferenceCompat
    private lateinit var export: Preference
    private lateinit var clear: Preference
    private var pickerId: String? = null
    private var changing = false
    private val picker = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        val app = fragment.requireContext().applicationContext
        val trace = DebugJournal.resume(app, DiagnosticAction.DEBUG_EXPORT, pickerId)
        pickerId = null
        refresh()
        when {
            uri == null -> trace.step(DiagnosticStep.CANCELLED)
            trace.id == null -> toast(app, R.string.debug_recording_ended)
            else -> {
                trace.step(DiagnosticStep.PICKER_RETURNED)
                writeReport(app, uri, trace)
            }
        }
    }

    fun attach(saved: Bundle?) {
        pickerId = saved?.getString("debugJournalPicker")
        val context = fragment.preferenceManager.context
        val group = PreferenceCategory(context).apply { setTitle(R.string.debug_diagnostics) }
        fragment.preferenceScreen.addPreference(group)
        toggle = SwitchPreferenceCompat(context).apply {
            key = "debug_mode"
            isPersistent = false
            setTitle(R.string.debug_mode)
            setSummary(R.string.debug_mode_summary)
            setOnPreferenceChangeListener { _, value ->
                updateRecording { DebugJournal.setEnabled(it, value == true) }
                false
            }
        }
        group.addPreference(toggle)
        export = Preference(context).apply {
            setTitle(R.string.debug_export)
            setSummary(R.string.debug_export_summary)
            setOnPreferenceClickListener { openPicker(); true }
        }
        group.addPreference(export)
        clear = Preference(context).apply {
            setTitle(R.string.debug_clear)
            setOnPreferenceClickListener {
                AlertDialog.Builder(fragment.requireContext()).setTitle(R.string.debug_clear)
                    .setMessage(R.string.debug_clear_confirm)
                    .setPositiveButton(R.string.debug_clear) { _, _ -> updateRecording(DebugJournal::clear) }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        }
        group.addPreference(clear)
        refresh()
    }

    fun saveState(out: Bundle) = out.putString("debugJournalPicker", pickerId)

    fun refresh() {
        val app = fragment.context?.applicationContext ?: return
        if (!::clear.isInitialized) return
        val enabled = DebugJournal.enabled(app)
        toggle.isChecked = enabled
        toggle.isEnabled = !changing
        export.isEnabled = enabled && !changing && pickerId == null
        clear.isEnabled = enabled && !changing
    }

    private fun updateRecording(update: (Context) -> Unit) {
        if (changing) return
        val app = fragment.requireContext().applicationContext
        changing = true
        refresh()
        CustomApplication.getApplication().applicationScope.launch(Dispatchers.Main.immediate) {
            try {
                withContext(Dispatchers.IO) { update(app) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                toast(app, R.string.debug_update_failed)
            } finally {
                changing = false
                refresh()
            }
        }
    }

    private fun openPicker() {
        if (pickerId != null) return
        val app = fragment.requireContext().applicationContext
        val trace = DebugJournal.begin(app, DiagnosticAction.DEBUG_EXPORT)
        pickerId = trace.id ?: return
        refresh()
        try {
            trace.step(DiagnosticStep.PICKER_OPENED)
            val stamp = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneOffset.UTC).format(Instant.now())
            picker.launch("voipms-sms-debug-$stamp.jsonl")
        } catch (e: RuntimeException) {
            pickerId = null
            trace.step(DiagnosticStep.FAILED, error = e)
            refresh()
            toast(app, R.string.debug_export_failed)
        }
    }

    private fun writeReport(app: Context, uri: Uri, trace: DebugTrace) {
        CustomApplication.getApplication().applicationScope.launch(Dispatchers.IO) {
            val status = try {
                val verified = DebugJournal.export(app, uri, trace)
                if (!verified) trace.step(DiagnosticStep.UNAVAILABLE)
                trace.step(DiagnosticStep.COMPLETED)
                if (verified) R.string.debug_export_success else R.string.debug_export_unverified
            } catch (e: CancellationException) {
                trace.step(DiagnosticStep.CANCELLED)
                throw e
            } catch (e: Exception) {
                trace.step(DiagnosticStep.FAILED, error = e)
                R.string.debug_export_failed
            }
            withContext(Dispatchers.Main) { toast(app, status) }
        }
    }

    private fun toast(context: Context, message: Int) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
