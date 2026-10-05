package com.shilapi.xcertplay

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land")
class LynkPanelNavigationTest {
    @Test fun connectionPanelFitsWhenWindowNarrowsWithoutRebuilding() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val panel = CarPlayHostActivity::class.java.getDeclaredMethod("buildLynkConnectionPanel")
                .apply { isAccessible = true }.invoke(activity) as ViewGroup
            val frame = panel.getChildAt(0) as ViewGroup
            val content = frame.getChildAt(0)
            val density = activity.resources.displayMetrics.density
            fun resize(widthDp: Int) {
                repeat(2) {
                    panel.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((540 * density).toInt(), View.MeasureSpec.EXACTLY))
                    panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                }
            }
            resize(960)
            resize(540)
            assertTrue("Content must fit the current window: width=${content.width} requested=${content.layoutParams.width} available=${frame.width - frame.paddingLeft - frame.paddingRight}", content.width <= frame.width - frame.paddingLeft - frame.paddingRight)
            assertSame("Resize must reuse the panel", content, frame.getChildAt(0))
        } finally {
            listOf("teardownExecutor", "airPlayCommandExecutor").forEach { name ->
                (CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(activity) as java.util.concurrent.ExecutorService).shutdownNow()
            }
        }
    }

    @Test fun everySettingsCategoryRetainsItsControlsWithoutChangingPreferences() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = owner.get()
            val scale = AirPlayPersistence.loadDisplayScaleTenths(activity)
            val buffer = AirPlayPersistence.loadMediaBufferMillis(activity)
            val fps = AirPlayPersistence.loadFps(activity)
            click(activity, R.string.settings)
            assertHas(activity, R.string.automatic_connection)
            assertHas(activity, R.string.language_section_title)
            click(activity, R.string.lynk_display)
            assertHas(activity, R.string.display_and_performance)
            assertFalse(texts(activity).contains(activity.getString(R.string.audio_routing)))
            click(activity, R.string.lynk_audio)
            assertHas(activity, R.string.audio_routing)
            click(activity, R.string.lynk_support)
            assertHas(activity, R.string.save_diagnostic_report)
            assertHas(activity, R.string.choose_save_location)
            assertHas(activity, R.string.app_permissions)
            click(activity, R.string.lynk_connection)
            assertHas(activity, R.string.auto_hotspot_start)
            assertFalse(texts(activity).contains(activity.getString(R.string.link_save_connect)))
            click(activity, R.string.auto_hotspot_manual)
            assertHas(activity, R.string.link_save_connect)
            assertHas(activity, R.string.link_local_bt_title)
            assertEquals(scale, AirPlayPersistence.loadDisplayScaleTenths(activity))
            assertEquals(buffer, AirPlayPersistence.loadMediaBufferMillis(activity))
            assertEquals(fps, AirPlayPersistence.loadFps(activity))
        } finally { owner.destroy() }
    }

    @Test fun wiredActionStillUsesWiredStartupWhenWirelessWasSaved() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = owner.get()
            DiPlayActivity::class.java.getDeclaredField("setupError").apply { isAccessible = true }.set(activity, null)
            AirPlayPersistence.saveWirelessEnabled(activity, true)
            click(activity, R.string.lynk_wired)
            assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(activity).nextStartedActivity?.component?.className)
        } finally { owner.destroy() }
    }

    @Test fun selectedSettingsCategorySurvivesRecreation() {
        val first = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        val state = Bundle()
        try {
            click(first.get(), R.string.settings)
            click(first.get(), R.string.lynk_audio)
            first.saveInstanceState(state)
        } finally { first.destroy() }
        val next = Robolectric.buildActivity(DiPlayActivity::class.java).create(state)
        try { assertHas(next.get(), R.string.audio_routing) } finally { next.destroy() }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts(activity: DiPlayActivity) = views(activity.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }
    private fun assertHas(activity: DiPlayActivity, text: Int) =
        assertTrue(activity.getString(text), activity.getString(text) in texts(activity))
    private fun click(activity: DiPlayActivity, text: Int) {
        val buttons = views(activity.window.decorView).filterIsInstance<Button>().filter { it.text == activity.getString(text) }
        assertEquals("One reachable action: ${activity.getString(text)}", 1, buttons.size)
        assertTrue(buttons.single().performClick())
    }
}
