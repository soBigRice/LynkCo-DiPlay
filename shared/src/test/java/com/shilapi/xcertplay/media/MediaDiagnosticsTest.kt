package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MediaDiagnosticsTest {
    @Test fun pcmSeparatesSilenceFromActualSignalWithoutRecordingSamples() {
        val stats = PcmSignalStats()
        stats.observe(byteArrayOf(0, 0, 1, 0, 0, -128), 0, 6)
        assertEquals("pcmSamples=3 pcmNonzero=2 pcmPeak=32768", stats.take())
        assertEquals("pcmSamples=0 pcmNonzero=0 pcmPeak=0", stats.take())
    }
    @Test fun staticScreenDoesNotProduceTwentySecondTouchLatency() {
        TouchLatencyProbe.onFrame(Long.MAX_VALUE)
        TouchLatencyProbe.onTouchSent(1000L, 10L)
        assertEquals(-1L, TouchLatencyProbe.onFrame(23_000_001_000L))
        TouchLatencyProbe.onTouchSent(24_000_000_000L, 10L)
        assertEquals(10_000_000L, TouchLatencyProbe.onFrame(24_010_000_000L))
        assertEquals(-1L, TouchLatencyProbe.onFrame(24_020_000_000L))
    }
}
