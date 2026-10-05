package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomaticHotspotHandoffTest {
    @Test fun requestBeforeTunnelDisablesOnlyAfterBothAndRestoresOnClose() = handoff(true)
    @Test fun tunnelBeforeRequestDisablesOnlyAfterBothAndRestoresOnClose() = handoff(false)

    private fun handoff(requestFirst: Boolean) {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val controller = controller(activity)
        val off = CountDownLatch(1)
        val state = AtomicInteger(BluetoothAdapter.STATE_ON)
        val disabled = AtomicInteger(); val enabled = AtomicInteger(); val pending = AtomicBoolean()
        val leaseType = Class.forName("com.shilapi.xcertplay.network.BluetoothRadioLease")
        val portType = Class.forName("com.shilapi.xcertplay.network.BluetoothRadioLease\$Port")
        val port = Proxy.newProxyInstance(portType.classLoader, arrayOf(portType)) { _, method, args ->
            when (method.name) {
                "getState" -> state.get()
                "getPending" -> pending.get()
                "journal" -> { pending.set(args!![0] as Boolean); true }
                "disable" -> { disabled.incrementAndGet(); state.set(BluetoothAdapter.STATE_OFF); off.countDown(); true }
                "enable" -> { enabled.incrementAndGet(); state.set(BluetoothAdapter.STATE_ON); true }
                "await" -> state.get() == args!![0]
                else -> error(method.name)
            }
        }
        val released: (Any) -> Unit = {}
        val lease = leaseType.declaredConstructors.single { it.parameterCount == 3 }.newInstance(port, Any(), released)
        field(controller, "localHotspotBluetooth").set(controller, lease)
        val phase = field(controller, "phase")
        phase.set(controller, phase.type.enumConstants.single { it.toString() == "WIRELESS" })
        val request = field(controller, "wirelessHandoffRequested").get(controller) as AtomicBoolean
        val tunnel = field(controller, "wirelessTunnelReady").get(controller) as AtomicBoolean
        val complete = controller.javaClass.getDeclaredMethod("maybeCompleteWirelessHandoff").apply { isAccessible = true }
        try {
            if (requestFirst) request.set(true) else tunnel.set(true)
            complete.invoke(controller)
            assertEquals(0, disabled.get())
            request.set(true); tunnel.set(true)
            complete.invoke(controller)
            assertTrue(off.await(2, TimeUnit.SECONDS))
            complete.invoke(controller)
            assertEquals(1, disabled.get())
            controller.close(); assertTrue(controller.awaitClosed(2000))
            assertEquals(BluetoothAdapter.STATE_ON, state.get()); assertFalse(pending.get())
            assertEquals(1, enabled.get())
            complete.invoke(controller)
            assertEquals(1, disabled.get())
        } finally { controller.close(); controller.awaitClosed(2000); dispose(activity) }
    }

    @Test fun cancelCompletionWaitsForActualControllerRelease() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val controller = controller(activity)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = AtomicBoolean()
        (field(controller, "executor").get(controller) as ExecutorService).execute {
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
        }
        field(activity, "controller").set(activity, controller)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val callback: () -> Unit = { completed.set(true) }
            activity.javaClass.declaredMethods.single { it.name == "shutdown" }.apply { isAccessible = true }
                .invoke(activity, false, "test cancellation", callback)
            (field(activity, "teardownExecutor").get(activity) as ExecutorService).submit {}.get(2, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(completed.get())
            release.countDown(); assertTrue(controller.awaitClosed(2000))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!completed.get() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.yield()
            }
            assertTrue(completed.get())
        } finally { release.countDown(); controller.close(); controller.awaitClosed(2000); dispose(activity) }
    }

    private fun controller(activity: CarPlayHostActivity) = CarPlayController(activity,
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 3)),
        AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
    private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun dispose(activity: CarPlayHostActivity) {
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
            (field(activity, name).get(activity) as ExecutorService).shutdownNow()
    }
}
