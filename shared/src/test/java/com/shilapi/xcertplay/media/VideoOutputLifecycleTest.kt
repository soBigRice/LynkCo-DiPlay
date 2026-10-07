package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.MediaStreamOwner
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.VideoStreamId
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [VideoOutputLifecycleTest.Output::class])
class VideoOutputLifecycleTest {
    @Before fun reset() = Output.reset()

    @Test fun replacementWaitsForNativeReleaseAndRetiredFrameIsNotPresented() {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val sink = AndroidMediaSink(surface)
        val old = VideoStreamId(110, MediaStreamOwner(1, 1))
        val next = VideoStreamId(110, MediaStreamOwner(2, 1))
        fun setup(id: VideoStreamId) {
            sink.onScreenStreamActive(id, true)
            sink.onVideoCodec(id, VideoCodec.H264)
            sink.onVideoConfig(id, byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68))
        }
        try {
            setup(old)
            assertTrue(Output.dequeueEntered.await(2, TimeUnit.SECONDS))
            setup(next)
            Output.resumeDequeue.countDown()
            assertTrue(Output.releaseEntered.await(2, TimeUnit.SECONDS))
            assertEquals(0, Output.presented.get())
            assertEquals("Successor cannot configure while the old codec owns native resources", 1, Output.configures.get())
            Output.resumeRelease.countDown()
            assertTrue(Output.secondConfigured.await(2, TimeUnit.SECONDS))
            assertEquals(0, Output.presented.get())
        } finally {
            Output.resumeDequeue.countDown(); Output.resumeRelease.countDown()
            sink.close(); assertTrue(sink.awaitClosed(2000))
            surface.release(); texture.release()
        }
    }

    @Test fun thirdReplacementCannotSkipAnOlderNativeRelease() {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val sink = AndroidMediaSink(surface)
        val old = VideoStreamId(110, MediaStreamOwner(1, 1))
        val next = VideoStreamId(110, MediaStreamOwner(2, 1))
        fun setup(id: VideoStreamId) {
            sink.onScreenStreamActive(id, true)
            sink.onVideoCodec(id, VideoCodec.H264)
            sink.onVideoConfig(id, byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68))
        }
        try {
            setup(old)
            assertTrue(Output.dequeueEntered.await(2, TimeUnit.SECONDS))
            setup(next)
            Output.resumeDequeue.countDown()
            assertTrue(Output.releaseEntered.await(2, TimeUnit.SECONDS))
            assertEquals(0, Output.presented.get())
            assertEquals("Successor cannot configure while the old codec owns native resources", 1, Output.configures.get())
            setup(VideoStreamId(110, MediaStreamOwner(3, 1)))
            assertFalse("C cannot configure before A releases", Output.secondConfigured.await(200, TimeUnit.MILLISECONDS))
            Output.resumeRelease.countDown()
            assertTrue(Output.secondConfigured.await(2, TimeUnit.SECONDS))
            assertEquals(0, Output.presented.get())
        } finally {
            Output.resumeDequeue.countDown(); Output.resumeRelease.countDown()
            sink.close(); assertTrue(sink.awaitClosed(2000))
            surface.release(); texture.release()
        }
    }

    @Test fun closeAlsoWaitsForAnAlreadyRunningKeyframeCommand() {
        val sink = AndroidMediaSink()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handler: () -> Unit = { entered.countDown(); awaitUninterruptibly(release) }
        try {
            sink.javaClass.getDeclaredMethod("requestVideoRecovery", kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible = true }.invoke(sink, handler)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            sink.close()
            assertFalse(sink.awaitClosed(25))
            release.countDown()
            assertTrue(sink.awaitClosed(2000))
        } finally { release.countDown(); sink.close(); assertTrue(sink.awaitClosed(2000)) }
    }

    @Implements(MediaCodec::class)
    class Output : ShadowMediaCodec() {
        private var number = 0
        @Implementation fun configure(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
            number = configures.incrementAndGet()
            if (number == 2) secondConfigured.countDown()
        }
        @Implementation fun start() {}
        @Implementation fun stop() {}
        @Implementation fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeout: Long): Int {
            if (number != 1) return MediaCodec.INFO_TRY_AGAIN_LATER
            dequeueEntered.countDown(); awaitUninterruptibly(resumeDequeue)
            return 0
        }
        @Implementation override fun releaseOutputBuffer(index: Int, render: Boolean) { if (render) presented.incrementAndGet() }
        @Implementation fun release() {
            if (number != 1) return
            releaseEntered.countDown(); awaitUninterruptibly(resumeRelease)
        }
        companion object {
            val configures = AtomicInteger(); val presented = AtomicInteger()
            lateinit var dequeueEntered: CountDownLatch; lateinit var resumeDequeue: CountDownLatch
            lateinit var releaseEntered: CountDownLatch; lateinit var resumeRelease: CountDownLatch
            lateinit var secondConfigured: CountDownLatch
            fun reset() {
                configures.set(0); presented.set(0)
                dequeueEntered = CountDownLatch(1); resumeDequeue = CountDownLatch(1)
                releaseEntered = CountDownLatch(1); resumeRelease = CountDownLatch(1)
                secondConfigured = CountDownLatch(1)
            }
        }
    }
    companion object {
        private fun awaitUninterruptibly(latch: CountDownLatch) {
            while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) {}
        }
    }
}
