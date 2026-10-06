package com.shilapi.xcertplay.media

import java.util.concurrent.CompletableFuture

/** A close barrier includes retired workers, not just resources still present in the live maps. */
internal class MediaResourceScope {
    private val pending = mutableSetOf<CompletableFuture<Unit>>()
    private var sealed = false
    private var failure: Throwable? = null
    val completion = CompletableFuture<Unit>()

    @Synchronized fun track(done: CompletableFuture<Unit>) {
        check(!sealed) { "Media scope is closing" }
        pending.add(done)
        done.whenComplete { _, error ->
            synchronized(this) {
                pending.remove(done)
                if (error != null && failure == null) failure = error
                finishIfReady()
            }
        }
    }

    @Synchronized fun seal() {
        sealed = true
        finishIfReady()
    }

    private fun finishIfReady() {
        if (!sealed || pending.isNotEmpty()) return
        failure?.let { completion.completeExceptionally(it) } ?: completion.complete(Unit)
    }
}
