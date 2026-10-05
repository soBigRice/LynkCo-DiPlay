package com.shilapi.xcertplay

import android.app.Notification
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LynkOsNProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun isolatedPackageDisablesVendorOutputsEvenWithSavedSwitches() {
        assertEquals("com.shihab.diplay.lynk", context.packageName)
        assertEquals("LYNK & CO", AirPlayPersistence.loadOemLabel(context))
        BydOutputSettings.setEnabled(context, true)
        BydOutputSettings.setBatteryToIphone(context, true)
        BydOutputSettings.setWheelSpeedToIphone(context, true)
        BydOutputSettings.setVideoWhileParked(context, true)
        BydOutputSettings.setClusterSong(context, true)
        BydOutputSettings.setClusterStreamPause(context, true)
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        assertFalse(BydOutputSettings.integrationAllowed(context))
        assertFalse(BydOutputSettings.available(context))
        assertFalse(BydOutputSettings.enabled(context))
        assertFalse(BydOutputSettings.batteryToIphone(context))
        assertFalse(BydOutputSettings.wheelSpeedToIphone(context))
        assertFalse(BydOutputSettings.videoWhileParked(context))
        assertFalse(BydOutputSettings.clusterSong(context))
        assertFalse(BydOutputSettings.clusterStreamPause(context))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(context))
        assertTrue(context.resources.getBoolean(com.shilapi.xcertplay.shared.R.bool.config_manual_hotspot_strict_interface))
        assertTrue(context.resources.getBoolean(com.shilapi.xcertplay.shared.R.bool.config_manual_hotspot_prefer_ipv4))
        assertTrue(context.resources.getBoolean(com.shilapi.xcertplay.shared.R.bool.config_verified_usb_configuration))
        assertTrue(context.resources.getBoolean(com.shilapi.xcertplay.shared.R.bool.config_release_iphone_usb_drivers))
        assertTrue(context.resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_simple_connection_flow))
    }

    @Test fun android9UsesAutomaticHotspotWithoutChangingVideoQuality() {
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals(10, AirPlayPersistence.loadDisplayScaleTenths(context))
        assertFalse(AirPlayPersistence.loadHevcEnabled(context))
    }

    @Test fun foregroundConnectionServiceStartsOnAndroid9() {
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        try {
            controller.startCommand(0, 1)
            val notification = shadowOf(controller.get()).lastForegroundNotification
            assertNotNull(notification)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            controller.destroy()
        }
    }
}
