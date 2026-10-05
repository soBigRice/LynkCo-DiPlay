package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionCrashLogTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun fatalStackIsSynchronousSurvivesReopenAndExportsWithBothTransports() {
        val target = File(context.filesDir, "logs/last-crash.txt")
        val error = NoSuchMethodError("private credential must never be saved").apply {
            stackTrace = arrayOf(StackTraceElement("com.shilapi.xcertplay.media.AudioRenderer", "createTrack", "AndroidMediaSink.kt", 958))
        }
        var delegated = false
        val handler = ConnectionCrashLog.handler(target) { thread, failure ->
            assertSame(Thread.currentThread(), thread)
            assertSame(error, failure)
            assertTrue(target.readText().contains("AudioRenderer.createTrack"))
            delegated = true
        }
        handler.uncaughtException(Thread.currentThread(), error)
        assertTrue(delegated)
        val reopened = ConnectionCrashLog.report(context)
        assertTrue(reopened.contains("java.lang.NoSuchMethodError"))
        assertFalse(reopened.contains("private credential"))
        val report = ConnectionDiagnosticReport.build(context, "test8", true)
        assertTrue(report.contains("Last retained fatal stack"))
        assertTrue(report.contains("java.lang.NoSuchMethodError"))
        assertTrue(report.contains("connection-wireless history"))
        assertTrue(report.contains("connection-usb history"))
    }

    @Test fun persistenceFailureStillDelegatesOriginalError() {
        val parent = File(context.filesDir, "not-a-directory").apply { writeText("keep") }
        val error = IllegalStateException("private value")
        var received: Throwable? = null
        ConnectionCrashLog.handler(File(parent, "crash.txt")) { _, failure -> received = failure }
            .uncaughtException(Thread.currentThread(), error)
        assertSame(error, received)
        assertEquals("keep", parent.readText())
    }

    @Test fun hugeCyclicCauseChainIsBoundedAndMessagesAreExcluded() {
        val error = IllegalStateException("secret".repeat(20000))
        val cause = IllegalArgumentException("secret cause", error)
        error.initCause(cause)
        error.stackTrace = Array(2000) { StackTraceElement("Example", "worker", "Example.kt", it) }
        cause.stackTrace = error.stackTrace
        val target = File(context.filesDir, "logs/last-crash.txt")
        ConnectionCrashLog.handler(target) { _, _ -> }
            .uncaughtException(Thread.currentThread(), error)
        val report = ConnectionCrashLog.report(context)
        assertEquals(4, report.lineSequence().count { it.startsWith("cause[") })
        assertEquals(48, report.lineSequence().count { it.startsWith("  at ") })
        assertTrue(target.length() < 16 * 1024)
        assertFalse(report.contains("secret"))
    }

    @Test fun concurrentWritersAndExportKeepOneWholeRecordAndAlwaysDelegate() {
        val target = File(context.filesDir, "logs/last-crash.txt")
        val delegated = AtomicInteger()
        val handler = ConnectionCrashLog.handler(target) { _, _ -> delegated.incrementAndGet() }
        handler.uncaughtException(Thread.currentThread(), IllegalStateException())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val futures = (1..12).map {
                pool.submit {
                    start.await()
                    handler.uncaughtException(Thread.currentThread(), IllegalStateException())
                    val report = ConnectionCrashLog.report(context)
                    assertTrue(report.startsWith("Fatal stack at="))
                    assertEquals(1, report.lineSequence().count { it.startsWith("cause[0]=") })
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(13, delegated.get())
        } finally {
            pool.shutdownNow()
        }
    }
}
