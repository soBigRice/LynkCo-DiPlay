package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [AudioOutputLifecycleTest.Output::class])
class AudioOutputLifecycleTest {
    @Before fun reset() { Output.reset(); ShadowAudioTrack.setMinBufferSize(4096) }
    private val id = AudioStreamId(100, "media", MediaStreamOwner(1, 1))
    private val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100, "media")
    private fun send(sink: AndroidMediaSink) = sink.onAudioRtp(id, format, ByteArray(12 + 32768) { 1 }, 0)

    @Test fun deadObjectRecreatesOnceAndContinuesTheSameFormat() {
        Output.deadWrites = 1
        val diagnostics = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(onAudioDiagnostic = diagnostics::add)
        try {
            sink.onAudioStarted(id, format, 0); send(sink)
            assertTrue(Output.successfulWrite.await(2, TimeUnit.SECONDS))
            assertEquals(1, diagnostics.count { it.contains("recreating dead output") })
            assertTrue(diagnostics.count { it.contains("Audio: ready") && it.contains("rate=48000 channels=2") } == 2)
        } finally { sink.close(); assertTrue(sink.awaitClosed(2000)) }
    }
    @Test fun persistentDeadObjectStopsWorkerAndLatePacketsCannotResurrectIt() {
        Output.deadWrites = Int.MAX_VALUE
        val sink = AndroidMediaSink()
        try {
            sink.onAudioStarted(id, format, 0)
            val renderer = renderers(sink)[id]!!
            send(sink)
            completion(renderer).get(2, TimeUnit.SECONDS)
            assertTrue(renderers(sink).isEmpty())
            val writes = Output.writes.get()
            repeat(20) { send(sink) }
            assertEquals(2, writes)
            assertEquals(writes, Output.writes.get())
            assertTrue(renderers(sink).isEmpty())
        } finally { sink.close(); assertTrue(sink.awaitClosed(2000)) }
    }
    @Test fun cancellationDuringNativeWriteDoesNotPlayAgainAndWaitsForNativeRelease() {
        Output.blockWrite = true; Output.blockRelease = true
        val sink = AndroidMediaSink()
        try {
            sink.onAudioStarted(id, format, 0); send(sink)
            assertTrue(Output.writeEntered.await(2, TimeUnit.SECONDS))
            sink.close()
            Output.resumeWrite.countDown()
            assertTrue(Output.releaseEntered.await(2, TimeUnit.SECONDS))
            assertEquals(0, Output.plays.get())
            assertFalse(sink.awaitClosed(25))
            Output.resumeRelease.countDown()
            assertTrue(sink.awaitClosed(2000))
            assertEquals(0, Output.plays.get())
        } finally {
            Output.resumeWrite.countDown(); Output.resumeRelease.countDown()
            sink.close(); assertTrue(sink.awaitClosed(2000))
        }
    }
    @Test fun failedNativeReleaseIsNotReportedAsClosedSuccessfully() {
        Output.failRelease = true
        val sink = AndroidMediaSink()
        sink.onAudioStarted(id, format, 0); send(sink)
        assertTrue(Output.successfulWrite.await(2, TimeUnit.SECONDS))
        sink.close()
        val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { sink.awaitClosed(2000) }
        assertTrue(failure.cause is IllegalStateException)
    }
    @Suppress("UNCHECKED_CAST") private fun renderers(sink: AndroidMediaSink) =
        sink.javaClass.getDeclaredField("audioRenderers").apply { isAccessible = true }.get(sink) as Map<AudioStreamId, Any>
    @Suppress("UNCHECKED_CAST") private fun completion(renderer: Any) =
        renderer.javaClass.getDeclaredMethod("getCompletion").apply { isAccessible = true }.invoke(renderer)
            as java.util.concurrent.CompletableFuture<Unit>

    @Implements(AudioTrack::class)
    class Output : ShadowAudioTrack() {
        @Implementation fun write(data: ByteArray, offset: Int, size: Int, mode: Int): Int {
            val number = writes.incrementAndGet()
            writeEntered.countDown()
            if (blockWrite) awaitUninterruptibly(resumeWrite)
            if (number <= deadWrites) return AudioTrack.ERROR_DEAD_OBJECT
            successfulWrite.countDown()
            return size
        }
        @Implementation override fun play() { plays.incrementAndGet(); super.play() }
        @Implementation fun native_release() {
            releaseEntered.countDown()
            if (blockRelease) awaitUninterruptibly(resumeRelease)
            if (failRelease) throw IllegalStateException("Synthetic release failure")
        }
        companion object {
            var deadWrites = 0; var blockWrite = false; var blockRelease = false
            var failRelease = false
            val writes = AtomicInteger(); val plays = AtomicInteger()
            lateinit var writeEntered: CountDownLatch; lateinit var successfulWrite: CountDownLatch
            lateinit var resumeWrite: CountDownLatch; lateinit var releaseEntered: CountDownLatch
            lateinit var resumeRelease: CountDownLatch
            fun reset() {
                deadWrites = 0; blockWrite = false; blockRelease = false; writes.set(0); plays.set(0)
                failRelease = false
                writeEntered = CountDownLatch(1); successfulWrite = CountDownLatch(1)
                resumeWrite = CountDownLatch(1); releaseEntered = CountDownLatch(1); resumeRelease = CountDownLatch(1)
            }
            fun awaitUninterruptibly(latch: CountDownLatch) {
                while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) {}
            }
        }
    }
}
