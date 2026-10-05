package com.shilapi.xcertplay

import android.view.MotionEvent
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlaySettingsButtonTest {
    @Test fun tapOpensSettingsOnce() {
        var opened = 0
        val button = button { opened++ }
        touch(button, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(button, MotionEvent.ACTION_UP, 100f, 100f)
        assertEquals(1, opened)
    }

    @Test fun dragStaysOnScreenWithoutOpeningSettings() {
        var opened = 0
        val button = button { opened++ }
        touch(button, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(button, MotionEvent.ACTION_MOVE, -200f, 1800f)
        touch(button, MotionEvent.ACTION_UP, -200f, 1800f)
        assertEquals(0, opened)
        assertTrue(button.x >= 0)
        assertTrue(button.y >= 0)
        assertTrue(button.x + button.width <= 1000)
        assertTrue(button.y + button.height <= 600)
    }

    @Test fun cancelledTouchDoesNotOpenSettings() {
        var opened = 0
        val button = button { opened++ }
        touch(button, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(button, MotionEvent.ACTION_CANCEL, 100f, 100f)
        assertEquals(0, opened)
    }

    private fun button(onOpen: () -> Unit): CarPlaySettingsButton {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context).apply { layout(0, 0, 1000, 600) }
        return CarPlaySettingsButton(context, onOpen).also {
            root.addView(it, FrameLayout.LayoutParams(144, 96))
            it.layout(840, 24, 984, 120)
        }
    }

    private fun touch(button: CarPlaySettingsButton, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 1, action, x, y, 0).let { event ->
            try { assertTrue(button.onTouchEvent(event)) } finally { event.recycle() }
        }
    }
}
