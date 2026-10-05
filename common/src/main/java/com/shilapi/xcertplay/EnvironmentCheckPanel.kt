package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.shilapi.xcertplay.host.R
import java.text.DateFormat
import java.util.Date

/** Renders immutable results; actions are owned by the host so checks cannot change connection settings. */
internal object EnvironmentCheckPanel {
    fun render(
        parent: LinearLayout, report: EnvironmentReport?, running: Boolean, failed: Boolean,
        group: EnvironmentGroup, select: (EnvironmentGroup) -> Unit, scan: () -> Unit,
        export: () -> Unit, action: (EnvironmentAction) -> Unit,
    ) {
        val c = parent.context
        fun label(id: Int, size: Int, color: Int = LynkPanelStyle.text) = LynkPanelStyle.label(c, c.getString(id), size, color)
        fun button(title: Int, primary: Boolean = false, click: () -> Unit) = Button(c).apply {
            text = c.getString(title); LynkPanelStyle.styleButton(this, primary); setOnClickListener { click() }
        }
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        if (running) header.addView(ProgressBar(c), LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)).apply { marginEnd = dp(c, 12) })
        val heading = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label(R.string.env_title, 26))
        heading.addView(LynkPanelStyle.label(c, when {
            running -> c.getString(R.string.env_scanning)
            failed -> c.getString(R.string.env_failed)
            report != null -> c.getString(R.string.env_summary,
                report.items.count { it.state == EnvironmentState.ACTION }, report.items.count { it.state == EnvironmentState.VERIFY })
            else -> c.getString(R.string.env_home_idle)
        }, 14, LynkPanelStyle.muted), lp(c, top = 6))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(R.string.env_scan, true, scan).apply { isEnabled = !running },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 12) })
        parent.addView(header, lp(c, bottom = 16))
        if (report == null) return
        val codec = if (report.facts.codec == "video/hevc") "HEVC" else "H.264"
        parent.addView(LynkPanelStyle.label(c,
            c.getString(R.string.env_device, report.facts.device, report.facts.api, codec), 13, LynkPanelStyle.muted), lp(c, bottom = 16))
        val categories = LinearLayout(c)
        EnvironmentGroup.entries.forEach { candidate ->
            categories.addView(button(candidate.title, group == candidate) { select(candidate) }.apply {
                isSelected = group == candidate
                text = "${c.getString(candidate.title)} · ${c.getString(report.state(candidate).title)}"
                textSize = 14f
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(c, 8) })
        }
        parent.addView(categories, lp(c, bottom = 12))
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; background = LynkPanelStyle.shape(c)
            setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 4))
        }
        report.items.filter { it.group == group }.forEachIndexed { index, item ->
            if (index > 0) card.addView(View(c).apply { setBackgroundColor(LynkPanelStyle.border) }, LinearLayout.LayoutParams(-1, dp(c, 1)))
            val line = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(c, 16), 0, dp(c, 16)) }
            val copy = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val color = when (item.state) {
                EnvironmentState.PASS -> LynkPanelStyle.success
                EnvironmentState.ACTION -> LynkPanelStyle.warning
                EnvironmentState.VERIFY -> LynkPanelStyle.muted
            }
            copy.addView(LynkPanelStyle.label(c, "${c.getString(item.title)}   ·   ${c.getString(item.state.title)}", 17, color, true))
            copy.addView(label(item.detail, 14, LynkPanelStyle.muted), lp(c, top = 5))
            line.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            item.action?.let { remedy ->
                line.addView(button(remedy.title) { action(remedy) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 16) })
            }
            card.addView(line)
        }
        parent.addView(card)
        parent.addView(label(R.string.env_scope, 13, LynkPanelStyle.muted), lp(c, top = 16))
        parent.addView(LynkPanelStyle.label(c, c.getString(R.string.env_time,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.capturedAt))), 12, LynkPanelStyle.muted), lp(c, top = 8))
        parent.addView(button(R.string.link_export, click = export), lp(c, top = 16))
    }

    private fun dp(c: Context, value: Int) = LynkPanelStyle.dp(c, value)
    private fun lp(c: Context, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(c, top); bottomMargin = dp(c, bottom)
    }
}
