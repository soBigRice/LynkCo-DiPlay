package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LynkAudioFocusTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private fun track() = AudioTrack.Builder().setAudioAttributes(attributes).setBufferSizeInBytes(4096).build()
    @Suppress("DEPRECATION")
    private class VolumeTrack : AudioTrack(AudioManager.STREAM_MUSIC, 48_000,
        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, 4096, MODE_STREAM) {
        var appliedVolume = 1f
        override fun setVolume(gain: Float): Int { appliedVolume = gain; return SUCCESS }
    }
    private fun request(coordinator: AudioFocusCoordinator) = coordinator.javaClass.getDeclaredField("request")
        .apply { isAccessible = true }.get(coordinator) as AudioFocusRequest?

    @Test fun diagnosticsDistinguishPendingMuteFromGrantedAudioWithoutReRequesting() {
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        val lines = mutableListOf<String>(); var events = 0
        val focus = AudioFocusCoordinator(context, true, true, lines::add, { events++ })
        val track = VolumeTrack()
        try {
            focus.acquire(track, AudioChannel.MEDIA, attributes)
            assertTrue(focus.diagnosticState().contains("pending=true appVolumeRequested=0.0"))
            assertTrue(focus.diagnosticState().contains("volumeApplyErrors=0"))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1_200))
            assertTrue(focus.diagnosticState().contains("requestAgeMs=1200"))
            val pending = request(focus)!!
            val listener = pending.javaClass.getMethod("getOnAudioFocusChangeListener")
                .invoke(pending) as AudioManager.OnAudioFocusChangeListener
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertTrue(focus.diagnosticState().contains("granted=true pending=false appVolumeRequested=1.0"))
            assertEquals(2, events)
            assertSame(pending, request(focus))
        } finally { focus.release(track); track.release() }
        assertTrue(focus.diagnosticState().contains("activeTracks=0 requestAgeMs=-1"))
    }

    @Test fun brokenDiagnosticObserverCannotInterruptAudioFocus() {
        val focus = AudioFocusCoordinator(context, true, true, onFocusEvent = { error("diagnostic failure") })
        val track = VolumeTrack()
        try {
            focus.acquire(track, AudioChannel.MEDIA, attributes)
            assertNotNull(request(focus))
            assertEquals(1f, track.appliedVolume, 0f)
        } finally { focus.release(track); track.release() }
    }

    @Test fun lockedFocusCanRecoverWithoutAnotherPhonePlayTransition() {
        val manager = context.getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        val focus = AudioFocusCoordinator(context, true, true)
        val track = VolumeTrack()
        try {
            focus.acquire(track, AudioChannel.MEDIA, attributes)
            val pending = request(focus)!!
            assertEquals(0f, track.appliedVolume, 0f)
            assertTrue("The system must retain the request while its current focus owner is locked",
                pending.acceptsDelayedFocusGain())
            focus.resumeMedia()
            assertSame("The phone play update must not abandon the pending system request", pending, request(focus))
            val listener = pending.javaClass.getMethod("getOnAudioFocusChangeListener")
                .invoke(pending) as AudioManager.OnAudioFocusChangeListener
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals("Delayed GAIN must unmute the existing audio track", 1f, track.appliedVolume, 0f)
            focus.resumeMedia()
            assertSame(pending, request(focus))
        } finally { focus.release(track); track.release() }
    }

    @Test fun releasingPendingFocusAbandonsItAndIgnoresLateGain() {
        val manager = shadowOf(context.getSystemService(AudioManager::class.java))
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        val focus = AudioFocusCoordinator(context, true, true)
        val oldTrack = VolumeTrack(); val currentTrack = VolumeTrack()
        try {
            focus.acquire(oldTrack, AudioChannel.MEDIA, attributes)
            val old = request(focus)!!
            val listener = old.javaClass.getMethod("getOnAudioFocusChangeListener")
                .invoke(old) as AudioManager.OnAudioFocusChangeListener
            focus.release(oldTrack)
            assertSame(old, manager.lastAbandonedAudioFocusRequest)
            assertNull(request(focus))
            focus.acquire(currentTrack, AudioChannel.MEDIA, attributes)
            assertEquals(0f, currentTrack.appliedVolume, 0f)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals("A finished session cannot unmute the next pending session", 0f, currentTrack.appliedVolume, 0f)
        } finally {
            focus.release(oldTrack); focus.release(currentTrack); oldTrack.release(); currentTrack.release()
        }
    }

    @Test fun ordinaryProfileDoesNotOptIntoDelayedFocus() {
        val focus = AudioFocusCoordinator(context, true); val track = track()
        try {
            focus.acquire(track, AudioChannel.MEDIA, attributes)
            assertFalse(request(focus)!!.acceptsDelayedFocusGain())
        } finally { focus.release(track); track.release() }
    }

    @Test fun phonePlayDoesNotReplaceDelayedFocusRequest() {
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        val focus = AudioFocusCoordinator(context, true, true)
        val track = track()
        try {
            focus.acquire(track, AudioChannel.MEDIA, attributes)
            val pending = request(focus)
            focus.resumeMedia()
            assertSame(pending, request(focus))
        } finally { focus.release(track); track.release() }
    }

    @Test fun rejectedRequestDoesNotBlockAValidNewMediaTrack() {
        val manager = shadowOf(context.getSystemService(AudioManager::class.java))
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val focus = AudioFocusCoordinator(context, true, true)
        val first = track(); val second = track()
        try {
            focus.acquire(first, AudioChannel.MEDIA, attributes)
            val rejected = request(focus)
            manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focus.acquire(second, AudioChannel.MEDIA, attributes)
            assertNotSame("A rejected request cannot be reused as an active focus grant", rejected, request(focus))
        } finally { focus.release(first); focus.release(second); first.release(); second.release() }
    }

    @Test fun repeatedPlayDoesNotReclaimUntilFocusWasLost() {
        val lines = mutableListOf<String>(); val focus = AudioFocusCoordinator(context, true, true, lines::add)
        val track = track()
        try {
            focus.acquire(track, AudioChannel.MEDIA, attributes)
            val first = request(focus)!!
            focus.resumeMedia(); assertSame(first, request(focus))
            val listener = first.javaClass.getMethod("getOnAudioFocusChangeListener").invoke(first) as AudioManager.OnAudioFocusChangeListener
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            focus.resumeMedia(); assertNotSame(first, request(focus))
            focus.resumeMedia()
            assertEquals(2, lines.count { it.startsWith("Audio: focus requested") })
        } finally { focus.release(track); track.release() }
        assertNull(request(focus))
    }
    @Test fun lateLossFromAnOldRequestCannotMuteOrReclaimTheNewRequest() {
        val lines = mutableListOf<String>(); val focus = AudioFocusCoordinator(context, true, true, lines::add)
        val nav = track(); val music = track()
        try {
            focus.acquire(nav, AudioChannel.NAVIGATION, attributes)
            val old = request(focus)!!
            val listener = old.javaClass.getMethod("getOnAudioFocusChangeListener").invoke(old) as AudioManager.OnAudioFocusChangeListener
            focus.acquire(music, AudioChannel.MEDIA, attributes)
            val current = request(focus)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            focus.resumeMedia()
            assertSame(current, request(focus))
            assertFalse(lines.any { it.startsWith("Audio: focus change=") })
        } finally { focus.release(nav); focus.release(music); nav.release(); music.release() }
    }

    @Test fun navigationOnlyClaimsTransientFocusButDoesNotInterruptItsOwnMusic() {
        val focus = AudioFocusCoordinator(context, true, true)
        val nav = track(); val music = track()
        try {
            focus.acquire(nav, AudioChannel.NAVIGATION, attributes)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request(focus)!!.focusGain)
            focus.acquire(music, AudioChannel.MEDIA, attributes)
            val mediaRequest = request(focus)!!
            focus.release(nav); focus.acquire(nav, AudioChannel.NAVIGATION, attributes)
            assertSame(mediaRequest, request(focus))
            focus.release(nav); assertSame(mediaRequest, request(focus))
        } finally { focus.release(nav); focus.release(music); nav.release(); music.release() }
    }
    @Test fun explicitlyDisabledFocusMakesNoRequests() {
        val focus = AudioFocusCoordinator(context, false, true); val track = track()
        try {
            focus.acquire(track, AudioChannel.MEDIA, attributes); focus.resumeMedia()
            assertNull(request(focus))
        } finally { focus.release(track); track.release() }
    }
    @Test fun upstreamNavigationPolicyIsUnchanged() {
        val focus = AudioFocusCoordinator(context, true); val track = track()
        try { focus.acquire(track, AudioChannel.NAVIGATION, attributes); assertNull(request(focus)) }
        finally { focus.release(track); track.release() }
    }
}
