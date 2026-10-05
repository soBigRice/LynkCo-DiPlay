package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LocalHotspotRadioPolicyTest {
    @Test fun android9TwoPointFourUsesMeasuredChannel() {
        assertEquals(6, LocalHotspotRadioPolicy.channel("2.4 GHz", 0, 2437, true))
        assertEquals(11, LocalHotspotRadioPolicy.channel("2.4 GHz", 11, null, true))
    }
    @Test fun missingOrIncompatibleChannelNeverFallsBackToThirtySix() {
        for (channel in listOf(0, 36)) assertThrows(IOException::class.java) {
            LocalHotspotRadioPolicy.channel("2.4 GHz", channel, null, true)
        }
        assertThrows(IOException::class.java) { LocalHotspotRadioPolicy.channel("5 GHz", 0, null, true) }
    }
    @Test fun nonLynkProfileStillRequiresFiveGhz() {
        assertThrows(IOException::class.java) { LocalHotspotRadioPolicy.channel("2.4 GHz", 6, 2437, false) }
        assertEquals(149, LocalHotspotRadioPolicy.channel("5 GHz", 36, 5745, false))
    }
    @Test fun unrecognizedFrequencyCannotBorrowConfiguredChannel() {
        assertThrows(IOException::class.java) { LocalHotspotRadioPolicy.channel("2.4 GHz", 6, 2420, true) }
    }
}
