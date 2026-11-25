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
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.preferences.activities.DidsPreferencesActivity
import net.kourlas.voipms_sms.signIn.SignInActivity
import net.kourlas.voipms_sms.sms.LiveDeliveryService
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
import java.util.Date
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
                "live_listening" -> preference.setOnPreferenceClickListener {
                    val context = requireContext()
                    when {
                        LiveDeliveryService.state.value.running -> LiveDeliveryService.stop(context)
                        !accountConfigured(context) -> startActivity(Intent(context, SignInActivity::class.java))
                        getDids(context, onlyRetrieveMessages = true).isEmpty() ->
                            startActivity(Intent(context, DidsPreferencesActivity::class.java))
                        else -> LiveDeliveryService.start(context)
                    }
                    true
                }
                "live_alert_settings" -> preference.setOnPreferenceClickListener {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${requireContext().packageName}".toUri())
                    }
                    startActivity(intent)
                    true
                }
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveDeliveryService.state.collect { updateSummary(findPreference("live_listening")) }
            }
        }
    }

    override fun onDestroy() {
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
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
            if (preference?.key == "live_listening") {
                val state = LiveDeliveryService.state.value
                preference.setTitle(if (state.running) R.string.live_stop else R.string.live_start)
                preference.summary = when {
                    state.message != null -> state.message
                    state.lastSuccess != null -> getString(R.string.live_last_check,
                        DateFormat.getTimeFormat(it).format(Date(state.lastSuccess)))
                    state.running -> getString(R.string.live_active)
                    !accountConfigured(it) -> getString(R.string.live_sign_in)
                    getDids(it, onlyRetrieveMessages = true).isEmpty() -> getString(R.string.push_no_dids)
                    else -> getString(R.string.live_idle)
                }
            } else if (preference?.key == "live_alert_settings") {
                preference.isVisible = !NotificationManagerCompat.from(it).areNotificationsEnabled()
            } else if (preference?.key == PushRegistration.KEY) {
                val unavailable = pushUnavailableMessage(it)
                val dids = if (accountConfigured(it)) getDids(it, onlyRetrieveMessages = true) else emptySet()
                preference.isVisible = unavailable == null
                preference.isSelectable = dids.isNotEmpty()
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