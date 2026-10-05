package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarPlayBonjourTest {
    @Test fun discoveryDiagnosticsKeepOutcomeWithoutPhoneIdentifiers() {
        val endpoint = CarPlayBonjourEndpoint("Private phone", "192.168.43.25", 7000, "AA:BB:CC:DD:EE:FF")
        assertEquals("control resolved family=IPv4 port=7000", CarPlayBonjourEvent.Resolved(endpoint).diagnosticSummary())
        assertEquals("control probe attempts=2 status=200 error=none",
            CarPlayBonjourEvent.Probed(endpoint, 2, "HTTP/1.1 200 Private phone", null).diagnosticSummary())
        val failed = CarPlayBonjourEvent.Probed(endpoint, 3, null, java.io.IOException("Private phone 192.168.43.25"))
            .diagnosticSummary()
        assertEquals("control probe attempts=3 status=none error=IOException", failed)
        assertFalse(failed.contains("Private phone"))
        assertEquals("control failure stage=INTERFACE_SERVICE error=IOException",
            CarPlayBonjourEvent.Failure(CarPlayBonjourEvent.Failure.Stage.INTERFACE_SERVICE, "IOException").diagnosticSummary())
    }
    private val config = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        model = "LIVI",
    )
    private val identity = AirPlayIdentity(
        privateKey = ByteArray(32),
        publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
        pairingId = "pairing-1",
    )

    @Test
    fun airPlayTxtRecordsMatchLivi() {
        assertEquals(
            linkedMapOf(
                "deviceid" to "02:00:00:00:00:02",
                "features" to "0x44540380,0x61",
                "flags" to "0x4",
                "model" to "LIVI",
                "srcvers" to "366.0",
                "protovers" to "1.1",
                "pi" to "pairing-1",
                "pk" to "0123ab",
            ),
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
        )
    }

    @Test fun receiverDeviceIdUsesIndependentDecimalMacVectors() {
        // CatPlay cp_ctrl_invite AirPlayMacId vectors, not the production encoder.
        for ((mac, expected) in listOf(
            "AA:BB:CC:DD:EE:FF" to "187723572702975",
            "00:11:22:33:44:55" to "73588229205",
            "aabbccddeeff" to "187723572702975",
            "00:00:00:00:00:00" to "0",
            "FF:FF:FF:FF:FF:FF" to "281474976710655",
        )) {
            val request = CarPlayBonjourProtocol.connectProbeRequest("192.168.2.2", 7000, "366.0", mac)
            assertEquals("AirPlay-Receiver-Device-ID: $expected", request.lineSequence().first { it.startsWith("AirPlay-Receiver") })
        }
    }

    @Test fun invitationRejectsMalformedOrOversizedMacIdentifiers() {
        for (invalid in listOf("", "AA:BB", "AA:BB:CC:DD:EE:GG", "FFFFFFFFFFFFFF", ":AABBCCDDEEFF", "00:11:22:33:44:55\r\nInjected: yes")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                CarPlayBonjourProtocol.connectProbeRequest("192.168.2.2", 7000, "366.0", invalid)
            }
        }
    }

    @Test
    fun connectProbeRequestMatchesExactRequestLineAndHeaders() {
        assertEquals(
            "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
                "Host: [fe80::1]:7000\r\n" +
                "User-Agent: AirPlay/366.0\r\n" +
                "AirPlay-Receiver-Device-ID: 2199023255554\r\n" +
                "Connection: close\r\n" +
                "\r\n",
            CarPlayBonjourProtocol.connectProbeRequest(
                host = "fe80::1%wlan0",
                port = 7000,
                sourceVersion = "366.0",
                deviceId = "02:00:00:00:00:02",
            ),
        )
    }
}
