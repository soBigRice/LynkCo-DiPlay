package com.shilapi.xcertplay

import android.os.Handler

/** Main-thread retry ownership. Manual restart cancels the old timer before a new attempt. */
internal class ConnectionRetryScheduler(private val handler: Handler) {
    private var pending: Runnable? = null
    val isScheduled get() = pending != null

    fun schedule(delayMillis: Long, action: () -> Unit) {
        if (pending != null) return
        val task = object : Runnable {
            override fun run() {
                if (pending !== this) return
                pending = null
                action()
            }
        }
        pending = task
        handler.postDelayed(task, delayMillis)
    }

    fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}
