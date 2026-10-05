package com.shilapi.xcertplay

import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionGuideTest {
    @Test fun chineseStatesDistinguishHotspotBluetoothAndHandshake() {
        val app = RuntimeEnvironment.getApplication()
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        fun title(status: CarPlayStatus) = context.getString(ConnectionGuide.state(status, true).title)
        assertEquals("1 / 3 · 检查车机热点", title(CarPlayStatus.StartingHotspot))
        assertEquals("2 / 3 · 连接 iPhone 蓝牙通道", title(CarPlayStatus.ConnectingBluetooth))
        assertEquals("等待 iPhone 识别车机", title(CarPlayStatus.RunningWireless))
        assertFalse(title(CarPlayStatus.MfiReady).contains("认证通过"))
        assertFalse(title(CarPlayStatus.WirelessActive).contains("已连接"))
    }

    @Test fun unusableHotspotWaitsForConfigurationInsteadOfRetryingForever() {
        val state = ConnectionGuide.state(CarPlayStatus.Failed(
            "Could not establish MANUAL hotspot: hotspot interface unavailable"), true)
        assertEquals(R.string.link_hotspot_failed, state.title)
        assertEquals(ConnectionGuide.Action.HOTSPOT, state.action)
        assertTrue(state.pauseRetry)
    }

    @Test fun automaticHotspotGuidanceNeverAsksToCopyItsPassword() {
        assertEquals(R.string.auto_hotspot_creating, ConnectionGuide.state(CarPlayStatus.StartingHotspot, true, true).detail)
        assertEquals(R.string.auto_hotspot_sent, ConnectionGuide.state(CarPlayStatus.HandshakeProgress(
            com.shilapi.xcertplay.transport.Iap2HandshakeStage.WIFI_CREDENTIALS_SENT), true, true).detail)
        assertEquals(R.string.auto_hotspot_conflict, ConnectionGuide.failure("LocalOnlyHotspot failed: incompatible mode").detail)
        assertEquals(R.string.auto_hotspot_location, ConnectionGuide.failure("Location mode is not enabled for LocalOnlyHotspot").detail)
        assertTrue(ConnectionGuide.failure("Bluetooth restore pending").pauseRetry)
    }

    @Test fun permissionAndAuthenticationFailuresHaveDifferentRemedies() {
        val permission = ConnectionGuide.failure("Bluetooth permission denied")
        val auth = ConnectionGuide.failure("MFi certificate could not be loaded")
        assertEquals(ConnectionGuide.Action.PERMISSIONS, permission.action)
        assertEquals(R.string.link_auth_failed, auth.title)
        assertTrue(permission.pauseRetry)
        assertTrue(auth.pauseRetry)
    }

    @Test fun transientBluetoothFailureKeepsAutomaticRecovery() {
        val state = ConnectionGuide.failure("RFCOMM socket connection timed out")
        assertEquals(ConnectionGuide.Action.BLUETOOTH, state.action)
        assertFalse(state.pauseRetry)
    }

    @Test fun usbPermissionDenialRequestsDeviceAccessInsteadOfLocationSettings() {
        val state = ConnectionGuide.failure("iPhone USB permission was denied")
        assertEquals(R.string.link_usb_permission_fix, state.detail)
        assertEquals(ConnectionGuide.Action.RETRY, state.action)
        assertTrue(state.pauseRetry)
    }

    @Test fun wirelessHandshakeDoesNotAskForAUsbCable() {
        assertEquals(R.string.link_identifying_hint, ConnectionGuide.state(CarPlayStatus.RunningWireless, true).detail)
        assertEquals(R.string.link_usb_hint, ConnectionGuide.state(CarPlayStatus.WaitingForIphone, false).detail)
        assertEquals(R.string.link_usb_hint, ConnectionGuide.state(CarPlayStatus.ControlEnded, false).detail)
        assertEquals(R.string.link_usb_hint, ConnectionGuide.state(CarPlayStatus.Failed("Transport interrupted"), false).detail)
    }

    @Test fun phoneTrustDenialDoesNotSendTheUserToAndroidLocationSettings() {
        for (message in listOf("The user denied Lockdown pairing", "PasswordProtected")) {
            val state = ConnectionGuide.failure(message)
            assertEquals(R.string.link_usb_trust_hint, state.detail)
            assertEquals(ConnectionGuide.Action.RETRY, state.action)
            assertTrue(state.pauseRetry)
        }
    }

    @Test fun aTimeoutRetainsTheExactLastMilestoneAndStopsAutomaticRetry() {
        for (stage in com.shilapi.xcertplay.transport.Iap2HandshakeStage.entries) {
            val progress = ConnectionGuide.state(CarPlayStatus.HandshakeProgress(stage), true)
            val failure = ConnectionGuide.state(CarPlayStatus.HandshakeTimedOut(stage), true)
            assertEquals(progress.title, failure.title)
            assertTrue(failure.pauseRetry)
            assertEquals(ConnectionGuide.Action.RETRY, failure.action)
        }
    }

    @Test fun usbDiscoveryAuthorizationTrustAndAuthenticationRemainDistinct() {
        val phases = listOf(CarPlayStatus.WaitingForIphone, CarPlayStatus.RequestingIphonePermission,
            CarPlayStatus.WaitingForReenumeration, CarPlayStatus.OpeningDataPaths, CarPlayStatus.Pairing,
            CarPlayStatus.ConnectingControl, CarPlayStatus.HandshakeProgress(
                com.shilapi.xcertplay.transport.Iap2HandshakeStage.AUTHENTICATING))
        assertEquals(phases.size, phases.map { ConnectionGuide.state(it, false).title }.toSet().size)
    }

    @Test fun rejectedUsbConfigurationPausesRetryAndDoesNotAskForAndroidPermissions() {
        for (message in listOf(
            "USB configuration could not be verified: requested=6 set=false active=2 error=EBUSY",
            "USB configuration could not be verified: requested=6 set=false active=2 error=EACCES",
            "USB configuration changed before NCM: expected=6 active=2")) {
            val state = ConnectionGuide.state(CarPlayStatus.Failed(message), false)
            assertEquals(R.string.link_usb_mode_failed, state.title)
            assertEquals(R.string.link_usb_mode_blocked_hint, state.detail)
            assertEquals(ConnectionGuide.Action.RETRY, state.action)
            assertTrue(state.pauseRetry)
        }
    }

}
