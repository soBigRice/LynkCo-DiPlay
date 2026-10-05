package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionRetrySchedulerTest {
    @Test fun manualRestartDoesNotLoseTheNextFailuresRetry() {
        val scheduler = ConnectionRetryScheduler(Handler(Looper.getMainLooper()))
        val calls = mutableListOf<String>()
        scheduler.schedule(2000) { calls += "old attempt" }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        scheduler.cancel() // User taps Retry before the automatic retry fires.
        scheduler.schedule(3000) { calls += "new attempt" }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertTrue(calls.isEmpty())
        assertTrue(scheduler.isScheduled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertEquals(listOf("new attempt"), calls)
        assertFalse(scheduler.isScheduled)
    }

    @Test fun duplicateFailuresScheduleOnlyOneRetryAndCancellationStopsIt() {
        val scheduler = ConnectionRetryScheduler(Handler(Looper.getMainLooper()))
        var calls = 0
        scheduler.schedule(1000) { calls++ }
        scheduler.schedule(1000) { calls++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, calls)
        scheduler.schedule(1000) { calls++ }
        scheduler.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, calls)
    }
}
