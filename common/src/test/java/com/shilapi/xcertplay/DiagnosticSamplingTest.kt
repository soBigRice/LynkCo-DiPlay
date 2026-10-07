package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSamplingTest {
    @Test fun focusChangesAreRateLimitedAndBoundedForOneSession() {
        val samples = DiagnosticSampleBudget(limit = 2, intervalMs = 2_000)
        assertTrue(samples.take(0))
        assertFalse(samples.take(10))
        assertFalse(samples.take(1_999))
        assertTrue(samples.take(2_000))
        assertFalse(samples.take(20_000))
        assertTrue(DiagnosticSampleBudget().take(20_000))
    }
    @Test fun waitMilestonesDoNotSpamOrReplayBacklogAfterBackgrounding() {
        val samples = ConnectionWaitSamples()
        assertNull(samples.due(4))
        assertEquals(5L, samples.due(5))
        assertNull(samples.due(14))
        assertEquals(30L, samples.due(35))
        assertNull(samples.due(36))
        assertEquals(60L, samples.due(90))
        assertNull(samples.due(3_600))
        samples.reset()
        assertNull(samples.due(0))
        assertEquals(5L, samples.due(5))
    }
    @Test fun newDiagnosticFieldsSurviveExportWithoutRelaxingPrivacyFilters() {
        for (line in listOf(
            "Manual hotspot rejected reason=NETWORK_NAME_MISMATCH",
            "Manual hotspot configuration readable=true networkMatches=false observedChannel=6 expectedChannel=0",
            "Audio: focus state point=request requestResult=2 pending=true appVolumeRequested=0.0 requestAgeMs=400",
            "Audio playback visibility=platform_filtered usageCounts={1=2}",
            "CONNECTION_DIAGNOSTIC DISPLAY_STATE point=waiting_15s headUnitLocked=false phoneLockState=not_observable surfaceValid=true",
            "Video: first frame presented stream=110 surfaceValid=true atNs=123456789",
        )) {
            assertEquals(line, DiagnosticRedactor.redact(line))
            if (line.contains("DISPLAY_STATE")) assertTrue(ConnectionEnvironmentSnapshot.isConnectionEvent(line))
        }
        assertNull(DiagnosticRedactor.redact("Manual hotspot SSID does not match: private-network"))
        assertNull(DiagnosticRedactor.redact("password=private-secret"))
    }
}
