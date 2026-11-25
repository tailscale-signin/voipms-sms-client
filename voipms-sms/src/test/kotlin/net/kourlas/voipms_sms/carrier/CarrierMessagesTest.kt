package net.kourlas.voipms_sms.carrier

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CarrierMessagesTest {
    @Test fun opensSingleRecipientComposerWithExactDraftAndNoSendOrReceiptExtras() {
        val body = "Meeting at 7?\nSee you there."
        val intent = requireNotNull(CarrierMessages.draftIntent("(416) 555-0100", body))
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto:+14165550100", intent.dataString)
        assertEquals("+14165550100", intent.data?.schemeSpecificPart)
        assertEquals(setOf("sms_body"), intent.extras?.keySet())
        assertEquals(body, intent.getStringExtra("sms_body"))
        assertNull(intent.clipData)
    }

    @Test fun preservesExplicitInternationalNumbersAndShortCodes() {
        for ((input, target) in listOf("+351 912 345 678" to "+351912345678",
                "351912345678" to "+351912345678", "14165550100" to "+14165550100", "43210" to "43210")) {
            val intent = requireNotNull(CarrierMessages.draftIntent(input, ""))
            assertEquals(target, intent.data?.schemeSpecificPart)
            assertFalse(intent.hasExtra("sms_body"))
        }
    }

    @Test fun cancellationKeepsDraftLocalAndConfirmationOpensTheSelectedApp() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        controller.setup()
        try {
            val handler = ResolveInfo().apply {
                isDefault = true
                nonLocalizedLabel = "Carrier app"
                activityInfo = ActivityInfo().apply {
                    packageName = "fixture.carrier"
                    name = "fixture.carrier.Compose"
                    exported = true
                    applicationInfo = ApplicationInfo().apply { packageName = "fixture.carrier" }
                }
            }
            val draft = requireNotNull(CarrierMessages.draftIntent("4165550100", "local draft"))
            shadowOf(activity.packageManager).addResolveInfoForIntent(draft, handler)
            CarrierMessages.open(activity, "4165550100", "local draft", hasAttachments = true)
            val cancelled = ShadowDialog.getLatestDialog() as AlertDialog
            assertNull(shadowOf(activity).nextStartedActivity)
            cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(activity).nextStartedActivity)
            CarrierMessages.open(activity, "4165550100", "local draft")
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val launch = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_SENDTO, launch.action)
            assertEquals("fixture.carrier", launch.`package`)
            assertEquals("smsto:+14165550100", launch.dataString)
            assertEquals("local draft", launch.getStringExtra("sms_body"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rejectsGroupsServiceCommandsOrInjectedParameters() {
        for (recipient in listOf("", "unknown", "+14165550100,+14165550101",
                "4165550100?body=surprise", "*123#", "4165550100;ext=50", "4165550100\n1234")) {
            assertNull(CarrierMessages.draftIntent(recipient, "private text"))
        }
    }
}
