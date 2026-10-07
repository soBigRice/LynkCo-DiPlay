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
        FailingCodec.failRelease = false
        FailingCodec.releaseEntered = java.util.concurrent.CountDownLatch(1)
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

    @Test fun failedConfigurationAndFailedReleaseDoNotReportSuccessfulShutdown() = doubleFailure(false)

    @Test fun failedStartAndFailedReleaseDoNotReportSuccessfulShutdown() = doubleFailure(true)

    @Test fun rejectedCodecWithSuccessfulReleaseAllowsTheNextAudioOutput() {
        val ready = java.util.concurrent.CountDownLatch(1)
        val sink = AndroidMediaSink(onAudioDiagnostic = {
            if (it.startsWith("Audio: ready")) ready.countDown()
        })
        try {
            sink.onAudioStarted(AudioStreamId(100, "media"), AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 100, "media"), 0)
            assertTrue(FailingCodec.releaseEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            sink.onAudioStarted(AudioStreamId(100, "media", com.shilapi.xcertplay.airplay.MediaStreamOwner(1, 2)),
                AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100, "media"), 0)
            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { sink.close(); assertTrue(sink.awaitClosed(2000)) }
    }

    private fun doubleFailure(atStart: Boolean) {
        FailingCodec.failAtStart = atStart
        FailingCodec.failRelease = true
        val sink = AndroidMediaSink()
        try {
            sink.onAudioStarted(AudioStreamId(100, "media"), AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 100, "media"), 0)
            assertTrue(FailingCodec.releaseEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            sink.close()
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { sink.awaitClosed(2000) }
            assertEquals("Synthetic codec release failure", failure.cause?.message)
        }
        assertEquals("Failed candidate is not released twice", 1, FailingCodec.released)
    }

    private fun decoderFailure() {
        val sink = AndroidMediaSink()
        val format = AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 100, "media")
        val renderer = sink.javaClass.getDeclaredMethod("audioRenderer", AudioStreamId::class.java, AudioFormat::class.java)
            .apply { isAccessible = true }.invoke(sink, AudioStreamId(100, "media"), format)
        try {
            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                renderer.javaClass.getDeclaredMethod("configureCodec", String::class.java)
                    .apply { isAccessible = true }.invoke(renderer, MediaFormat.MIMETYPE_AUDIO_AAC)
            }
            assertTrue(error.cause is IllegalArgumentException || error.cause is IllegalStateException)
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
        fun release() {
            released++
            releaseEntered.countDown()
            if (failRelease) throw IllegalStateException("Synthetic codec release failure")
        }

        companion object {
            var failAtStart = false
            var failRelease = false
            lateinit var releaseEntered: java.util.concurrent.CountDownLatch
            var configured = 0
            var released = 0
        }
    }
}
