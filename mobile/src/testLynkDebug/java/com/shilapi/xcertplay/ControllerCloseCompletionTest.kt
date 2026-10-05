package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.CarPlayVpnService
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.ParcelFileDescriptor
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ControllerCloseCompletionTest {
    @Test fun attachmentPublishedAfterFirstDetachIsReleasedBeforeCompletion() {
        val firstDetach = CountDownLatch(1)
        val controller = controller(object : AirPlaySessionListener {
            override fun onDebugLog(message: String) {
                if (message.contains("resource=VPN/NCM completed=true")) firstDetach.countDown()
            }
        })
        val service = org.robolectric.Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        field(controller, "vpnService").set(controller, service)
        field(controller, "vpnBound").setBoolean(controller, true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pipe = ParcelFileDescriptor.createPipe()
        val worker = field(controller, "executor").get(controller) as ExecutorService
        val lateAttach = worker.submit {
            // The wired worker retains a local service reference even after unbind clears the field.
            entered.countDown()
            while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
            assertTrue(firstDetach.await(2, TimeUnit.SECONDS))
            // Use a real loopback listener and owned fd; no USB/TUN hardware is needed to replay ownership.
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                InetAddress.getLoopbackAddress(),
                AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                    sourceVersion = "1", port = 0, main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720)),
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}))
            service.javaClass.getDeclaredField("tun").apply { isAccessible = true }.set(service, pipe[0])
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.close()
            assertTrue(firstDetach.await(2, TimeUnit.SECONDS))
            assertNull(field(controller, "vpnService").get(controller))
            release.countDown()
            lateAttach.get(2, TimeUnit.SECONDS)
            assertTrue(controller.awaitClosed(2000))
            assertFalse("Late attachment must be released before reporting closed", service.isAttached())
            assertNull(service.boundPort())
            assertNull(service.javaClass.getDeclaredField("tun").apply { isAccessible = true }.get(service))
        } finally {
            release.countDown()
            controller.close(); controller.awaitClosed(2000)
            service.onDestroy()
            pipe.forEach { it.close() }
        }
    }

    @Test fun unfinishedUsbWorkerCannotReportTeardownComplete() {
        val controller = controller()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lateReleased = AtomicBoolean()
        val completed = CountDownLatch(1)
        val calls = AtomicInteger()
        controller.whenClosed { calls.incrementAndGet(); completed.countDown() }
        val executor = field(controller, "executor").get(controller) as ExecutorService
        executor.execute {
            entered.countDown()
            // Model a native USB call which cannot be cancelled by Thread.interrupt().
            while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
            field(controller, "mfiSession").set(controller, MfiSession(object : MfiAuthenticator {
                override fun protocolMajor() = 2
                override fun readCertificate(maximumOutputLength: Int) = byteArrayOf()
                override fun signChallenge(challenge: ByteArray) = byteArrayOf()
            }, java.io.Closeable { lateReleased.set(true) }))
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.close()
            assertFalse("A timeout is not resource release", controller.awaitClosed(2300))
            assertFalse(executor.isTerminated)
            assertEquals(0, calls.get())
            release.countDown()
            assertTrue(controller.awaitClosed(2000))
            assertTrue(executor.isTerminated)
            assertTrue(lateReleased.get())
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            controller.whenClosed { calls.incrementAndGet() }
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            controller.close()
            assertTrue(controller.awaitClosed(2000))
        }
    }

    @Test fun delayedServiceBindingCannotResurrectAClosedController() {
        val controller = controller()
        controller.close()
        assertTrue(controller.awaitClosed(2000))
        val callback = field(controller, "serviceConnection").get(controller) as ServiceConnection
        val service = org.robolectric.Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        callback.onServiceConnected(ComponentName(RuntimeEnvironment.getApplication(), CarPlayVpnService::class.java), service.LocalBinder())
        assertNull(field(controller, "vpnService").get(controller))
        service.onDestroy()
    }

    private fun controller(listener: AirPlaySessionListener = object : AirPlaySessionListener {}) = CarPlayController(RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 3)),
        AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720)),
        AirPlayIdentity.generate(), PairingStore(), listener, object : AirPlayMediaHandler {}, {})

    private fun field(controller: CarPlayController, name: String) = controller.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
