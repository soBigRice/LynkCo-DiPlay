package com.shilapi.xcertplay

/** Sampling affects diagnostics only; it must never delay a connection or retry. */
internal class DiagnosticSampleBudget(private val limit: Int = 12, private val intervalMs: Long = 2_000) {
    private var count = 0
    private var last: Long? = null
    @Synchronized fun take(nowMs: Long): Boolean {
        if (count >= limit || last?.let { nowMs - it < intervalMs } == true) return false
        count++
        last = nowMs
        return true
    }
}

internal class ConnectionWaitSamples {
    private val seconds = listOf(5L, 15L, 30L, 60L)
    private var next = 0
    fun reset() { next = 0 }
    fun due(elapsedSeconds: Long): Long? {
        var reached: Long? = null
        while (next < seconds.size && elapsedSeconds >= seconds[next]) reached = seconds[next++]
        return reached
    }
}
