package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayRuntimeLifecycleTest {
    private val app get() = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): android.content.Context = this
        override fun bindService(intent: Intent, connection: android.content.ServiceConnection, flags: Int) = false
    }
    private val owned = mutableListOf<Pair<CarPlayController, AndroidMediaSink>>()
    private val releases = mutableListOf<CountDownLatch>()
    private val display = CarPlaySessionDisplay(1280, 720, 0, true, true, 1280, 720)
    @Before fun before() { CarPlayBackgroundSession.clear() }
    @After fun after() {
        releases.forEach { it.countDown() }
        CarPlayBackgroundSession.snapshot()?.let { owned += it.controller to it.sink }
        owned.forEach { (controller, sink) -> controller.close(); controller.awaitClosed(2000); sink.close(); sink.awaitClosed(2000) }
        CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun config() = CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
        identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 3))
    private fun airPlay() = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01", sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720))
    private fun plan() = CarPlaySessionPlan(app, config(), airPlay(), AirPlayIdentity.generate(), 1280, 720,
        display, false, false, false, 0, 0, 14, 100, false, null)
    private fun blocked(): Triple<CarPlayController, AndroidMediaSink, CountDownLatch> {
        val controller = CarPlayController(app, config(), airPlay(), AirPlayIdentity.generate(), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        val sink = AndroidMediaSink()
        owned += controller to sink
        val entered = CountDownLatch(1); val release = CountDownLatch(1); releases += release
        val executor = field(controller, "executor").get(controller) as ExecutorService
        executor.execute { entered.countDown(); while (release.count != 0L) try { release.await() } catch (_: InterruptedException) {} }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        return Triple(controller, sink, release)
    }
    private fun store(controller: CarPlayController, sink: AndroidMediaSink, owner: Any = Any()) =
        CarPlayBackgroundSession.store(controller, sink, 1280, 720, owner, display)
    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Runtime did not complete its transition", condition())
    }

    @Test fun restartSurvivesUiReplacementAndWaitsPastPresentationDeadline() {
        val (old, sink, release) = blocked()
        val firstOwner = Any(); store(old, sink, firstOwner)
        var delivered = 0
        val firstBinding = CarPlayBackgroundSession.Binding(object : AirPlaySessionListener {}, {}, {}, {})
        CarPlayBackgroundSession.bind(firstOwner, firstBinding)
        mockConstruction(CarPlayController::class.java).use { construction ->
            CarPlayBackgroundSession.restart(plan())
            CarPlayBackgroundSession.unbind(firstOwner)
            val nextOwner = Any()
            val nextBinding = CarPlayBackgroundSession.Binding(object : AirPlaySessionListener {}, {}, { delivered++ }, {})
            CarPlayBackgroundSession.bind(nextOwner, nextBinding)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(CarPlayBackgroundSession.isClosing())
            assertNull(CarPlayBackgroundSession.snapshot())
            assertTrue(construction.constructed().isEmpty())
            release.countDown(); assertTrue(old.awaitClosed(2000))
            pumpUntil { CarPlayBackgroundSession.snapshot() != null }
            assertTrue(sink.awaitClosed(2000))
            assertEquals(1, construction.constructed().size)
            assertEquals(1, delivered)
            assertTrue(CarPlayBackgroundSession.isOwner(nextOwner))
        }
    }
    @Test fun cancellationWhileClosingPreventsReplacement() {
        val (old, sink, release) = blocked(); store(old, sink)
        var completed = false
        mockConstruction(CarPlayController::class.java).use { construction ->
            CarPlayBackgroundSession.restart(plan())
            CarPlayBackgroundSession.stop { completed = true }
            assertFalse(completed)
            release.countDown(); assertTrue(old.awaitClosed(2000))
            pumpUntil { completed }
            assertTrue(sink.awaitClosed(2000))
            assertTrue(construction.constructed().isEmpty())
            assertFalse(CarPlayBackgroundSession.hasSession())
        }
    }
    @Test fun reentrantStopCallbackCannotStealTheNextCloseCompletion() {
        val (first, firstSink, firstRelease) = blocked()
        val (second, secondSink, secondRelease) = blocked()
        store(first, firstSink)
        var firstDone = false; var secondDone = false
        CarPlayBackgroundSession.stop {
            firstDone = true
            store(second, secondSink)
            CarPlayBackgroundSession.stop { secondDone = true }
        }
        firstRelease.countDown(); assertTrue(first.awaitClosed(2000))
        pumpUntil { firstDone }
        assertFalse(secondDone)
        secondRelease.countDown(); assertTrue(second.awaitClosed(2000))
        pumpUntil { secondDone }
    }
    @Test fun oldServiceDestructionCannotStopTheReplacementRuntime() {
        val (old, sink, release) = blocked(); store(old, sink)
        val service = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val oldEpoch = field(CarPlayBackgroundSession, "serviceEpoch").getLong(CarPlayBackgroundSession)
        service.get().onStartCommand(Intent().putExtra(DiPlaySessionService.EXTRA_RUNTIME_EPOCH, oldEpoch), 0, 1)
        mockConstruction(CarPlayController::class.java).use {
            CarPlayBackgroundSession.stop { CarPlayBackgroundSession.start(plan()) }
            release.countDown(); assertTrue(old.awaitClosed(2000))
            pumpUntil { CarPlayBackgroundSession.snapshot() != null }
            val next = CarPlayBackgroundSession.snapshot()!!.controller
            service.destroy()
            assertSame(next, CarPlayBackgroundSession.snapshot()!!.controller)
            org.mockito.Mockito.verify(next, org.mockito.Mockito.never()).close()
        }
    }
    @Test fun releaseFailureSettlesCancellationButQuarantinesTheOutputs() {
        val (old, sink, release) = blocked(); store(old, sink)
        val resources = field(sink, "resources").get(sink)
        val completion = CompletableFuture<Unit>()
        resources.javaClass.getDeclaredMethod("track", CompletableFuture::class.java).invoke(resources, completion)
        completion.completeExceptionally(IllegalStateException("native release failed"))
        val states = mutableListOf<CarPlayStatus>()
        val observer = Any()
        val binding = CarPlayBackgroundSession.Binding(object : AirPlaySessionListener {}, states::add, {}, {})
        CarPlayBackgroundSession.bind(observer, binding)
        var stopped = false
        CarPlayBackgroundSession.stop { stopped = true }
        release.countDown(); assertTrue(old.awaitClosed(2000))
        pumpUntil { stopped }
        assertFalse(CarPlayBackgroundSession.isClosing())
        assertTrue(states.last() is CarPlayStatus.Failed)
        CarPlayBackgroundSession.unbind(observer)
        states.clear()
        val replacement = CarPlayBackgroundSession.Binding(object : AirPlaySessionListener {}, states::add, {}, {})
        CarPlayBackgroundSession.bind(Any(), replacement)
        assertTrue(states.single() is CarPlayStatus.Failed)
        assertThrows(IllegalStateException::class.java) { CarPlayBackgroundSession.start(plan()) }
        // The failure result remains observable instead of claiming successful media release.
        assertThrows(ExecutionException::class.java) { sink.awaitClosed(1) }
        owned.removeAll { it.first === old }
    }
    private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
