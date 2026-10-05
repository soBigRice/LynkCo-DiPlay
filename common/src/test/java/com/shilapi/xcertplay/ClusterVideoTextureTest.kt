package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterVideoTextureTest {
    @Test fun textureDetachesBeforeReleaseAndCloseIsIdempotent() {
        val callbacks = mutableListOf<Surface?>()
        val view = ClusterVideoTexture(RuntimeEnvironment.getApplication()) { callbacks.add(it) }
        val texture = SurfaceTexture(0)
        try {
            assertFalse(view.isOpaque)
            val listener = view.surfaceTextureListener!!
            listener.onSurfaceTextureAvailable(texture, 1920, 720)
            assertNotNull(callbacks.last())
            assertTrue(listener.onSurfaceTextureDestroyed(texture))
            assertNull(callbacks.last())
            assertEquals(2, callbacks.size)
            view.close(); view.close()
            assertEquals(2, callbacks.size)
        } finally { view.close(); texture.release() }
    }
}
