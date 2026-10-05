package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeadUnitBluetoothSettingsTest {
    @Test fun addressIsNormalizedAndSavedWithoutOverwritingPhoneSelection() {
        val context = RuntimeEnvironment.getApplication()
        DiPlayPreferences.savePhone(context, "AA:22:33:44:55:66", "iPhone")
        AirPlayPersistence.saveHeadUnitBluetoothAddress(context, " 10-22-33-44-55-66 ")
        assertEquals("10:22:33:44:55:66", AirPlayPersistence.loadHeadUnitBluetoothAddress(context))
        assertEquals("AA:22:33:44:55:66", DiPlayPreferences.phoneAddress(context))
    }

    @Test fun invalidAddressDoesNotReplaceSavedIdentity() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveHeadUnitBluetoothAddress(context, "10:22:33:44:55:66")
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayPersistence.saveHeadUnitBluetoothAddress(context, "02:00:00:00:00:00")
        }
        assertEquals("10:22:33:44:55:66", AirPlayPersistence.loadHeadUnitBluetoothAddress(context))
    }

    @Test fun blankAddressRestoresAutomaticSelectionWithoutChangingPhoneOrHotspot() {
        val context = RuntimeEnvironment.getApplication()
        DiPlayPreferences.savePhone(context, "AA:22:33:44:55:66", "iPhone")
        AirPlayPersistence.saveManualHotspotSsid(context, "test car")
        AirPlayPersistence.saveHeadUnitBluetoothAddress(context, "10:22:33:44:55:66")
        AirPlayPersistence.saveHeadUnitBluetoothAddress(context, "  ")
        assertNull(AirPlayPersistence.loadHeadUnitBluetoothAddress(context))
        assertEquals("AA:22:33:44:55:66", DiPlayPreferences.phoneAddress(context))
        assertEquals("test car", AirPlayPersistence.loadManualHotspotSsid(context))
    }

    @Test fun lynkAutomaticIdentityUsesTheSameExactPlaceholderRuleAsItsController() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter)
            .setAddress("02:00:00:00:00:01")
        AirPlayPersistence.saveHeadUnitBluetoothAddress(context, "10:22:33:44:55:66")
        assertEquals("02:00:00:00:00:01", DiPlayBluetooth.configuredHeadUnitAddress(context))
        assertNull(DiPlayBluetooth.localAddress(context)) // Keep the original profile's behavior.
    }
}
