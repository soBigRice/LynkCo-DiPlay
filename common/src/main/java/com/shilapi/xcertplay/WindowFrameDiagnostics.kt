package com.shilapi.xcertplay

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Window/UI cost is independent of the decoder's real presentation callback. */
internal class WindowFrameDiagnostics(private val window: Window, private val report: (String) -> Unit) : Closeable {
    private val closed = AtomicBoolean()
    private val thread = HandlerThread("lynk-window-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private var started = System.nanoTime()
    private var frames = 0
    private var slowFrames = 0
    private var maxNs = 0L
    private var lostReports = 0
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        if (!closed.get()) {
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (total >= 0) {
                frames++
                if (total > 32_000_000L) slowFrames++
                maxNs = maxOf(maxNs, total)
            }
            lostReports += dropped
            if (System.nanoTime() - started >= 5_000_000_000L) flush()
        }
    }

    init {
        try { window.addOnFrameMetricsAvailableListener(listener, handler) }
        catch (error: Exception) { thread.quitSafely(); throw error }
    }

    private fun flush() {
        if (frames > 0) report("Window frames=$frames over32ms=$slowFrames maxMs=${maxNs / 1_000_000} droppedReports=$lostReports")
        started = System.nanoTime(); frames = 0; slowFrames = 0; maxNs = 0; lostReports = 0
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { window.removeOnFrameMetricsAvailableListener(listener) }
        finally { handler.post { try { flush() } finally { thread.quitSafely() } } }
    }
}
