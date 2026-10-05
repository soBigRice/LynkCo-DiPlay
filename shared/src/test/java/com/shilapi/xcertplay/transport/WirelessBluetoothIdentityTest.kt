package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class WirelessBluetoothIdentityTest {
    private val address = "10:22:33:44:55:66"
    private val generated = "02:22:33:44:55:66"

    @Test fun lynkUsesSavedRealAddressInsteadOfSyntheticFallback() {
        val result = WirelessBluetoothIdentity.resolve({ "02:00:00:00:00:00" }, { null }, generated,
            saved = " 10-22-33-44-55-66 ")
        assertEquals(address, result.address)
        assertEquals("MANUAL", result.source)
        assertFalse(result.diagnosticSummary(true).contains(address))
    }

    @Test fun hiddenSystemAddressUsesStableConfigurationWithoutBlockingStartup() {
        val result = WirelessBluetoothIdentity.resolve({ "02:00:00:00:00:00" }, { null }, generated)
        assertEquals(generated, result.address)
        assertEquals("CONFIG_FALLBACK", result.source)
    }

    @Test fun systemAddressTakesPrecedenceOverSavedValue() {
        val result = WirelessBluetoothIdentity.resolve({ address }, { null }, generated,
            saved = "AA:22:33:44:55:66")
        assertEquals(address, result.address)
        assertEquals("ADAPTER", result.source)
    }

    @Test fun invalidSavedAddressFallsBackToStableConfiguration() {
        for (invalid in listOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "AA:BB", "not a MAC")) {
            assertNull(HeadUnitBluetoothAddress.normalize(invalid))
            assertEquals(generated, WirelessBluetoothIdentity.resolve({ null }, { null }, generated, invalid).address)
        }
    }

    @Test fun usesAdapterAndNeverExportsRawAddresses() {
        val result = WirelessBluetoothIdentity.resolve({ address }, { null }, generated)
        assertEquals(address, result.address)
        assertEquals("ADAPTER", result.source)
        assertFalse(result.diagnosticSummary(true).contains(address))
        assertTrue(result.diagnosticSummary(true).contains("configDerived=false"))
    }

    @Test fun placeholderAdapterUsesRealSettingsAddress() {
        val result = WirelessBluetoothIdentity.resolve({ "02:00:00:00:00:00" }, { address }, generated)
        assertEquals(address, result.address)
        assertEquals("SETTINGS", result.source)
        assertEquals("placeholder", result.adapterState)
    }

    @Test fun distinguishesPermissionDenialAndGeneratedFallbackWithoutGuessingARealAddress() {
        val result = WirelessBluetoothIdentity.resolve({ throw SecurityException("private details") }, { null }, generated)
        assertEquals(generated, result.address)
        assertEquals("CONFIG_FALLBACK", result.source)
        assertEquals("permission-denied", result.adapterState)
        assertTrue(result.diagnosticSummary(true).contains("configDerived=true"))
        assertFalse(result.diagnosticSummary(true).contains("private details"))
    }

    @Test fun invalidOrUnavailableReadsCannotMasqueradeAsHardwareIdentity() {
        for (value in listOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "not-an-address", null)) {
            val result = WirelessBluetoothIdentity.resolve({ value }, { throw IllegalStateException() }, generated)
            assertEquals("CONFIG_FALLBACK", result.source)
            assertEquals("read-failed", result.settingsState)
        }
    }
}
