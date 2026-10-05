package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [WirelessAutomaticIdentityFlowTest.HotspotOn::class])
class WirelessAutomaticIdentityFlowTest {
    @Implements(WifiManager::class)
    class HotspotOn : ShadowWifiManager() {
        @Implementation fun getWifiApState(): Int = 13
    }

    @Test fun pairedPhoneAndReadyCarHotspotOpenProjectionWithoutManualMac() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = controller.get()
            // Local auth loading is outside this UI boundary; packaged assets are verified separately.
            DiPlayActivity::class.java.getDeclaredField("setupError").apply { isAccessible = true }.set(activity, null)
            AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
            AirPlayPersistence.saveManualHotspotSsid(activity, "test car")
            AirPlayPersistence.saveManualHotspotPassphrase(activity, "secret123")
            val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setEnabled(true)
            shadowOf(adapter).setAddress("02:00:00:00:00:00")
            android.provider.Settings.Secure.putString(activity.contentResolver, "bluetooth_address", null)
            val phone = adapter.getRemoteDevice("AA:22:33:44:55:66")
            shadowOf(adapter).setBondedDevices(setOf(phone))
            activity.getSharedPreferences("diplay", android.content.Context.MODE_PRIVATE).edit()
                .putString("phone_address", phone.address).putString("phone_name", "iPhone").commit()
            assertNull(AirPlayPersistence.loadHeadUnitBluetoothAddress(activity))
            DiPlayActivity::class.java.getDeclaredMethod("connect", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, true)
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(activity).nextStartedActivity?.component?.className)
            assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
        } finally {
            controller.destroy()
        }
    }
}
