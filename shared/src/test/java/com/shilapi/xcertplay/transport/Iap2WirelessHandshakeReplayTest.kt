package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test

/** Synthetic phone transcript through the real link, CSM, identification and MFi clients.
 * Only the phone and its challenge signer are simulated; this is not iPhone certification.
 */
class Iap2WirelessHandshakeReplayTest {
    @Test fun unrecognizedOptionalAvailabilityDoesNotSuppressStart() {
        val replay = replay("000900010005000002")
        assertEquals(1, replay.sent.count { it.messageId == 0x4301 })
        assertTrue(replay.progress.any { it.contains("availability decode=unrecognized") })
    }

    @Test fun falseUnknownAndWiredOnlyAvailabilityStillReceiveOneStartRequest() {
        // Literal nested TLVs: wireless=false, no fields, wired=true, wireless=true.
        for (body in listOf("000900010005000000", "", "000900000005000001", "000900010005000001")) {
            val replay = replay(body)
            assertEquals("4300 body=$body must receive a start even without a second 4300", 1,
                replay.result.carPlayStartSessionsSent)
            assertEquals(1, replay.sent.count { it.messageId == 0x4301 })
            assertTrue(replay.stages.contains(Iap2HandshakeStage.START_REQUESTED))
        }
    }

    @Test fun realControlLoopAuthenticatesAnswersWifiAndForwardsOtherFrames() {
        val replay = replay("000900010005000000")
        assertEquals(listOf(0x1d01, 0xaa01, 0xaa03), replay.sent.take(3).map { it.messageId })
        assertArrayEquals(hex("000700000a0b0c"), replay.sent.single { it.messageId == 0xaa03 }.payload)
        assertEquals(1, replay.result.wifiConfigurationsSent)
        assertEquals(1, replay.sent.count { it.messageId == 0x5703 })
        assertEquals(listOf(0x9999), replay.forwarded.map { it.messageId })
        assertTrue(replay.result.wirelessCarPlayAvailableSeen)
        assertFalse(replay.progress.any { it.contains("start deferred") })
    }

    private data class Replay(val result: Iap2WirelessControlResult, val sent: List<Iap2Frame>,
        val stages: List<Iap2HandshakeStage>, val forwarded: List<Iap2Frame>, val progress: List<String>)

    private fun replay(availability: String): Replay {
        val phone = ScriptedPhone(availability)
        val stages = mutableListOf<Iap2HandshakeStage>()
        val forwarded = mutableListOf<Iap2Frame>()
        val progress = mutableListOf<String>()
        val authenticator = object : MfiAuthenticator {
            override fun protocolMajor() = 2
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1, 2, 3)
            override fun signChallenge(challenge: ByteArray): ByteArray {
                assertArrayEquals(byteArrayOf(1, 2, 3), challenge)
                return byteArrayOf(10, 11, 12)
            }
        }
        val result = Iap2Session.openWireless(phone).use { session ->
            Iap2WirelessControlClient(session, Iap2MfiAuthenticationClient(authenticator)).run(
                identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1",
                    wireless = Iap2WirelessIdentification("02:22:33:44:55:66", "test")),
                endpoint = Iap2WirelessCarPlayEndpoint("test", "secret123", 36,
                    Iap2WirelessSecurity.WPA_WPA2, listOf("192.168.2.1"), 7000, "02:22:33:44:55:66", "aabb", "366.0"),
                timeoutMillis = 1_000,
                onHandshakeStage = { stages += it }, onIncoming = { forwarded += it },
                onProgress = { progress += it },
            )
        }
        return Replay(result, phone.received.toList(), stages, forwarded, progress)
    }

    private class ScriptedPhone(private val availability: String) : BlockingDuplexByteStream {
        private val engine = Iap2LinkEngine().apply { start(wiredInitiator = true, nowMillis = now()) }
        private val framer = Iap2CsmFramer()
        val received = mutableListOf<Iap2Frame>()
        @Volatile private var closed = false
        private var identificationSent = false

        override fun send(data: ByteArray) {
            engine.feed(data, now())
            while (true) {
                when (val event = engine.pollEvent() ?: break) {
                    is Iap2LinkEngine.Event.Writable -> if (event.value && !identificationSent) {
                        identificationSent = true
                        emit(0x1d00)
                    }
                    is Iap2LinkEngine.Event.Control -> framer.offer(event.bytes).forEach { frame ->
                        received += frame
                        when (frame.messageId) {
                            0x1d01 -> { emit(0x1d02); emit(0xaa00) }
                            0xaa01 -> emit(0xaa02, "00070000010203")
                            0xaa03 -> {
                                emit(0xaa05)
                                emit(0x5702)
                                emit(0x4e0d, "0005000000")
                                emit(0x4300, availability)
                                // Recovery without a repeated 4300 previously left the gate stuck.
                                emit(0x4e0d, "0005000001")
                                emit(0x9999)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (closed) return byteArrayOf()
            engine.advanceTime(now())
            val data = engine.takeOutput()
            check(data.size <= maxBytes)
            if (data.isNotEmpty()) return data
            if (timeoutMillis > 0) Thread.sleep(minOf(timeoutMillis, 2))
            return null
        }

        override fun close() { closed = true }
        private fun emit(id: Int, body: String = "") = engine.sendControl(Iap2Frame(id, hex(body)).encodedFrame(), now())
    }

    companion object {
        private fun now() = System.nanoTime() / 1_000_000
        private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
