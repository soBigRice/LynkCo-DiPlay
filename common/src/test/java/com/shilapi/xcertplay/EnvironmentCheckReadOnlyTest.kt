package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [EnvironmentCheckReadOnlyTest.VpnGuard::class])
class EnvironmentCheckReadOnlyTest {
    @Implements(VpnService::class)
    class VpnGuard {
        companion object {
            var calls = 0
            @JvmStatic @Implementation fun prepare(context: Context): Intent? {
                calls++; throw IllegalStateException("Read-only checks must not prepare/revoke VPNs")
            }
        }
    }
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun scanDoesNotMigratePreferencesOrPrepareVpn() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("wireless_hotspot_mode", "MANUAL").commit()
        val before = prefs.all.toMap()
        VpnGuard.calls = 0
        val report = CarPlayEnvironmentCheck.capture(app)
        assertEquals(before, prefs.all)
        assertEquals(0, VpnGuard.calls)
        assertEquals(EnvironmentState.VERIFY, report.items.single { it.id == "vpn_consent" }.state)
        assertFalse(report.facts.automaticHotspot)
    }

    @Test fun validOpenHotspotIsAcceptedButEmbeddedNullIsRejected() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "")
        assertEquals(true, CarPlayEnvironmentCheck.capture(app).facts.manualConfigured)
        AirPlayPersistence.saveManualHotspotSsid(app, "Bad\u0000name")
        assertEquals(false, CarPlayEnvironmentCheck.capture(app).facts.manualConfigured)
    }

    @Test fun reportNeverContainsSavedPasswordsOrPhoneIdentifiers() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Private car name")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "DoNotExportMe123")
        DiPlayPreferences.savePhone(app, "AA:22:33:44:55:66", "Private phone")
        val text = CarPlayEnvironmentCheck.capture(app).diagnosticText(app)
        for (secret in listOf("Private car name", "DoNotExportMe123", "AA:22:33:44:55:66", "Private phone")) assertFalse(text.contains(secret))
    }
}
