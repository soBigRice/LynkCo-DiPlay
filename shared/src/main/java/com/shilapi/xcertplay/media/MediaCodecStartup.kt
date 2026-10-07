package com.shilapi.xcertplay.media

/** A failed configure/start still owns the created codec and must release it before propagating. */
internal object MediaCodecStartup {
    fun <T> create(
        create: () -> T,
        configure: (T) -> Unit,
        start: (T) -> Unit,
        release: (T) -> Unit,
        onReleaseFailure: (Throwable) -> Unit = {},
    ): T {
        val candidate = create()
        try {
            configure(candidate)
            start(candidate)
            return candidate
        } catch (failure: Throwable) {
            // Driver cleanup can fail too; preserve the original configure/start failure.
            try {
                release(candidate)
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
                onReleaseFailure(cleanup)
            }
            throw failure
        }
    }
}
