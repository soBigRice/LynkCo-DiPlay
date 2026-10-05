package com.shilapi.xcertplay

import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionDiagnosticReportTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun oneFileContainsBothTransportsAfterModeSwitchAndFlushesTheLastQueuedError() {
        val wireless = SessionLogFile(File(context.filesDir, "logs/connection-wireless/diplay.log"))
        wireless.reset("Connection capture transport=wireless")
        wireless.append("IAP2_META RX id=0xaa00 bytes=0")
        val tcpArrival = "CONNECTION_DIAGNOSTIC AirPlay TCP accepted family=IPv4"
        assertTrue(ConnectionEnvironmentSnapshot.isConnectionEvent(tcpArrival))
        wireless.append(tcpArrival)
        val bonjour = "CONNECTION_DIAGNOSTIC Bonjour control discovery stage=ADDED ipv4=0 ipv6=0"
        assertTrue(ConnectionEnvironmentSnapshot.isConnectionEvent(bonjour))
        AsyncDiagnosticLog.append(wireless, bonjour)
        val identity = "CONNECTION_DIAGNOSTIC Bluetooth identity source=CONFIG_FALLBACK adapter=placeholder settings=unavailable configDerived=true"
        wireless.append(identity)
        val manualIdentity = "CONNECTION_DIAGNOSTIC Bluetooth identity source=MANUAL adapter=placeholder settings=unavailable configDerived=false"
        wireless.append(manualIdentity)
        val availability = "CONNECTION_DIAGNOSTIC iap2 availability wired=false wireless=false wiredTransportPresent=false radioTransportPresent=false"
        assertTrue(ConnectionEnvironmentSnapshot.isConnectionEvent(availability))
        wireless.append(availability)
        wireless.append("password=must-not-export")
        wireless.append("certificate=must-not-export")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        wireless.close()
        val usb = SessionLogFile(File(context.filesDir, "logs/connection-usb/diplay.log"))
        usb.reset("Connection capture transport=usb")
        AsyncDiagnosticLog.append(usb, "CONNECTION_DIAGNOSTIC failureClass=UserDeniedPairing")
        AsyncDiagnosticLog.append(usb, "CONNECTION_DIAGNOSTIC USB configuration set=6 result=false active=4")
        val driver = "CONNECTION_DIAGNOSTIC USB driver config=2 iface=0 class=1 result=snd-usb-audio"
        AsyncDiagnosticLog.append(usb, driver)
        val recovery = "CONNECTION_DIAGNOSTIC USB handoff iface=0 driver=snd-usb-audio errno=0"
        val restored = "CONNECTION_DIAGNOSTIC USB restore outcome=replug-required"
        AsyncDiagnosticLog.append(usb, recovery)
        AsyncDiagnosticLog.append(usb, restored)
        val mediaEvents = listOf(
            "CONNECTION_DIAGNOSTIC media-key source=KEYCODE_MEDIA_PLAY index=1 closed=false session=true",
            "CONNECTION_DIAGNOSTIC media-hid index=1 pressSent=true releaseSent=true",
            "CONNECTION_DIAGNOSTIC playback playing=false",
        )
        mediaEvents.forEach { AsyncDiagnosticLog.append(usb, it) }
        AirPlayPersistence.saveWirelessEnabled(context, false)
        val report = ConnectionDiagnosticReport.build(context, "test3", true)
        usb.close()
        assertTrue(report.contains("transport=wireless"))
        assertTrue(report.contains("transport=usb"))
        assertTrue(report.contains("failureClass=UserDeniedPairing"))
        assertTrue(report.contains("id=0xaa00"))
        assertTrue(report.contains(tcpArrival))
        assertTrue(report.contains(bonjour))
        assertTrue(report.contains(identity))
        assertTrue(report.contains(manualIdentity))
        assertTrue(report.contains(availability))
        assertTrue(report.contains(driver))
        assertTrue(report.contains(recovery))
        assertTrue(report.contains(restored))
        mediaEvents.forEach { assertTrue("Playback diagnosis must survive export", report.contains(it)) }
        assertTrue(report.contains("USB configuration set=6 result=false active=4"))
        assertTrue(report.contains("Log queue drained: true"))
        assertTrue(report.contains("--- END OF REPORT ---"))
        assertFalse(report.contains("must-not-export"))
    }

    @Test fun repeatedUsbAttemptsDoNotRotateAwayWirelessHistory() {
        SessionLogFile(File(context.filesDir, "logs/connection-wireless/diplay.log")).use {
            it.reset("transport=wireless last-stage=AUTHENTICATING")
            val failure = "ERROR wireless control channel closed before tunnel ready"
            assertTrue(ConnectionEnvironmentSnapshot.isConnectionEvent(failure))
            it.append(failure)
        }
        repeat(10) { index ->
            SessionLogFile(File(context.filesDir, "logs/connection-usb/diplay.log")).use {
                it.reset("transport=usb attempt=$index")
            }
        }
        val report = ConnectionDiagnosticReport.build(context, "test3", true)
        assertTrue(report.contains("transport=wireless last-stage=AUTHENTICATING"))
        assertTrue(report.contains("ERROR wireless control channel closed before tunnel ready"))
        assertTrue(report.contains("transport=usb attempt=9"))
        assertTrue(report.contains("transport=usb attempt=2"))
        assertFalse(report.contains("transport=usb attempt=0"))
    }

    @Test fun android9CreatesReadableShareableFileWithoutDocumentsUiOrStoragePermission() {
        val saved = DiagnosticExportStore.saveLocally(context, "DiPlay-test3.txt", "USB + 无线\n")
        assertTrue(saved.file.isFile)
        assertEquals("content", saved.uri.scheme)
        assertEquals("${context.packageName}.reports", saved.uri.authority)
        context.contentResolver.openInputStream(saved.uri)!!.bufferedReader().use {
            assertEquals("USB + 无线\n", it.readText())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.reports", File(context.filesDir, "offline-mfi/identity.pk8"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticExportStore.saveLocally(context, "../secret.txt", "test")
        }
    }
}
