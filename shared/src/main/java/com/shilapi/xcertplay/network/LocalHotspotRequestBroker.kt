package com.shilapi.xcertplay.network

import java.io.IOException

/** Android 9 permits one outstanding LOHS request per process, including cancelled waiters. */
internal class LocalHotspotRequestBroker {
    class Ticket internal constructor() {
        internal var abandoned = false
        internal var submitted = false
        internal var reservation: AutoCloseable? = null
        internal var releasing = false
    }

    private val lock = Object()
    private var current: Ticket? = null
    private var blocked = false

    fun acquire(deadlineNanos: Long, cancelled: () -> Boolean): Ticket = synchronized(lock) {
        while (true) {
            if (cancelled()) throw IOException("LocalOnlyHotspot request cancelled")
            if (blocked) throw IOException("LocalOnlyHotspot system request still registered; automatic retry stopped")
            if (current == null) return@synchronized Ticket().also { current = it }
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw IOException("LocalOnlyHotspot waiting for previous system request callback")
            // Cancellation wakes without waiting for an OEM callback that may never arrive.
            lock.wait(minOf(50L, (remaining / 1_000_000L).coerceAtLeast(1)))
        }
        error("unreachable")
    }

    fun <T> submit(ticket: Ticket, request: () -> T): T = synchronized(lock) {
        check(current === ticket && !ticket.abandoned) { "LocalOnlyHotspot request cancelled before submission" }
        ticket.submitted = true
        try { request() } catch (error: Throwable) { rejected(ticket, error); throw error }
    }

    fun started(ticket: Ticket, reservation: AutoCloseable): Boolean {
        val deliver = synchronized(lock) {
            if (current !== ticket || ticket.reservation != null) false
            else {
                ticket.reservation = reservation
                !ticket.abandoned
            }
        }
        if (!deliver) {
            if (synchronized(lock) { ticket.reservation === reservation }) release(ticket)
            else reservation.close()
        }
        return deliver
    }

    fun finished(ticket: Ticket) = synchronized(lock) {
        if (current === ticket) {
            current = null
            lock.notifyAll()
        }
    }

    fun rejected(ticket: Ticket, error: Throwable) = synchronized(lock) {
        if (current === ticket) {
            // Reservation.close() swallows binder errors on API28. An outstanding-request
            // rejection is evidence that the framework slot did not in fact get released.
            blocked = generateSequence(error) { it.cause }.any { it is IllegalStateException }
            current = null
            lock.notifyAll()
        }
    }

    fun release(ticket: Ticket) {
        val reservation = synchronized(lock) {
            ticket.abandoned = true
            if (current !== ticket || ticket.releasing) return
            if (!ticket.submitted) { finished(ticket); return }
            (ticket.reservation ?: return).also { ticket.releasing = true }
        }
        try {
            reservation.close()
            finished(ticket)
        } catch (error: Exception) {
            synchronized(lock) { blocked = true; current = null; lock.notifyAll() }
            throw error
        }
        // No public API cancels a pending request. With no reservation, keep the slot until
        // onStarted/onFailed/onStopped, even after its manager and controller have closed.
    }

    companion object { val process = LocalHotspotRequestBroker() }
}
