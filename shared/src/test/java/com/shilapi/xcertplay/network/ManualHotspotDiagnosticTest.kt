package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManualHotspotDiagnosticTest {
    @Test fun configurationMismatchRecordsSafeReasonBeforeThePrivateErrorIsRedacted() {
        val context = RuntimeEnvironment.getApplication()
        val wifi = context.getSystemService(WifiManager::class.java)
        val live = WifiConfiguration().apply { SSID = "private-live-network" }
        WifiManager::class.java.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(wifi, live)
        val lines = mutableListOf<String>()
        val manager = ManualHotspotManager(context, "private-saved-network", "", ManualHotspotBand.AUTO,
            0, ManualHotspotSecurity.OPEN, lines::add)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val error = executor.submit<Throwable?> { try { manager.start(500); null } catch (e: Exception) { e } }
                .get(2, TimeUnit.SECONDS)
            assertTrue(error is WirelessStartupException)
            assertTrue(lines.any { it.contains("networkMatches=false") })
            assertTrue(lines.contains("Manual hotspot rejected reason=NETWORK_NAME_MISMATCH"))
            assertFalse(lines.joinToString().contains("private-live-network"))
            assertFalse(lines.joinToString().contains("private-saved-network"))
        } finally { manager.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}
