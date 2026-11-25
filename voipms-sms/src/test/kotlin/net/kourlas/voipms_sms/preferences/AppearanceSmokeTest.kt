package net.kourlas.voipms_sms.preferences

import android.app.Application
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.preferences.activities.AppearancePreferencesActivity
import net.kourlas.voipms_sms.preferences.fragments.AppearancePreferencesFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppearanceSmokeTest {
    @Test
    @Config(qualifiers = "night")
    fun newInstallShowsDarkPreferenceAndReadableMessages() {
        assertAppearanceScreen(AppearancePreferencesFragment.DARK)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun explicitLightChoiceSurvivesOpeningAppearanceSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(app.getString(R.string.preferences_theme_key), AppearancePreferencesFragment.LIGHT)
            .apply()
        assertAppearanceScreen(AppearancePreferencesFragment.LIGHT)
    }

    private fun assertAppearanceScreen(expectedMode: String) {
        val controller = Robolectric.buildActivity(AppearancePreferencesActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.supportFragmentManager.executePendingTransactions()
            val settings = activity.supportFragmentManager
                .findFragmentById(R.id.preferences_fragment_layout) as AppearancePreferencesFragment
            val theme = settings.findPreference<ListPreference>(activity.getString(R.string.preferences_theme_key))
            assertNotNull(theme)
            assertEquals(expectedMode, theme!!.value)
            assertEquals(expectedMode, getAppTheme(activity))

            val parent = FrameLayout(activity)
            fun checkMessage(layout: Int, background: Int, checkedBackground: Int) {
                val bubble = activity.layoutInflater.inflate(layout, parent, false)
                val text = bubble.findViewById<TextView>(R.id.message).currentTextColor
                for (role in listOf(background, checkedBackground)) {
                    assertTrue(ColorUtils.calculateContrast(text, activity.getColor(role)) >= 4.5)
                }
            }
            checkMessage(R.layout.conversation_item_incoming, R.color.message_incoming, R.color.message_incoming_checked)
            checkMessage(R.layout.conversation_item_outgoing, R.color.message_outgoing, R.color.message_outgoing_checked)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
