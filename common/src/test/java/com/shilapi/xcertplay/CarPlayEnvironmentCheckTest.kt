package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test

class CarPlayEnvironmentCheckTest {
    private fun ready() = EnvironmentFacts(
        api = 28, device = "test head unit", codec = "video/avc", nativeLibraries = true,
        decoder = true, authentication = true, microphone = true, usbHost = true,
        appleDevices = 0, usbPermission = null, wifi = true, bluetooth = true,
        bluetoothPermission = true, bluetoothEnabled = true, phonePaired = true,
        locationPermission = true, nearbyWifiPermission = true, locationEnabled = true,
        hotspotMode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, automaticSupported = true, manualConfigured = false,
        systemHotspot = false, sessionPresent = false, sessionActive = false,
    )
    private fun report(facts: EnvironmentFacts = ready()) = CarPlayEnvironmentCheck.evaluate(facts, 1234)
    private fun EnvironmentReport.item(id: String) = items.single { it.id == id }

    @Test fun allStaticPrerequisitesNeverClaimTheIphoneAcceptedCarplay() {
        val report = report()
        assertEquals(EnvironmentState.PASS, report.item("local_identity").state)
        assertEquals(EnvironmentState.VERIFY, report.item("usb_handshake").state)
        assertEquals(EnvironmentState.VERIFY, report.item("wireless_handoff").state)
        assertEquals(EnvironmentState.VERIFY, report.item("playback").state)
        EnvironmentGroup.entries.forEach { assertEquals(EnvironmentState.VERIFY, report.state(it)) }
        assertFalse(report.items.any { it.state == EnvironmentState.ACTION })
    }
    @Test fun disconnectedPhoneIsPendingNotUnsupportedUsb() {
        val report = report()
        assertEquals(EnvironmentState.PASS, report.item("usb_host").state)
        assertEquals(EnvironmentState.VERIFY, report.item("usb_phone").state)
        assertFalse(report.items.any { it.id == "usb_permission" })
        assertEquals(EnvironmentState.VERIFY, report.item("vpn_consent").state)
    }
    @Test fun attachedDevicePermissionHasWiredRemedyWithoutBlockingWireless() {
        val report = report(ready().copy(appleDevices = 1, usbPermission = false))
        assertEquals(EnvironmentAction.USB, report.item("usb_permission").action)
        assertEquals(EnvironmentState.ACTION, report.state(EnvironmentGroup.USB))
        assertEquals(EnvironmentState.VERIFY, report.state(EnvironmentGroup.WIRELESS))
    }
    @Test fun unavailableOemApisCannotPass() {
        val report = report(ready().copy(bluetooth = null, systemHotspot = null, decoder = null))
        for (id in listOf("bluetooth", "hotspot_conflict", "decoder")) {
            assertEquals(EnvironmentState.VERIFY, report.item(id).state)
            assertEquals(R.string.env_unknown, report.item(id).detail)
        }
    }
    @Test fun activeHandoffDoesNotAskToReenableBluetoothOrCloseItsHotspot() {
        val report = report(ready().copy(sessionPresent = true, sessionActive = true, bluetoothEnabled = false, systemHotspot = true))
        assertEquals(EnvironmentState.VERIFY, report.item("bluetooth_power").state)
        assertNull(report.item("bluetooth_power").action)
        assertFalse(report.items.any { it.id == "hotspot_conflict" })
    }
    @Test fun idleBluetoothOffNeedsAction() {
        assertEquals(EnvironmentAction.BLUETOOTH, report(ready().copy(bluetoothEnabled = false)).item("bluetooth_power").action)
    }
    @Test fun lynkHotspotSwitchIsNotProofOfReachableAccessPoint() {
        val manual = ready().copy(hotspotMode = WirelessHotspotMode.MANUAL, manualConfigured = true, lynkProfile = true)
        assertEquals(EnvironmentState.VERIFY, report(manual.copy(systemHotspot = true)).item("hotspot_enabled").state)
        assertEquals(EnvironmentState.ACTION, report(manual.copy(systemHotspot = false)).item("hotspot_enabled").state)
        assertEquals(EnvironmentState.VERIFY, report(manual.copy(systemHotspot = null)).item("hotspot_enabled").state)
        assertEquals(EnvironmentState.PASS, report(manual.copy(systemHotspot = true, lynkProfile = false)).item("hotspot_enabled").state)
    }

    @Test fun manualHotspotDoesNotRequireLocationSwitchOrAutomaticApi() {
        val report = report(ready().copy(hotspotMode = WirelessHotspotMode.MANUAL, manualConfigured = true,
            locationEnabled = false, automaticSupported = false, systemHotspot = true))
        assertFalse(report.items.any { it.id == "location_switch" || it.id == "hotspot_api" })
        assertEquals(EnvironmentState.PASS, report.item("hotspot_config").state)
        assertEquals(EnvironmentState.VERIFY, report.state(EnvironmentGroup.WIRELESS))
    }
    @Test fun lynkApi28ManualHotspotDoesNotReportMissingLocationAsAConnectionBlocker() {
        val report = report(ready().copy(hotspotMode = WirelessHotspotMode.MANUAL, manualConfigured = true,
            lynkProfile = true, locationPermission = false, systemHotspot = true))
        assertFalse(report.items.any { it.id == "location_permission" })
        assertEquals(EnvironmentState.VERIFY, report.state(EnvironmentGroup.WIRELESS))
    }
    @Test fun locationExemptionIsLimitedToLynkApi28ManualWithoutGpsReporting() {
        val manual = ready().copy(hotspotMode = WirelessHotspotMode.MANUAL, lynkProfile = true,
            locationPermission = false)
        for (facts in listOf(manual.copy(lynkProfile = false), manual.copy(api = 29),
            manual.copy(hotspotMode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT),
            manual.copy(hotspotMode = WirelessHotspotMode.WIFI_P2P),
            manual.copy(hotspotMode = WirelessHotspotMode.EXISTING_WIFI),
            manual.copy(locationReportingEnabled = true))) {
            assertEquals(EnvironmentState.ACTION, report(facts).item("location_permission").state)
        }
    }
    @Test fun automaticHotspotDoesNotRequireManuallyEnteredCredentials() {
        val report = report()
        assertFalse(report.items.any { it.id == "hotspot_config" })
        assertEquals(EnvironmentState.PASS, report.item("hotspot_api").state)
    }
    @Test fun locationMissingAndApConflictHaveSeparateRemedies() {
        val report = report(ready().copy(locationEnabled = false, systemHotspot = true))
        assertEquals(EnvironmentAction.LOCATION, report.item("location_switch").action)
        assertEquals(EnvironmentAction.CONNECTION, report.item("hotspot_conflict").action)
    }
    @Test fun newerAndroidChecksNearbyWifiInsteadOfLegacyLocationPermission() {
        for (reporting in listOf(false, true)) {
            val report = report(ready().copy(api = 33, locationPermission = false,
                nearbyWifiPermission = false, locationReportingEnabled = reporting))
            assertFalse(report.items.any { it.id == "location_permission" })
            assertEquals(EnvironmentState.ACTION, report.item("nearby_wifi_permission").state)
        }
    }
}
