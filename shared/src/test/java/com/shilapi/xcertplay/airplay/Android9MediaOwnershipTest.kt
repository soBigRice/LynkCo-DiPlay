package com.shilapi.xcertplay.airplay

import android.content.Intent
import android.net.VpnService
import com.shilapi.xcertplay.network.CarPlayVpnService
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression of the API28 baseline failures recorded in the head-unit audit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Android9MediaOwnershipTest {
    private val setup = mapOf<String,Any?>("audioType" to "media", "audioFormat" to 0x8000L, "streamConnectionID" to 42L)
    private fun session(): AirPlaySession {
        val result = AirPlaySession(socket = Socket(),
            config = AirPlayConfig(deviceName = "audit", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {})
        result.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(result.pairVerify, ByteArray(32) { (it+1).toByte() })
        return result
    }
    private fun timingCount(engine: CarPlayMediaEngine, session: AirPlaySession) =
        (engine.onFeedback(session)?.get("streams") as? List<*>)?.size ?: 0

    @Test fun audioSetupPublishesOneTimingRecord_control() {
        val owner = session(); val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(owner, 100, setup))
            assertEquals(1, timingCount(engine, owner))
        } finally { engine.onSessionClosed(owner); owner.close() }
    }
    @Test fun closingUnrelatedProbeMustNotClearLiveSessionAudioTiming() {
        val owner = session(); val probe = session(); val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(owner, 100, setup))
            assertEquals(1, timingCount(engine, owner))
            engine.onSessionClosed(probe)
            assertEquals("Closing unrelated session removed live audio timing", 1, timingCount(engine, owner))
        } finally { engine.onSessionClosed(owner); probe.close(); owner.close() }
    }
    @Test fun sessionCloseMustReleaseItsAudioSink() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped += id }
        })
        val owner = session()
        try {
            assertNotNull(engine.onAudio(owner, 100, setup))
            stopped.clear()
            engine.onSessionClosed(owner)
            assertEquals("Session close did not release its audio renderer", listOf(100 to "media"), stopped.map { it.type to it.audioType })
        } finally { engine.onSessionClosed(owner); owner.close() }
    }
    @Test fun feedbackBelongsOnlyToTheRequestingSession() {
        val first = session(); val second = session(); val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            engine.onAudio(first, 100, setup)
            engine.onAudio(second, 100, setup)
            assertEquals(1, timingCount(engine, first))
            assertEquals(1, timingCount(engine, second))
            engine.onSessionClosed(first)
            assertEquals(1, timingCount(engine, second))
            assertNull(engine.onAudio(first, 100, setup))
        } finally { engine.onSessionClosed(first); engine.onSessionClosed(second); first.close(); second.close() }
    }

    @Test fun streamReplacementAndNewSessionHaveDistinctOutputOwners() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped += id }
        })
        val first = session(); val second = session()
        try {
            engine.onAudio(first, 100, setup)
            engine.onAudio(first, 100, setup)
            engine.onAudio(second, 100, setup)
            engine.onSessionClosed(first)
            engine.onSessionClosed(second)
            assertEquals(3, stopped.size)
            assertEquals(3, stopped.toSet().size)
            assertEquals(stopped[0].owner!!.session, stopped[1].owner!!.session)
            assertNotEquals(stopped[1].owner!!.session, stopped[2].owner!!.session)
        } finally { engine.onSessionClosed(first); engine.onSessionClosed(second); first.close(); second.close() }
    }

    @Test fun systemVpnBindingMustPreserveFrameworkRevocationCallback() {
        val handle = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try {
            val service = handle.get()
            val local = service.onBind(Intent())
            val system = service.onBind(Intent(VpnService.SERVICE_INTERFACE))
            assertNotNull(system)
            assertNotSame("System received LocalBinder instead of VpnService callback Binder", local, system)
        } finally { handle.destroy() }
    }
}
