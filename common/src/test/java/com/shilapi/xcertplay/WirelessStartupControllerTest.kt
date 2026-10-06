package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupControllerTest {
    private val statuses = mutableListOf<CarPlayStatus>()
    private fun controller() = CarPlayController(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
    },
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add)

    private fun fail(controller: CarPlayController, generation: Int, error: Throwable) {
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, error, generation)
    }

    @Test fun timeoutAndCleanupExceptionProduceOnlyOneTypedFailure() {
        val controller = controller()
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            fail(controller, 0, IOException("Bluetooth socket closed"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, statuses.size)
            assertEquals(WirelessStartupFailure.FIRST_TCP_TIMEOUT, (statuses.single() as CarPlayStatus.Failed).startupFailure)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun queuedOldFailureAndOldCleanupDoNotAffectNewGeneration() {
        val controller = controller()
        var closed = 0
        val hotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not started")
            override fun close() { closed++ }
        }
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
            ReflectionHelpers.setField(controller, "hotspot", hotspot)
            controller.javaClass.getDeclaredMethod("closeWirelessStack", CarPlayVpnService::class.java, Int::class.javaObjectType)
                .apply { isAccessible = true }.invoke(controller, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
            assertEquals(0, closed)
            assertSame(hotspot, ReflectionHelpers.getField(controller, "hotspot"))
            fail(controller, 0, IOException("late old failure"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun type130MayArriveBeforeRecordButOnlyOneTunnelCanOwnTheRun() {
        val controller = controller()
        val pool = Executors.newFixedThreadPool(4)
        val streams = List(4) { TestStream(ReflectionHelpers.getField(controller, "wirelessResourceLock")) }
        try {
            assertNull(ReflectionHelpers.getField<Any?>(controller, "activeSession"))
            val owner = tunnelOwner(controller)
            val gate = CountDownLatch(1)
            val attempts = streams.map { stream -> pool.submit<Boolean> { gate.await(); openTunnel(controller, owner, stream) } }
            gate.countDown()
            assertEquals(1, attempts.count { it.get(2, TimeUnit.SECONDS) })
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            assertEquals(1, streams.count { it.closed.count == 0L })
            assertTrue("Native/link close must not wait while holding the callback lock", streams.none { it.closedWithCallbackLock })
        } finally {
            controller.close(); assertTrue(controller.awaitClosed(2_000))
            streams.forEach { it.close() }; pool.shutdownNow()
        }
    }

    @Test fun retiredTunnelCallbackCannotBorrowTheNextRunsAuthenticationOrOpenIo() {
        val controller = controller()
        val stream = TestStream(ReflectionHelpers.getField(controller, "wirelessResourceLock"))
        try {
            val old = tunnelOwner(controller)
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
            tunnelOwner(controller, 1)
            assertFalse(openTunnel(controller, old, stream))
            assertEquals(0, stream.sends.get())
            controller.close(); assertTrue(controller.awaitClosed(2_000))
            assertFalse(openTunnel(controller, old, stream))
        } finally { controller.close(); assertTrue(controller.awaitClosed(2_000)); stream.close() }
    }

    private fun tunnelOwner(controller: CarPlayController, generation: Int = 0): Any {
        val identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 3)
        val endpoint = Iap2WirelessCarPlayEndpoint("test", "testpass", 6, Iap2WirelessSecurity.WPA_WPA2,
            listOf("192.168.43.1"), 7000, "test", "test", "1")
        val authenticator = object : MfiAuthenticator {
            override fun protocolMajor() = 2
            override fun readCertificate(maximumOutputLength: Int) = error("No phone authentication expected")
            override fun signChallenge(challenge: ByteArray) = error("No phone authentication expected")
        }
        val type = Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController\$WirelessTunnelOwner")
        return type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(generation, identification, endpoint, authenticator).also {
                ReflectionHelpers.setField(controller, "wirelessTunnelOwner", it)
            }
    }
    private fun openTunnel(controller: CarPlayController, owner: Any, stream: BlockingDuplexByteStream): Boolean =
        controller.javaClass.getDeclaredMethod("startWirelessTunnelControl", owner.javaClass, BlockingDuplexByteStream::class.java)
            .apply { isAccessible = true }.invoke(controller, owner, stream) as Boolean

    private class TestStream(private val callbackLock: Any) : BlockingDuplexByteStream {
        val sends = AtomicInteger()
        val closed = CountDownLatch(1)
        @Volatile var closedWithCallbackLock = false
        override fun send(data: ByteArray) { sends.incrementAndGet() }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            if (closed.await(minOf(timeoutMillis, 10), TimeUnit.MILLISECONDS)) byteArrayOf() else null
        override fun close() {
            if (Thread.holdsLock(callbackLock)) closedWithCallbackLock = true
            closed.countDown()
        }
    }
}
