package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Iap2StartupSilenceTest {
    @Test(timeout = 5000) fun silentWirelessPeerStopsAtIdentificationWithoutInvokingAuthentication() {
        val events = mutableListOf<Iap2HandshakeStage>()
        Iap2Session.openWireless(SilentStream()).use { session ->
            val failure = assertThrows(Iap2HandshakeTimeoutException::class.java) {
                Iap2WirelessControlClient(session, auth()).run(identity(true),
                    Iap2WirelessCarPlayEndpoint("test", "test12345", 36, Iap2WirelessSecurity.WPA_WPA2,
                        listOf("192.168.2.1"), 7000, "test", "abcd", "1.0"),
                    timeoutMillis = 300_000, initialHandshakeTimeoutMillis = 100,
                    onHandshakeStage = { events.add(it) })
            }
            assertEquals(Iap2HandshakeStage.IDENTIFYING, failure.stage)
            assertEquals(listOf(Iap2HandshakeStage.IDENTIFYING), events)
        }
    }
    @Test(timeout = 5000) fun silentUsbPeerStopsAtIdentificationDespiteUnlimitedDriveDuration() {
        Iap2Session.open(SilentStream()).use { session ->
            val failure = assertThrows(Iap2HandshakeTimeoutException::class.java) {
                Iap2WiredControlClient(session, auth()).run(identity(false),
                    Iap2WiredCarPlayEndpoint(listOf("fe80::2"), 7000, "abcd", "1.0"), 500,
                    timeoutMillis = Long.MAX_VALUE, initialHandshakeTimeoutMillis = 100)
            }
            assertEquals(Iap2HandshakeStage.IDENTIFYING, failure.stage)
        }
    }
    private fun identity(wireless: Boolean) = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 4,
        wireless = if (wireless) Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "test") else null)
    private fun auth() = Iap2MfiAuthenticationClient(object : MfiAuthenticator {
        override fun protocolMajor(): Int = error("No authentication before identification")
        override fun readCertificate(maximumOutputLength: Int): ByteArray = error("No authentication before identification")
        override fun signChallenge(challenge: ByteArray): ByteArray = error("No authentication before identification")
    })
    private class SilentStream : BlockingDuplexByteStream {
        private val closed = CountDownLatch(1)
        override fun send(data: ByteArray) = Unit
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            if (closed.await(timeoutMillis, TimeUnit.MILLISECONDS)) byteArrayOf() else null
        override fun close() = closed.countDown()
    }
}
