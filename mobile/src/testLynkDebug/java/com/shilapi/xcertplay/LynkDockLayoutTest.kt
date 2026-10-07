package com.shilapi.xcertplay

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Switch
import android.widget.Button
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import java.util.concurrent.ExecutorService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-xhdpi")
class LynkDockLayoutTest {
    @Test fun physicalWindowLeaves160PixelsAndRestoresItsOriginalGeometry() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val window = LynkDockWindow(activity)
        val before = WindowManager.LayoutParams().apply { copyFrom(activity.window.attributes) }
        assertTrue(window.apply(true))
        val enabled = activity.window.attributes
        assertEquals(1920, enabled.width)
        assertEquals(920, enabled.height)
        assertEquals(Gravity.TOP or Gravity.LEFT, enabled.gravity)
        assertEquals(0, enabled.y)
        assertTrue(enabled.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        window.apply(true)
        window.apply(false)
        val after = activity.window.attributes
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        assertEquals(before.gravity, after.gravity)
        assertEquals(before.flags, after.flags)
    }

    @Test fun smallerScreenScalesTheViewportWithoutClipping() {
        assertEquals(1920 to 920, LynkDockLayout.windowSize(1920, 1080))
        assertEquals(1280 to 613, LynkDockLayout.windowSize(1280, 720))
        assertEquals(1920 to 920, LynkDockLayout.windowSize(2560, 1440))
    }

    @Test fun settingsSwitchPersistsAcrossRecreationAndKeepsManualPreferences() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveDisplayScalePercent(context, 80)
        AirPlayPersistence.saveSafeAreaDrawOutside(context, false)
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            views(owner.get().window.decorView).filterIsInstance<Button>()
                .single { it.text == owner.get().getString(R.string.lynk_display) }.performClick()
            fun control() = views(owner.get().window.decorView).filterIsInstance<Switch>()
                .single { it.contentDescription == owner.get().getString(R.string.lynk_dock_title) }
            assertFalse(control().isChecked)
            control().performClick()
            assertTrue(LynkDockLayout.enabled(owner.get()))
            assertEquals(920, owner.get().window.attributes.height)
            owner.recreate()
            assertTrue(control().isChecked)
            control().performClick()
            assertFalse(LynkDockLayout.enabled(owner.get()))
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, owner.get().window.attributes.height)
            assertEquals(80, AirPlayPersistence.loadDisplayScalePercent(context))
            assertFalse(AirPlayPersistence.loadSafeAreaDrawOutside(context))
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun hostNegotiatesExactCanvasAndSafeAreaWithoutOverwritingManualSettings() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        fun field(name: String) = CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun load() = CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings")
            .apply { isAccessible = true }.invoke(activity)
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 920)
        fun config() = CarPlayHostActivity::class.java.getDeclaredMethod("createAirPlayConfig", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size) as AirPlayConfig
        try {
            field("airPlayIdentity").set(activity, AirPlayIdentity.generate())
            AirPlayPersistence.saveDisplayScalePercent(activity, 80)
            val manual = SafeAreaRect(20, 10, 1900, 900)
            AirPlayPersistence.saveSafeAreaRect(activity, 1920, 920, manual)
            AirPlayPersistence.saveSafeAreaDrawOutside(activity, false)
            AirPlayPersistence.saveLynkDockEnabled(activity, true)
            load()
            val main = config().main
            assertEquals(1920, main.widthPixels)
            assertEquals(920, main.heightPixels)
            assertEquals(AirPlayInsets(bottom = 40), main.safeArea)
            assertEquals(true, main.safeAreaDrawOutside)
            assertEquals(80, AirPlayPersistence.loadDisplayScalePercent(activity))
            assertEquals(manual, AirPlayPersistence.loadSafeAreaRect(activity, 1920, 920))
            assertFalse(AirPlayPersistence.loadSafeAreaDrawOutside(activity))
            AirPlayPersistence.saveLynkDockEnabled(activity, false)
            load()
            val restored = config().main
            assertEquals(1536, restored.widthPixels)
            assertEquals(736, restored.heightPixels)
            assertEquals(false, restored.safeAreaDrawOutside)
        } finally {
            listOf("teardownExecutor", "airPlayCommandExecutor").forEach {
                (field(it).get(activity) as ExecutorService).shutdownNow()
            }
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
