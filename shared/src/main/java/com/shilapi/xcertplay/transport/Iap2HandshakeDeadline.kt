package com.shilapi.xcertplay.transport

import java.io.IOException

/** Observed protocol milestones, not inferred from a Bluetooth/Wi-Fi association. */
enum class Iap2HandshakeStage {
    IDENTIFYING, AUTHENTICATING, WAITING_FOR_CARPLAY,
    WIFI_CREDENTIALS_SENT, START_REQUESTED,
}

class Iap2HandshakeTimeoutException(val stage: Iap2HandshakeStage) :
    IOException("Initial CarPlay handshake timed out at ${stage.name}")

/** A startup budget never becomes a maximum driving-session duration. Zero preserves legacy timing. */
internal class Iap2HandshakeDeadline(
    private val timeoutMillis: Long,
    private val sessionActive: () -> Boolean,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init { require(timeoutMillis in 0..300_000L) }
    private val started = clockNanos()
    private var completed = false
    var stage = Iap2HandshakeStage.IDENTIFYING

    fun bound(transportMillis: Long, poll: Boolean = false): Long {
        if (timeoutMillis == 0L || completed) return transportMillis
        if (sessionActive()) { completed = true; return transportMillis }
        val remaining = timeoutMillis * 1_000_000 - (clockNanos() - started).coerceAtLeast(0)
        if (remaining <= 0) throw Iap2HandshakeTimeoutException(stage)
        // While awaiting the first AirPlay session, periodically observe its independent callback.
        return minOf(transportMillis, (remaining + 999_999) / 1_000_000,
            if (poll) 1000L else Long.MAX_VALUE)
    }
}
