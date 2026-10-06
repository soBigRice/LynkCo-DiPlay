package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land")
class LynkRememberedConnectionTest {
    private fun prefs(activity: DiPlayActivity) = activity.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    private fun adapter(activity: DiPlayActivity) = activity.getSystemService(BluetoothManager::class.java).adapter
    private fun phone(activity: DiPlayActivity, address: String, name: String) = adapter(activity).getRemoteDevice(address).also {
        shadowOf(it).setName(name)
    }
    private fun connect(activity: DiPlayActivity, wireless: Boolean = true) {
        activity.javaClass.getDeclaredField("setupError").apply { isAccessible = true }.set(activity, null)
        activity.javaClass.getDeclaredMethod("connect", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, wireless)
    }
    private fun assertHost(activity: DiPlayActivity) {
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun renamedSavedPhoneMatchesIgnoringAddressCaseAndSurvivesReopening() {
        repeat(2) { index ->
            val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
            try {
                val activity = owner.get()
                val phone = phone(activity, "AA:22:33:44:55:66", "Rice 的手机")
                shadowOf(adapter(activity)).setEnabled(true)
                shadowOf(adapter(activity)).setBondedDevices(setOf(phone))
                if (index == 0) prefs(activity).edit().putString("phone_address", phone.address.lowercase())
                    .putString("phone_name", "Old name").commit()
                connect(activity)
                assertHost(activity)
                assertEquals(phone.address, prefs(activity).getString("phone_address", null))
                assertEquals("Rice 的手机", prefs(activity).getString("phone_name", null))
                assertNull(ShadowAlertDialog.getLatestAlertDialog())
            } finally { owner.destroy() }
        }
    }

    @Test fun soleIphoneIsRememberedWithoutOpeningPicker() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = owner.get()
            val phone = phone(activity, "AA:22:33:44:55:66", "Rice iPhone")
            val headset = phone(activity, "AA:22:33:44:55:77", "Headphones")
            shadowOf(adapter(activity)).setEnabled(true)
            shadowOf(adapter(activity)).setBondedDevices(setOf(phone, headset))
            connect(activity)
            assertHost(activity)
            assertEquals(phone.address, prefs(activity).getString("phone_address", null))
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        } finally { owner.destroy() }
    }

    @Test fun multiplePhonesNeedOneSelectionThenReuseIt() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = owner.get()
            val first = phone(activity, "AA:22:33:44:55:66", "A iPhone")
            val second = phone(activity, "AA:22:33:44:55:77", "B iPhone")
            shadowOf(adapter(activity)).setEnabled(true)
            shadowOf(adapter(activity)).setBondedDevices(setOf(first, second))
            connect(activity)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(prefs(activity).getString("phone_address", null))
            shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1)
            assertHost(activity)
            assertEquals(second.address, prefs(activity).getString("phone_address", null))
            connect(activity)
            assertHost(activity)
        } finally { owner.destroy() }
    }

    @Test fun missingSavedPhoneAndHeadsetAreNeverSilentlyReplaced() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = owner.get()
            prefs(activity).edit().putString("phone_address", "AA:22:33:44:55:66").commit()
            shadowOf(adapter(activity)).setEnabled(true)
            shadowOf(adapter(activity)).setBondedDevices(setOf(phone(activity, "AA:22:33:44:55:77", "Another iPhone")))
            connect(activity)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals("AA:22:33:44:55:66", prefs(activity).getString("phone_address", null))
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            prefs(activity).edit().remove("phone_address").commit()
            shadowOf(adapter(activity)).setBondedDevices(setOf(phone(activity, "AA:22:33:44:55:88", "Headphones")))
            connect(activity)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(prefs(activity).getString("phone_address", null))
        } finally { owner.destroy() }
    }

    @Test fun enablingBluetoothResumesSavedPhoneWithoutRePairing() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create().start().resume()
        try {
            val activity = owner.get()
            val phone = phone(activity, "AA:22:33:44:55:66", "Rice")
            prefs(activity).edit().putString("phone_address", phone.address).commit()
            shadowOf(adapter(activity)).setEnabled(false)
            connect(activity)
            assertEquals(BluetoothAdapter.ACTION_REQUEST_ENABLE, shadowOf(activity).nextStartedActivity?.action)
            assertEquals(phone.address, prefs(activity).getString("phone_address", null))
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            shadowOf(adapter(activity)).setEnabled(true)
            shadowOf(adapter(activity)).setBondedDevices(setOf(phone))
            activity.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertHost(activity)
            activity.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("Only one start", shadowOf(activity).nextStartedActivity)
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun firstPairingContinuesAutomaticallyButLeavingGuideCancelsPendingStart() {
        for (cancel in listOf(false, true)) {
            val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create().start().resume()
            try {
                val activity = owner.get()
                prefs(activity).edit().clear().commit()
                shadowOf(adapter(activity)).setEnabled(true)
                shadowOf(adapter(activity)).setBondedDevices(emptySet())
                connect(activity)
                assertNull(shadowOf(activity).nextStartedActivity)
                if (cancel) views(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.lynk_home) }.performClick()
                shadowOf(adapter(activity)).setBondedDevices(setOf(phone(activity, "AA:22:33:44:55:66", "Rice iPhone")))
                activity.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                shadowOf(Looper.getMainLooper()).idle()
                if (cancel) assertNull(shadowOf(activity).nextStartedActivity) else assertHost(activity)
            } finally { owner.pause().stop().destroy() }
        }
    }

    @Test fun applyingSettingsStillClosesExistingSessionBeforeStartingAgain() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        val type = Class.forName("com.shilapi.xcertplay.CarPlayBackgroundSession")
        val background = type.getField("INSTANCE").get(null)
        val action = type.getDeclaredField("stopAction").apply { isAccessible = true }
        var stopped = false
        var complete: (() -> Unit)? = null
        try {
            val activity = owner.get()
            AirPlayPersistence.saveWirelessEnabled(activity, false)
            action.set(background, { callback: () -> Unit -> stopped = true; complete = callback })
            connect(activity, false)
            assertTrue(stopped)
            assertNull("Must wait for actual close", shadowOf(activity).nextStartedActivity)
            action.set(background, null)
            complete!!.invoke()
            assertHost(activity)
        } finally { action.set(background, null); owner.destroy() }
    }

    @Test fun pendingFirstConnectionSurvivesActivityRecreation() {
        val state = android.os.Bundle()
        val first = Robolectric.buildActivity(DiPlayActivity::class.java).create().start().resume()
        try {
            shadowOf(adapter(first.get())).setEnabled(true)
            shadowOf(adapter(first.get())).setBondedDevices(emptySet())
            connect(first.get())
            first.saveInstanceState(state)
            // A restored in-progress guide owns startup, even if home auto-connect is on.
            prefs(first.get()).edit().putBoolean("auto_connect", true).commit()
        } finally { first.pause().stop().destroy() }
        val next = Robolectric.buildActivity(DiPlayActivity::class.java).create(state)
        try {
            val activity = next.get()
            activity.javaClass.getDeclaredField("setupError").apply { isAccessible = true }.set(activity, null)
            shadowOf(adapter(activity)).setEnabled(true)
            shadowOf(adapter(activity)).setBondedDevices(setOf(phone(activity, "AA:22:33:44:55:66", "Rice iPhone")))
            next.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertHost(activity)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { next.pause().stop().destroy() }
    }

    @Test fun existingWirelessSessionOpensWithoutBluetoothOrStopping() {
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        val type = Class.forName("com.shilapi.xcertplay.CarPlayBackgroundSession")
        val background = type.getField("INSTANCE").get(null)
        val action = type.getDeclaredField("stopAction").apply { isAccessible = true }
        var stopped = false
        try {
            val activity = owner.get()
            shadowOf(adapter(activity)).setEnabled(false)
            AirPlayPersistence.saveWirelessEnabled(activity, true)
            action.set(background, { _: () -> Unit -> stopped = true })
            activity.javaClass.getDeclaredMethod("startLynkConnection", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, true)
            assertHost(activity)
            assertFalse(stopped)
            assertFalse(adapter(activity).isEnabled)
        } finally { action.set(background, null); owner.destroy() }
    }
}
