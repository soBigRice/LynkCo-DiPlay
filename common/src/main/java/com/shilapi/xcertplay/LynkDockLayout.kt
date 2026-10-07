package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.view.Gravity
import android.view.WindowManager
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** One opt-in profile; manual resolution and safe-area preferences remain untouched. */
object LynkDockLayout {
    const val WIDTH = 1920
    const val HEIGHT = 920
    const val SAFE_HEIGHT = 880
    const val DOCK_HEIGHT = 160

    fun enabled(context: Context): Boolean =
        context.resources.getBoolean(R.bool.config_simple_connection_flow) &&
            AirPlayPersistence.loadLynkDockEnabled(context)

    fun display(base: AirPlayDisplayConfig): AirPlayDisplayConfig = base.copy(
        widthPixels = WIDTH,
        heightPixels = HEIGHT,
        safeArea = AirPlayInsets(bottom = HEIGHT - SAFE_HEIGHT),
        safeAreaDrawOutside = true,
    )

    // Physical pixels, never dp. Scale only the Android viewport on smaller landscape displays;
    // the iPhone always negotiates the requested 1920x920 canvas.
    fun windowSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val scale = minOf(1.0, screenWidth.toDouble() / WIDTH,
            screenHeight.toDouble() / (HEIGHT + DOCK_HEIGHT))
        return (WIDTH * scale).roundToInt().coerceAtLeast(1) to
            (HEIGHT * scale).roundToInt().coerceAtLeast(1)
    }
}

/** Owns only window geometry and outside-window touch dispatch, restoring them on disable. */
class LynkDockWindow(private val activity: Activity) {
    private data class Original(val width: Int, val height: Int, val gravity: Int,
        val x: Int, val y: Int, val notTouchModal: Boolean)
    private var original: Original? = null

    @Suppress("DEPRECATION")
    fun apply(enabled: Boolean): Boolean {
        val screen = Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }
        val active = enabled && !activity.isInMultiWindowMode && screen.x > screen.y
        val window = activity.window
        val params = window.attributes
        if (active) {
            if (original == null) original = Original(params.width, params.height, params.gravity,
                params.x, params.y, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            val (width, height) = LynkDockLayout.windowSize(screen.x, screen.y)
            params.width = width
            params.height = height
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.x = 0
            params.y = 0
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            window.attributes = params
        } else original?.let { saved ->
            params.width = saved.width
            params.height = saved.height
            params.gravity = saved.gravity
            params.x = saved.x
            params.y = saved.y
            params.flags = if (saved.notTouchModal) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            window.attributes = params
            original = null
        }
        return active
    }
}
