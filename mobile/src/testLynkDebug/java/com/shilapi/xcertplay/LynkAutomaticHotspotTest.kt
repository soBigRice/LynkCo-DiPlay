package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
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
class LynkAutomaticHotspotTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun audioFocusDefaultsOnButPreservesAnExplicitChoice() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(AirPlayPersistence.loadAudioFocusEnabled(context))
        AirPlayPersistence.saveAudioFocusEnabled(context, false)
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(context))
    }

    @Test fun diagnosticPeekDoesNotRunTheAutomaticModeMigration() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("wireless_hotspot_mode", "MANUAL").commit()
        val before = prefs.all.toMap()
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.peekWirelessHotspotMode(context))
        assertEquals(before, prefs.all)
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertTrue(prefs.getBoolean("lynk_auto_hotspot_v1", false))
    }

    @Test fun upgradeAdoptsAutomaticOnceAndKeepsBackupCredentials() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("wireless_hotspot_mode", "MANUAL").commit()
        AirPlayPersistence.saveManualHotspotSsid(context, "Saved car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test12345")
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("Saved car", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("test12345", AirPlayPersistence.loadManualHotspotPassphrase(context))
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        repeat(2) { assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context)) }
    }

    @Test fun automaticConnectionDoesNotAskForHotspotCredentials() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = owner.get()
            DiPlayActivity::class.java.getDeclaredField("setupError").apply { isAccessible = true }.set(activity, null)
            assertTrue(AirPlayPersistence.loadManualHotspotSsid(activity).isEmpty())
            val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setEnabled(true)
            val phone = adapter.getRemoteDevice("AA:22:33:44:55:66")
            shadowOf(adapter).setBondedDevices(setOf(phone))
            activity.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
                .putString("phone_address", phone.address).putString("phone_name", "iPhone").commit()
            DiPlayActivity::class.java.getDeclaredMethod("connect", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, true)
            assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(activity))
        } finally { owner.destroy() }
    }
}
