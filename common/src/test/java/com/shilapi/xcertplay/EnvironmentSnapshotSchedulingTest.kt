package com.shilapi.xcertplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EnvironmentSnapshotSchedulingTest {
    @Test fun audioContextCaptureQueuesOnceAndDoesNotWaitForTheWorker() {
        val context = RuntimeEnvironment.getApplication()
        val directory = java.nio.file.Files.createTempDirectory("audio-context-test").toFile()
        val session = SessionLogFile(java.io.File(directory, "session.log"))
        val connection = SessionLogFile(java.io.File(directory, "connection.log"))
        session.reset("test"); connection.reset("test")
        val logs = CarPlayBackgroundSession.Logs(session, connection)
        val executor = CarPlayBackgroundSession::class.java.getDeclaredField("snapshotExecutor")
            .apply { isAccessible = true }.get(CarPlayBackgroundSession) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(3) { logs.captureAudioContext(context) }
            assertEquals("Focus callbacks must only queue one bounded background read", 1, executor.queue.size)
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((executor.activeCount != 0 || executor.queue.isNotEmpty()) && System.nanoTime() < deadline)
                Thread.sleep(1)
            try {
                assertEquals(0, executor.activeCount)
                assertTrue(executor.queue.isEmpty())
                assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
                val capture = connection.file.readText()
                assertEquals(1, capture.lineSequence().count { it.contains("Audio context captured requestedAtMs=") })
                assertTrue(capture.contains("focusOwner=not_exposed_to_app"))
                assertEquals(session.file.readLines().map { it.substringAfter("  ") },
                    capture.lines().filter { it.isNotEmpty() }.map { it.substringAfter("  ") })
            } finally {
                session.close(); connection.close(); directory.deleteRecursively()
            }
        }
    }

    @Test fun startingSessionDuringPreviousSnapshotCompletionDoesNotFail() {
        CarPlayBackgroundSession.clear()
        val executor = CarPlayBackgroundSession::class.java.getDeclaredField("snapshotExecutor")
            .apply { isAccessible = true }.get(CarPlayBackgroundSession) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        // Reproduce the interval after snapshotPending becomes false but before the
        // old worker returns to its queue. A new session must be allowed to start.
        executor.execute {
            entered.countDown()
            try { release.await(5, TimeUnit.SECONDS) } finally { finished.countDown() }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val logs = CarPlayBackgroundSession.obtainLogs(RuntimeEnvironment.getApplication())
            assertSame(logs, CarPlayBackgroundSession.obtainLogs(RuntimeEnvironment.getApplication()))
            assertEquals("Only the new environment snapshot should be waiting", 1, executor.queue.size)
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((executor.activeCount != 0 || executor.queue.isNotEmpty()) && System.nanoTime() < deadline)
                Thread.sleep(1)
            assertEquals(0, executor.activeCount)
            assertTrue(executor.queue.isEmpty())
            CarPlayBackgroundSession.clear()
        }
    }
}
