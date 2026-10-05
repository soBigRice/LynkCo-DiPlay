package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayVpnGenerationTest {
    @Test fun delayedOldAcceptCannotCreateASessionInTheNewAttachment() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val address = InetAddress.getByName("127.0.0.1")
        val received = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val accepted = AtomicReference<Socket>()
        val staleServer = object : ServerSocket(0, 1, address) {
            override fun accept(): Socket {
                val socket = super.accept()
                accepted.set(socket)
                received.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                return socket
            }
        }
        val config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", port = 0, main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720))
        val identity = AirPlayIdentity.generate()
        val logs = CopyOnWriteArrayList<String>()
        val currentAccepted = CountDownLatch(1)
        val listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) {
                logs += message
                if (message.contains("TCP accepted")) currentAccepted.countDown()
            }
        }
        val media = object : AirPlayMediaHandler {}
        (field(service, "active").get(service) as AtomicBoolean).set(true)
        field(service, "attachGeneration").set(service, 1)
        field(service, "serverSocket").set(service, staleServer)
        val loop = service.javaClass.getDeclaredMethod("acceptLoop", Int::class.javaPrimitiveType, ServerSocket::class.java)
            .apply { isAccessible = true }
        val worker = Thread { loop.invoke(service, 1, staleServer) }.apply { start() }
        val oldPeer = Socket(address, staleServer.localPort)
        try {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            service.detach()
            assertEquals(CarPlayVpnService.AttachResult.Started,
                service.attachWireless(address, config, identity, PairingStore(), null, listener, media))
            val beforeOldAccept = logs.toList()
            resume.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertTrue("Old accepted socket must be rejected", accepted.get().isClosed)
            assertEquals("Old traffic must not reach the new listener", beforeOldAccept, logs.toList())
            assertTrue(service.isAttached())
            Socket(address, requireNotNull(service.boundPort())).use {
                assertTrue("New listener must still accept its own socket", currentAccepted.await(2, TimeUnit.SECONDS))
            }
        } finally {
            resume.countDown()
            oldPeer.close()
            service.detach()
            worker.join(2000)
        }
    }

    private fun field(service: CarPlayVpnService, name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
