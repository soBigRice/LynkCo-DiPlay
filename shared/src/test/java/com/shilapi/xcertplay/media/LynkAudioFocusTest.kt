package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LynkAudioFocusTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private fun track() = AudioTrack.Builder().setAudioAttributes(attributes).setBufferSizeInBytes(4096).build()
    private fun request(coordinator: AudioFocusCoordinator) = coordinator.javaClass.getDeclaredField("request")
        .apply { isAccessible = true }.get(coordinator) as AudioFocusRequest?

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
