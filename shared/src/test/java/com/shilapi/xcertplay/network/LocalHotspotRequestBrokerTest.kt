package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalHotspotRequestBrokerTest {
    private fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    @Test fun cancelledPendingRequestBlocksSuccessorUntilLateReservationIsReleased() {
        val broker = LocalHotspotRequestBroker()
        val first = broker.acquire(deadline()) { false }
        broker.submit(first) {}
        broker.release(first)
        val worker = Executors.newSingleThreadExecutor()
        val closes = AtomicInteger()
        try {
            val next = worker.submit<LocalHotspotRequestBroker.Ticket> { broker.acquire(deadline()) { false } }
            assertThrows(java.util.concurrent.TimeoutException::class.java) { next.get(50, TimeUnit.MILLISECONDS) }
            assertFalse(broker.started(first, AutoCloseable { closes.incrementAndGet() }))
            val second = next.get(1, TimeUnit.SECONDS)
            assertEquals(1, closes.get())
            broker.finished(first) // a stale onStopped cannot clear the new system request.
            broker.submit(second) {}
            assertThrows(IOException::class.java) { broker.acquire(System.nanoTime()) { false } }
            broker.finished(second)
        } finally { worker.shutdownNow() }
    }
    @Test fun waitingTimeoutAndCancellationDoNotDiscardTheOutstandingSystemRequest() {
        val broker = LocalHotspotRequestBroker()
        val first = broker.acquire(deadline()) { false }
        broker.submit(first) {}
        assertThrows(IOException::class.java) { broker.acquire(System.nanoTime()) { false } }
        assertThrows(IOException::class.java) { broker.acquire(deadline()) { true } }
        broker.release(first)
        assertThrows(IOException::class.java) { broker.acquire(System.nanoTime()) { false } }
        broker.finished(first)
        broker.acquire(deadline()) { false }.also(broker::release)
    }
    @Test fun frameworkStillHoldingSlotAfterCloseIsReportedWithoutRepeatedRequests() {
        val broker = LocalHotspotRequestBroker()
        val first = broker.acquire(deadline()) { false }
        broker.submit(first) {}
        broker.started(first, AutoCloseable { /* Android 9 can swallow a binder failure here. */ })
        broker.release(first)
        val second = broker.acquire(deadline()) { false }
        assertThrows(IllegalStateException::class.java) {
            broker.submit(second) { throw IllegalStateException("Caller already has an active request") }
        }
        assertThrows(IOException::class.java) { broker.acquire(deadline()) { false } }
    }
}
