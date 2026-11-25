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

import android.app.DatePickerDialog
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.PushRegistration
import net.kourlas.voipms_sms.preferences.accountConfigured
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getStartDate
import net.kourlas.voipms_sms.preferences.setStartDate
import net.kourlas.voipms_sms.sms.workers.SyncWorker
import net.kourlas.voipms_sms.utils.enablePushNotifications
import net.kourlas.voipms_sms.utils.pushUnavailableMessage
import net.kourlas.voipms_sms.utils.preferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SynchronizationPreferencesFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        // Add preferences
        addPreferencesFromResource(R.xml.preferences_synchronization)

        // Add listener for preference changes
        preferenceScreen
            .sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(this)

        // Add listeners to preferences
        for (preference in preferenceScreen.preferences) {
            when (preference.key) {
                PushRegistration.KEY -> preference.setOnPreferenceClickListener {
                    enablePushNotifications(requireContext(), activity)
                    true
                }


                getString(R.string.preferences_sync_start_date_key) ->
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            context?.let {
                                val calendar = Calendar.getInstance()
                                calendar.time = getStartDate(it)

                                val dialog = DatePickerDialog(
                                    it,
                                    { _, year, month, dayOfMonth ->
                                        val newCalendar = Calendar.getInstance()
                                        newCalendar.clear()
                                        newCalendar.set(Calendar.YEAR, year)
                                        newCalendar.set(Calendar.MONTH, month)
                                        newCalendar.set(
                                            Calendar.DAY_OF_MONTH,
                                            dayOfMonth
                                        )
                                        setStartDate(it, newCalendar.time)
                                    },
                                    calendar.get(Calendar.YEAR),
                                    calendar.get(Calendar.MONTH),
                                    calendar.get(Calendar.DAY_OF_MONTH)
                                )
                                dialog.show()
                            }
                            true
                        }

                getString(R.string.preferences_sync_interval_key) ->
                    preference.onPreferenceChangeListener =
                        Preference.OnPreferenceChangeListener { _, newValue ->
                            activity?.let {
                                SyncWorker.scheduleBackgroundSync(it, (newValue as String).toDouble())
                            }
                            true
                        }
            }
        }

        // Update preference summaries
        updateSummaries()
    }

    override fun onResume() {
        super.onResume()

        // Update preference summaries
        updateSummaries()
    }

    /**
     * Updates the summaries for all preferences.
     */
    private fun updateSummaries() {
        for (preference in preferenceScreen.preferences) {
            updateSummary(preference)
        }
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        // It's not clear why onSharedPreferenceChanged is called before the
        // fragment is actually added to the activity, but it apparently is;
        // this check is therefore required to prevent a crash
        if (isAdded && key != null) {
            updateSummary(findPreference(key))
        }
    }

    /**
     * Updates the summary for the specified preference.
     */
    private fun updateSummary(preference: Preference?) {
        context?.let {
            if (preference?.key == PushRegistration.KEY) {
                val unavailable = pushUnavailableMessage(it)
                val dids = if (accountConfigured(it)) getDids(it, onlyRetrieveMessages = true) else emptySet()
                preference.isSelectable = unavailable == null && dids.isNotEmpty()
                preference.summary = when {
                    unavailable != null -> getString(unavailable)
                    dids.isEmpty() -> getString(R.string.push_no_dids)
                    else -> {
                        val statuses = PushRegistration.read(it)
                        dids.sorted().joinToString("\n") { did ->
                            getString(R.string.push_did_status, did,
                                getString(statuses[did]?.description ?: R.string.push_pending))
                        } + "\n" + getString(R.string.push_delivery_retry)
                    }
                }
            } else if (preference is ListPreference) {
                // Display value of selected element as summary text
                preference.summary = preference.entry
            } else if (
                preference?.key == getString(
                    R.string.preferences_sync_start_date_key
                )
            ) {
                // Display start date
                val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                preference.summary = sdf.format(getStartDate(it))
            }
        }
    }
}