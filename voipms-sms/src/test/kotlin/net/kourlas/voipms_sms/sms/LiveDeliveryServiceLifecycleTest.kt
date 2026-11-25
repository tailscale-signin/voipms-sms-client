package net.kourlas.voipms_sms.sms

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.preferences.activities.SynchronizationPreferencesActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveDeliveryServiceLifecycleTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun mergedManifestDeclaresPrivateDataSyncServiceAndPermissions() {
        val manager = app.packageManager
        val info = manager.getServiceInfo(ComponentName(app, LiveDeliveryService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        val permissions = manager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in permissions)
    }

    @Test
    fun signedOutStartDoesNotQueueAService() {
        val application = shadowOf(app)
        application.clearStartedServices()
        LiveDeliveryService.start(app)
        assertNull(application.peekNextStartedService())
        assertFalse(LiveDeliveryService.state.value.running)
    }

    @Test
    fun notificationOpensSettingsAndItsStopActionEndsTheSession() {
        val controller = Robolectric.buildService(LiveDeliveryService::class.java).create()
        val service = controller.get()
        try {
            val notification = service.listeningNotification()
            assertNotEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            val open = shadowOf(notification.contentIntent)
            assertTrue(open.isActivity)
            assertTrue(open.isImmutable)
            assertEquals(ComponentName(app, SynchronizationPreferencesActivity::class.java), open.savedIntent.component)
            val stop = shadowOf(notification.actions.single().actionIntent)
            assertTrue(stop.isService)
            assertTrue(stop.isImmutable)
            assertEquals(ComponentName(app, LiveDeliveryService::class.java), stop.savedIntent.component)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop.savedIntent, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(LiveDeliveryService.state.value.running)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun deniedForegroundStartAndPlatformTimeoutReleaseTheServiceWithFeedback() {
        val controller = Robolectric.buildService(LiveDeliveryService::class.java).create()
        val service = controller.get()
        val shadow = shadowOf(service)
        try {
            shadow.setThrowInStartForeground(IllegalStateException("foreground quota exhausted"))
            val start = Intent(app, LiveDeliveryService::class.java)
                .setAction("net.kourlas.voipms_sms.START_LIVE_LISTENING")
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start, 0, 1))
            assertEquals(service.getString(R.string.live_start_declined), LiveDeliveryService.state.value.message)
            assertTrue(shadow.isStoppedBySelf)

            service.onTimeout(2, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertTrue(shadow.isForegroundStopped)
            assertFalse(LiveDeliveryService.state.value.running)
        } finally {
            controller.destroy()
        }
        assertEquals(app.getString(R.string.live_timeout), LiveDeliveryService.state.value.message)
    }
}
