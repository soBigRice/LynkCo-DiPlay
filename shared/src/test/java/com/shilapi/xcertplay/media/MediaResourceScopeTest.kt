package com.shilapi.xcertplay.media

import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class MediaResourceScopeTest {
    @Test fun retiredWorkerAndFailureRemainInBarrierUntilEveryWorkerTerminates() {
        val scope = MediaResourceScope()
        val first = CompletableFuture<Unit>()
        val second = CompletableFuture<Unit>()
        scope.track(first); scope.track(second)
        val failure = IllegalStateException("release failed")
        first.completeExceptionally(failure)
        assertFalse(scope.completion.isDone)
        scope.seal()
        assertFalse(scope.completion.isDone)
        var observed: Throwable? = null
        scope.completion.whenComplete { _, error -> observed = error }
        second.complete(Unit)
        assertTrue(scope.completion.isDone)
        assertSame(failure, observed)
    }
    @Test fun sealingAnIdleScopeCompletesAndForbidsNewNativeOwners() {
        val scope = MediaResourceScope()
        scope.seal()
        assertTrue(scope.completion.isDone)
        assertThrows(IllegalStateException::class.java) { scope.track(CompletableFuture()) }
    }
}
