package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
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
class LynkAppUpdateUiTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun click(activity: DiPlayActivity, text: Int) {
        views(activity.window.decorView).filterIsInstance<Button>().single { it.text == activity.getString(text) }.performClick()
    }
    @Test fun supportHasUpdateControlsAndUsesLynkWebsiteWithoutChangingConnectionSettings() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = owner.get()
            val oldScale = AirPlayPersistence.loadDisplayScaleTenths(activity)
            val oldBuffer = AirPlayPersistence.loadMediaBufferMillis(activity)
            click(activity, R.string.settings)
            click(activity, R.string.lynk_support)
            val all = views(activity.window.decorView)
            assertTrue(all.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.lynk_update_title) })
            assertTrue(all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.lynk_update_check) }.isEnabled)
            assertEquals(View.GONE, all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.lynk_update_download) }.visibility)
            val toggle = all.filterIsInstance<Switch>().single { it.contentDescription == activity.getString(R.string.lynk_update_auto) }
            assertTrue(toggle.isChecked)
            toggle.performClick()
            assertFalse(activity.getSharedPreferences("lynk_app_updates", 0).getBoolean("automatic", true))
            click(activity, R.string.lynk_update_website)
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://soBigRice.github.io/LynkCo-DiPlay/", intent.data.toString())
            assertEquals(oldScale, AirPlayPersistence.loadDisplayScaleTenths(activity))
            assertEquals(oldBuffer, AirPlayPersistence.loadMediaBufferMillis(activity))
        } finally { owner.destroy() }
    }
}
