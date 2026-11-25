/*
 * VoIP.ms SMS
 * Copyright (C) 2018-2022 Michael Kourlas
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

package net.kourlas.voipms_sms.preferences.fragments

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.CustomApplication
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.database.DatabaseImportException
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DebugTrace
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.privacy.PrivateThreads
import java.io.IOException
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.utils.preferences
import net.kourlas.voipms_sms.utils.showAlertDialog
import net.kourlas.voipms_sms.utils.showErrorMessage

private const val TAG = "DatabasePrefsFragment"

/**
 * Fragment used to display the database preferences.
 */
class DatabasePreferencesFragment : PreferenceFragmentCompat() {
    private val portable = net.kourlas.voipms_sms.privacy.BackupPreferences(this)
    private val debug = net.kourlas.voipms_sms.diagnostics.DiagnosticPreferences(this)
    private var importId: String? = null
    private var exportId: String? = null
    private var pendingRawExport: Uri? = null

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val trace = DebugJournal.resume(requireContext(), DiagnosticAction.DATABASE_IMPORT, importId)
        importId = null
        if (uri == null) trace.step(DiagnosticStep.CANCELLED)
        else {
            trace.step(DiagnosticStep.PICKER_RETURNED)
            transfer(uri, false, trace)
        }
    }

    private val exportPicker = registerForActivityResult(CreateDocument(SQLITE_MIME_TYPE)) { uri ->
        val trace = DebugJournal.resume(requireContext(), DiagnosticAction.DATABASE_EXPORT, exportId)
        if (uri == null) {
            exportId = null
            trace.step(DiagnosticStep.CANCELLED)
        } else {
            trace.step(DiagnosticStep.PICKER_RETURNED)
            protectRawExport(uri, trace)
        }
    }

    private val verifyRawExport = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val context = requireContext()
        val trace = DebugJournal.resume(context, DiagnosticAction.DATABASE_EXPORT, exportId)
        val uri = pendingRawExport
        pendingRawExport = null
        exportId = null
        when {
            result.resultCode != Activity.RESULT_OK -> trace.step(DiagnosticStep.CANCELLED)
            uri != null -> transfer(uri, true, trace)
            else -> {
                trace.step(DiagnosticStep.FAILED)
                showStatus(context, R.string.database_export_unlock_unavailable)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        debug.saveState(outState)
        outState.putString("rawImportTrace", importId)
        outState.putString("rawExportTrace", exportId)
        outState.putString("rawExportDocument", pendingRawExport?.toString())
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        importId = savedInstanceState?.getString("rawImportTrace")
        exportId = savedInstanceState?.getString("rawExportTrace")
        pendingRawExport = savedInstanceState?.getString("rawExportDocument")?.toUri()
        // Add preferences
        addPreferencesFromResource(R.xml.preferences_database)
        portable.attach()
        debug.attach(savedInstanceState)

        // Assign listeners to preferences
        for (preference in preferenceScreen.preferences) {
            when (preference.key) {
                getString(R.string.preferences_database_import_key) ->
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            launchPicker(false)
                            true
                        }

                getString(
                    R.string.preferences_database_export_key
                ) ->
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            launchPicker(true)
                            true
                        }

                getString(
                    R.string.preferences_database_clean_up_key
                ) ->
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            cleanUp()
                            true
                        }

                getString(
                    R.string.preferences_database_delete_key
                ) ->
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            delete()
                            true
                        }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        debug.refresh()
    }

    private fun launchPicker(writing: Boolean) {
        val context = requireContext()
        val trace = DebugJournal.begin(context, if (writing) DiagnosticAction.DATABASE_EXPORT else DiagnosticAction.DATABASE_IMPORT)
        trace.step(DiagnosticStep.PICKER_OPENED)
        try {
            if (writing) {
                exportId = trace.id
                exportPicker.launch("sms.db")
            } else {
                importId = trace.id
                importPicker.launch(arrayOf(SQLITE_MIME_TYPE, OCTET_STREAM_MIME_TYPE))
            }
        } catch (e: RuntimeException) {
            if (writing) exportId = null else importId = null
            trace.step(DiagnosticStep.FAILED, error = e)
            showStatus(context, if (writing) R.string.preferences_database_fail_create_document else R.string.preferences_database_fail_open_document)
        }
    }

    @Suppress("DEPRECATION")
    private fun protectRawExport(uri: Uri, trace: DebugTrace) {
        val context = requireContext()
        try {
            if (!PrivateThreads.hasPrivate(context)) {
                exportId = null
                transfer(uri, true, trace)
                return
            }
            trace.step(DiagnosticStep.AUTHORIZING)
            val proof = context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(
                getString(R.string.private_threads), getString(R.string.backup_legacy_private)
            )
            if (proof == null) {
                exportId = null
                trace.step(DiagnosticStep.FAILED)
                showStatus(context, R.string.database_export_unlock_unavailable)
                return
            }
            pendingRawExport = uri
            verifyRawExport.launch(proof)
        } catch (e: RuntimeException) {
            exportId = null
            pendingRawExport = null
            trace.step(DiagnosticStep.FAILED, error = e)
            showStatus(context, R.string.database_export_unlock_unavailable)
        }
    }

    private fun transfer(uri: Uri, writing: Boolean, trace: DebugTrace) {
        val context = requireContext().applicationContext
        CustomApplication.getApplication().applicationScope.launch(Dispatchers.IO) {
            val status = try {
                val database = Database.getInstance(context)
                if (writing) {
                    val result = database.export(trace, readBack = { context.contentResolver.openInputStream(uri) }) { mode ->
                        context.contentResolver.openFileDescriptor(uri, mode)
                    }
                    trace.step(DiagnosticStep.COMPLETED, count = result.bytes)
                    context.getString(if (result.verified) R.string.preferences_database_export_success else R.string.database_export_unverified)
                } else {
                    database.import(trace) { context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException() }
                    trace.step(DiagnosticStep.COMPLETED)
                    context.getString(R.string.preferences_database_import_success)
                }
            } catch (e: CancellationException) {
                trace.step(DiagnosticStep.CANCELLED)
                throw e
            } catch (e: Exception) {
                trace.step(DiagnosticStep.FAILED, error = e)
                val detail = if (e is DatabaseImportException) e.message else context.getString(R.string.database_file_access_failed)
                context.getString(if (writing) R.string.preferences_database_export_fail else R.string.preferences_database_import_fail, detail)
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, status, Toast.LENGTH_LONG).show() }
        }
    }

    private fun showStatus(context: Context, message: Int) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun cleanUp() {
        val activity = activity ?: return

        val options = arrayOf(
            activity.getString(
                R.string.preferences_database_clean_up_deleted_messages
            ),
            activity.getString(
                R.string.preferences_database_clean_up_removed_dids
            )
        )
        val selectedOptions = mutableListOf<Int>()

        // Ask user which kind of clean up is desired, and then perform that
        // clean up
        AlertDialog.Builder(activity).apply {
            setTitle(
                context.getString(
                    R.string.preferences_database_clean_up_title
                )
            )
            setMultiChoiceItems(
                options, null
            ) { _, which, isChecked ->
                if (isChecked) {
                    selectedOptions.add(which)
                } else {
                    selectedOptions.remove(which)
                }
            }
            setPositiveButton(
                context.getString(R.string.ok)
            ) { _, _ ->
                val deletedMessages = selectedOptions.contains(0)
                val removedDids = selectedOptions.contains(1)

                CustomApplication.getApplication().applicationScope.launch(
                    Dispatchers.Default
                ) {
                    try {
                        if (deletedMessages) {
                            Database.getInstance(context)
                                .deleteTableDeletedContents()
                        }
                        if (removedDids) {
                            Database.getInstance(context).deleteMessagesWithoutDids(
                                getDids(context)
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clean up database", e)
                        showErrorMessage(
                            activity,
                            R.id.coordinator_layout,
                            activity.getString(R.string.preferences_database_clean_up_fail)
                        )
                    }
                }
            }
            setNegativeButton(context.getString(R.string.cancel), null)
            setCancelable(false)
            show()
        }
    }

    private fun delete() {
        val activity = activity ?: return

        // Prompt the user before actually deleting the entire database
        showAlertDialog(
            activity,
            activity.getString(
                R.string.preferences_database_delete_confirm_title
            ),
            activity.getString(
                R.string.preferences_database_delete_confirm_message
            ),
            activity.applicationContext
                .getString(R.string.delete),
            { _, _ ->
                CustomApplication.getApplication().applicationScope.launch(
                    Dispatchers.Default
                ) {
                    try {
                        Database.getInstance(
                            activity.applicationContext
                        )
                            .deleteTablesContents()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete database", e)
                        showErrorMessage(
                            activity,
                            R.id.coordinator_layout,
                            activity.getString(R.string.preferences_database_delete_fail)
                        )
                    }
                }
            },
            activity.getString(R.string.cancel), null
        )
    }

    companion object {
        private const val SQLITE_MIME_TYPE = "application/x-sqlite3"
        private const val OCTET_STREAM_MIME_TYPE = "application/octet-stream"
    }
}