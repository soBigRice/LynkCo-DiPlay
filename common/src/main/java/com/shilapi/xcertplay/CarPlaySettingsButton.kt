package com.shilapi.xcertplay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import com.shilapi.xcertplay.host.R
import kotlin.math.abs

/** In-app shortcut: dragging never forwards a touch or opens settings, and needs no overlay permission. */
internal class CarPlaySettingsButton(context: Context, onOpen: () -> Unit) : Button(context) {
    private var downX = 0f
    private var downY = 0f
    private var originX = 0f
    private var originY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val inset get() = dp(12).toFloat()

    init {
        text = context.getString(R.string.settings)
        contentDescription = context.getString(R.string.link_settings_shortcut)
        isAllCaps = false
        textSize = 15f
        minWidth = 0
        minHeight = 0
        setPadding(dp(10), 0, dp(10), 0)
        LynkPanelStyle.styleButton(this)
        minHeight = 0
        textSize = 15f
        setPadding(dp(10), 0, dp(10), 0)
        elevation = dp(6).toFloat()
        setOnClickListener { onOpen() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                originX = x; originY = y; dragging = false; isPressed = true
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (abs(dx) > slop || abs(dy) > slop) dragging = true
                if (dragging) { isPressed = false; x = originX + dx; y = originY + dy; constrainToParent() }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) dockToEdge() else performClick()
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { isPressed = false; dockToEdge(); parent.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun constrainToParent() {
        val container = parent as? View ?: return
        x = x.coerceIn(inset, (container.width - width - inset).coerceAtLeast(inset))
        y = y.coerceIn(inset, (container.height - height - inset).coerceAtLeast(inset))
    }

    private fun dockToEdge() {
        val container = parent as? View ?: return
        x = if (x + width / 2f < container.width / 2f) inset else container.width - width - inset
        constrainToParent()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
