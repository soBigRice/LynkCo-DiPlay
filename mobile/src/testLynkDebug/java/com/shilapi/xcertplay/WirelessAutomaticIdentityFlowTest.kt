package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothManager
import android.net.wifi.WifiManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager
import java.util.concurrent.ExecutorService

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [WirelessAutomaticIdentityFlowTest.HotspotOn::class])
class WirelessAutomaticIdentityFlowTest {
    @Implements(WifiManager::class)
    class HotspotOn : ShadowWifiManager() {
        @Implementation fun getWifiApState(): Int = 13
    }

    @Test fun lynkApi28SystemHotspotCanStartWithoutLocationPermission() = withHost { activity ->
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(activity.resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_simple_connection_flow))
        field(activity, "wirelessEnabled").set(activity, true)
        field(activity, "wirelessHotspotMode").set(activity, WirelessHotspotMode.MANUAL)
        assertTrue(permissions(activity).isEmpty())
        assertEquals(true, invoke(activity, "hasRequiredWirelessPermissions"))
        invoke(activity, "requestStartupPrerequisites")
        assertEquals(true, field(activity, "wirelessPermissionsReady").get(activity))
        assertEquals(false, field(activity, "awaitingWirelessPermissions").get(activity))
    }

    @Test fun automaticAndP2pHotspotsStillRequireLocationPermission() = withHost { activity ->
        for (mode in listOf(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.WIFI_P2P)) {
            field(activity, "wirelessHotspotMode").set(activity, mode)
            assertTrue(permissions(activity).contains(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue(permissions(activity).contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    @Test @Config(sdk = [29])
    fun api29ManualHotspotRetainsItsLocationPermissionRequirement() = withHost { activity ->
        field(activity, "wirelessHotspotMode").set(activity, WirelessHotspotMode.MANUAL)
        assertTrue(permissions(activity).contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test fun manualHotspotDoesNotSkipIndependentLocationReportingConsent() = withHost { activity ->
        val requests = mutableListOf<Array<String>>()
        field(activity, "locationPermission").set(activity, object : ActivityResultLauncher<Array<String>>() {
            override fun launch(input: Array<String>, options: ActivityOptionsCompat?) { requests += input }
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.RequestMultiplePermissions()
        })
        field(activity, "wirelessEnabled").set(activity, true)
        field(activity, "wirelessHotspotMode").set(activity, WirelessHotspotMode.MANUAL)
        field(activity, "locationReportingEnabled").set(activity, true)
        field(activity, "locationPermissionAvailable").set(activity, false)
        invoke(activity, "requestStartupPrerequisites")
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            requests.single())
        assertEquals(true, field(activity, "awaitingLocationPermission").get(activity))
        assertEquals(false, field(activity, "wirelessPermissionsReady").get(activity))
    }

    private fun field(activity: CarPlayHostActivity, name: String) =
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(activity: CarPlayHostActivity, name: String): Any? =
        CarPlayHostActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun permissions(activity: CarPlayHostActivity) = invoke(activity, "requiredWirelessPermissions") as List<*>
    private fun withHost(block: (CarPlayHostActivity) -> Unit) {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try { block(activity) } finally {
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                (field(activity, name).get(activity) as ExecutorService).shutdownNow()
        }
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
