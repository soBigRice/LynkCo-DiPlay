package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [AudioCodecLifecycleTest.FailingCodec::class])
class AudioCodecLifecycleTest {
    @Before fun reset() {
        FailingCodec.failAtStart = false
        FailingCodec.configured = 0
        FailingCodec.released = 0
    }

    @Test fun rejectedDecoderConfigurationReleasesTheCreatedCodec() = decoderFailure()

    @Test fun failedDecoderStartReleasesTheCreatedCodec() {
        FailingCodec.failAtStart = true
        decoderFailure()
    }

    @Test fun rejectedOpusEncoderConfigurationReleasesTheCreatedCodec() = encoderFailure()

    @Test fun failedOpusEncoderStartReleasesTheCreatedCodec() {
        FailingCodec.failAtStart = true
        encoderFailure()
    }

    private fun decoderFailure() {
        val sink = AndroidMediaSink()
        val format = AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 100, "media")
        val renderer = sink.javaClass.getDeclaredMethod("audioRenderer", AudioStreamId::class.java, AudioFormat::class.java)
            .apply { isAccessible = true }.invoke(sink, AudioStreamId(100, "media"), format)
        try {
            renderer.javaClass.getDeclaredMethod("configureCodec", String::class.java)
                .apply { isAccessible = true }.invoke(renderer, MediaFormat.MIMETYPE_AUDIO_AAC)
            assertEquals("Fault injection must reach configure", 1, FailingCodec.configured)
            assertEquals("Rejected codec must be released before the next attempt", 1, FailingCodec.released)
        } finally {
            sink.close()
        }
    }

    private fun encoderFailure() {
        OpusEncoder(48000).use { encoder ->
            assertFalse(encoder.available)
            assertEquals("Fault injection must reach configure", 1, FailingCodec.configured)
            assertEquals("Rejected codec must be released before the next attempt", 1, FailingCodec.released)
        }
        assertEquals("close must not release a failed candidate twice", 1, FailingCodec.released)
    }

    @Implements(MediaCodec::class)
    class FailingCodec : ShadowMediaCodec() {
        @Implementation
        fun configure(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
            configured++
            if (!failAtStart) throw IllegalArgumentException("Synthetic unsupported format")
        }

        @Implementation
        fun start() { throw IllegalStateException("Synthetic codec start failure") }

        @Implementation
        fun release() { released++ }

        companion object {
            var failAtStart = false
            var configured = 0
            var released = 0
        }
    }
}
