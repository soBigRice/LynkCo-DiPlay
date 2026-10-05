package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsbReenumerationDeadlineTest {
    @Test fun absentReenumeratedPhoneReportsFailureAndStopsPolling() = withController { controller, statuses ->
        poll(controller, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertTrue((statuses.single() as CarPlayStatus.Failed).message.contains("CarPlay USB configuration"))
        assertEquals("IDLE", field(controller, "phase").get(controller).toString())
        poll(controller, 0) // A delayed callback cannot restart a failed attempt.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, statuses.size)
    }

    @Test fun anOldGenerationCannotFailANewerUsbAttempt() = withController { controller, statuses ->
        (field(controller, "availabilityPollGeneration").get(controller) as AtomicInteger).incrementAndGet()
        poll(controller, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
        assertEquals("REENUMERATION", field(controller, "phase").get(controller).toString())
    }

    @Test fun cancellationIgnoresThePendingDeadline() = withController { controller, statuses ->
        controller.close()
        assertTrue(controller.awaitClosed(2000))
        poll(controller, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
    }

    private fun poll(controller: CarPlayController, generation: Int) {
        controller.javaClass.getDeclaredMethod("pollReenumeration", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, generation, System.nanoTime() - 1)
    }
    private fun field(controller: CarPlayController, name: String) = controller.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun withController(check: (CarPlayController, MutableList<CarPlayStatus>) -> Unit) {
        val statuses = mutableListOf<CarPlayStatus>()
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, initialHandshakeTimeoutMillis = 60_000,
                identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
            { statuses.add(it) })
        val phase = field(controller, "phase")
        phase.set(controller, phase.type.enumConstants.single { it.toString() == "REENUMERATION" })
        try { check(controller, statuses) } finally { controller.close(); controller.awaitClosed(2000) }
    }
}
