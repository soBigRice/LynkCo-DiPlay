package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioOutputDiagnosticsTest {
    @Test fun snapshotIsBoundedAndDoesNotChangeFocusRoutingOrVolume() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setOutputDevices(List(20) {
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUS).build().also { device ->
                // Robolectric's builder omits pre-31 port capabilities; Android supplies arrays.
                val port = ReflectionHelpers.getField<Any>(device, "mPort")
                for (field in listOf("mSamplingRates", "mChannelMasks", "mChannelIndexMasks", "mFormats"))
                    ReflectionHelpers.setField(port, field, intArrayOf())
            }
        })
        val before = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val lines = AudioOutputDiagnostics.inventory(context)
        assertTrue(lines.any { it == "Audio devices count=20 shownLimit=8" })
        assertEquals(lines.joinToString("\n"), 8, lines.count { it.startsWith("Audio device id=") })
        assertTrue(lines.any { it.contains("focusOwner=not_exposed_to_app") })
        assertTrue(lines.any { it.contains("visibility=platform_filtered") })
        assertEquals(before, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertNull(shadow.lastAudioFocusRequest)
        assertFalse(lines.any { it.contains("productName=") || it.contains("address=") })
        assertTrue(lines.all { it.length < 700 })
    }
}
