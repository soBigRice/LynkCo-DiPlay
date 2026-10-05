package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/** Presentation tokens shared by the Lynk dashboard, waiting panel and in-app shortcut. */
internal object LynkPanelStyle {
    val background = Color.rgb(18, 21, 25)
    val surface = Color.rgb(28, 32, 37)
    val raised = Color.rgb(39, 45, 51)
    val border = Color.rgb(47, 54, 61)
    val accent = Color.rgb(174, 213, 220)
    val success = Color.rgb(151, 214, 181)
    val warning = Color.rgb(241, 186, 121)
    val text = Color.rgb(245, 246, 247)
    val muted = Color.rgb(174, 182, 189)

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun shape(context: Context, color: Int = surface, stroke: Int = color, radius: Int = 12) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radius).toFloat()
            setStroke(dp(context, 1), stroke)
        }

    fun styleButton(button: Button, primary: Boolean = false) = with(button) {
        isAllCaps = false
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (primary) LynkPanelStyle.background else LynkPanelStyle.text),
        ))
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
            shape(context, if (primary) accent else raised, if (primary) accent else border, 12), null)
        minHeight = dp(context, 56)
        minWidth = 0
        setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6))
        stateListAnimator = null
    }

    fun label(context: Context, value: String, size: Int, color: Int = text, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(dp(context, 3).toFloat(), 1f)
        }
}

internal enum class LynkSettingsGroup { GENERAL, DISPLAY, AUDIO, SUPPORT }
