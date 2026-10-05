package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29])
class AndroidAudioStartupTest {
    @Test fun mediaTrackReachesReadyWithoutCallingAnUnavailablePlatformGetter() {
        withTrack(0) { attributes, track, diagnostics ->
            assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            assertTrue(diagnostics.any { it.startsWith("Audio: ready") && it.contains("route=usage") })
        }
    }

    @Test fun configuredLegacyStreamKeepsMatchingFocusAttributes() {
        withTrack(AudioManager.STREAM_ALARM) { attributes, _, diagnostics ->
            assertEquals(AudioAttributes.USAGE_ALARM, attributes.usage)
            assertTrue(diagnostics.any { it.startsWith("Audio: ready") && it.contains("route=streamType=4") })
        }
    }

    @Test
    @Config(shadows = [RejectFirstTrack::class])
    fun rejectedLegacyStreamFallsBackToMediaUsage() {
        // Model a ROM that rejects the configured legacy track. Robolectric's default
        // native setup accepts even unsupported stream IDs, unlike a real audio HAL.
        RejectFirstTrack.rejectNextState.set(true)
        withTrack(AudioManager.STREAM_ALARM) { attributes, track, diagnostics ->
            assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            assertTrue(diagnostics.any { it.contains("route=streamType=4(fallback=usage)") })
        }
    }

    @Implements(AudioTrack::class)
    class RejectFirstTrack : ShadowAudioTrack() {
        @Implementation
        fun getState(): Int = if (rejectNextState.compareAndSet(true, false)) {
            AudioTrack.STATE_UNINITIALIZED
        } else AudioTrack.STATE_INITIALIZED

        companion object {
            val rejectNextState = AtomicBoolean()
        }
    }

    private fun withTrack(stream: Int, check: (AudioAttributes, AudioTrack, List<String>) -> Unit) {
        ShadowAudioTrack.setMinBufferSize(4096)
        val diagnostics = mutableListOf<String>()
        val sink = AndroidMediaSink(mediaChannel = stream, onAudioDiagnostic = { diagnostics += it })
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100, "media")
        val renderer = sink.javaClass.getDeclaredMethod("audioRenderer", AudioStreamId::class.java, AudioFormat::class.java)
            .apply { isAccessible = true }.invoke(sink, AudioStreamId(100, "media"), format)
        // Run the actual track creation synchronously so LinkageError is a test failure,
        // not an uncaught error on a background thread that could escape the assertion.
        try {
            renderer.javaClass.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(renderer)
            val attributes = renderer.javaClass.getDeclaredField("trackAttributes").apply { isAccessible = true }.get(renderer) as AudioAttributes
            val track = renderer.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(renderer) as AudioTrack
            check(attributes, track, diagnostics)
        } finally {
            renderer.javaClass.getDeclaredMethod("release").apply { isAccessible = true }.invoke(renderer)
            sink.close()
        }
    }
}
