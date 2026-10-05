package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayRestartBarrierTest {
    @Test fun restartWaitsPastUiDeadlineThenCreatesExactlyOneReplacement() = checkRestart(cancel = false)
    @Test fun cancellationDuringDelayedCloseCannotRestartTheConnection() = checkRestart(cancel = true)

    private fun checkRestart(cancel: Boolean) {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        // get() skips onCreate; initialize its identity explicitly before exercising a real restart.
        val identity = AirPlayIdentity.generate()
        field(activity, "airPlayIdentity").set(activity, identity)
        // This tests controller ownership, not MFi authentication; source tests need no private assets.
        val sink = AndroidMediaSink()
        val old = CarPlayController(activity,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720)),
            identity, PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        val released = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val worker = field(old, "executor").get(old) as ExecutorService
        worker.execute {
            entered.countDown()
            while (released.count != 0L) try { released.await() } catch (_: InterruptedException) { }
        }
        val teardown = field(activity, "teardownExecutor").get(activity) as ExecutorService
        val closing = field(activity, "shuttingDown").get(activity) as AtomicBoolean
        val backgroundType = Class.forName("com.shilapi.xcertplay.CarPlayBackgroundSession")
        val background = requireNotNull(backgroundType.getField("INSTANCE").get(null))
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val sizeType = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
            val size = sizeType.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(1280, 720)
            field(activity, "activeDisplaySize").set(activity, size)
            // Model a previously connected session; resume rechecks these prerequisites.
            field(activity, "vpnReady").setBoolean(activity, true)
            field(activity, "microphonePermissionResolved").setBoolean(activity, true)
            field(activity, "controller").set(activity, old)
            field(activity, "sink").set(activity, sink)
            field(background, "owner").set(background, activity)
            activity.javaClass.getDeclaredMethod("restartCarPlay", String::class.java)
                .apply { isAccessible = true }.invoke(activity, "Synthetic delayed USB close")
            // Wait until the production 4-second UI threshold expires, without holding the UI thread.
            teardown.submit {}.get(6, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(field(activity, "handshakeResetInProgress").getBoolean(activity))
            assertNull("No new controller may overlap old resources", field(activity, "controller").get(activity))
            if (cancel) closing.set(true)
            released.countDown()
            assertTrue(old.awaitClosed(2000))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!(field(sink, "recoveryExecutor").get(sink) as ExecutorService).isShutdown && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue((field(sink, "recoveryExecutor").get(sink) as ExecutorService).isShutdown)
            // Completion posts its UI update after releasing the old sink.
            val uiDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!cancel && field(activity, "controller").get(activity) == null && System.nanoTime() < uiDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            shadowOf(Looper.getMainLooper()).idle()
            val next = field(activity, "controller").get(activity)
            if (cancel) assertNull(next) else {
                assertNotNull(next)
                assertNotSame(old, next)
                assertFalse(field(activity, "handshakeResetInProgress").getBoolean(activity))
                old.close() // Duplicate close cannot trigger another replacement.
                shadowOf(Looper.getMainLooper()).idle()
                assertSame(next, field(activity, "controller").get(activity))
            }
        } finally {
            closing.set(true)
            released.countDown()
            old.close(); old.awaitClosed(2000)
            (field(activity, "controller").get(activity) as? CarPlayController)?.let { it.close(); it.awaitClosed(2000) }
            (field(activity, "sink").get(activity) as? AndroidMediaSink)?.close()
            sink.close()
            teardown.shutdownNow()
            (field(activity, "airPlayCommandExecutor").get(activity) as ExecutorService).shutdownNow()
            backgroundType.getDeclaredMethod("clear", CarPlayController::class.java, Boolean::class.javaPrimitiveType)
                .invoke(background, null, false)
        }
    }

    private fun field(instance: Any, name: String) = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
