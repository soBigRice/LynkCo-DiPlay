package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicLong

/** A bounded next-frame proxy, not proof that the frame contains the touch response. */
internal object TouchLatencyProbe {
    private val pendingTouchNs = AtomicLong()
    @Volatile var maxSendNs = 0L

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        val previous = pendingTouchNs.get()
        if (previous == 0L || sentAtNs - previous > MAX_SAMPLE_NS) pendingTouchNs.compareAndSet(previous, sentAtNs)
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs.getAndSet(0L)
        val elapsed = nowNs - touch
        return if (touch != 0L && elapsed in 0..MAX_SAMPLE_NS) elapsed else -1L
    }

    private const val MAX_SAMPLE_NS = 2_000_000_000L
}
