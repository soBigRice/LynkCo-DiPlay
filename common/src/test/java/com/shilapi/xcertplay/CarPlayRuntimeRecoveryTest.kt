package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayRuntimeRecoveryTest {
    private var simpleFlow = true
    private val ownedSinks = mutableListOf<AndroidMediaSink>()
    private val app get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        @Suppress("DEPRECATION")
        private val profileResources = object : android.content.res.Resources(
            baseContext.resources.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
            override fun getBoolean(id: Int): Boolean =
                if (id == com.shilapi.xcertplay.host.R.bool.config_simple_connection_flow) simpleFlow else super.getBoolean(id)
        }
        override fun getResources() = profileResources
        override fun bindService(intent: Intent, connection: android.content.ServiceConnection, flags: Int) = false
    }
    private fun plan() = CarPlaySessionPlan(app,
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 3)),
        AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720)),
        AirPlayIdentity.generate(), 1280, 720, CarPlaySessionDisplay(1280, 720, 0, true, true, 1280, 720),
        false, false, false, 0, 0, 14, 100, false, null)
    private fun scheduler() = CarPlayBackgroundSession::class.java.getDeclaredField("retry").apply {
        isAccessible = true
    }.get(CarPlayBackgroundSession) as ConnectionRetryScheduler
    @Before fun reset() { CarPlayBackgroundSession.clear() }
    @After fun cleanup() {
        CarPlayBackgroundSession.snapshot()?.sink?.let(ownedSinks::add)
        ownedSinks.distinct().forEach { it.close(); assertTrue(it.awaitClosed(2000)) }
        CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun vanishedSettingsWindowMustNotBlockRecoveryInReplacementWindow() {
        mockConstruction(CarPlayController::class.java).use {
            val first = Any()
            val firstBinding = CarPlayBackgroundSession.Binding(object : AirPlaySessionListener {}, {}, {}, {})
            CarPlayBackgroundSession.bind(first, firstBinding)
            CarPlayBackgroundSession.start(plan())
            // Same calls made by openSettingsMenu and the old Activity's onDestroy.
            CarPlayBackgroundSession.pauseRetry(first, true)
            CarPlayBackgroundSession.reconnect("Lost connection while settings is open")
            assertFalse(scheduler().isScheduled)
            CarPlayBackgroundSession.unbind(first)
            val replacement = Any()
            val replacementBinding = CarPlayBackgroundSession.Binding(object : AirPlaySessionListener {}, {}, {}, {})
            CarPlayBackgroundSession.bind(replacement, replacementBinding)
            CarPlayBackgroundSession.reconnect("Transport lost after the window was replaced")
            assertTrue("Settings no longer exists, but the runtime still suppresses reconnect", scheduler().isScheduled)
        }
    }
    @Test fun userFixableHotspotFailureMustNotScheduleBackgroundRetry() {
        var report: ((CarPlayStatus) -> Unit)? = null
        mockConstruction(CarPlayController::class.java) { _, context ->
            @Suppress("UNCHECKED_CAST")
            report = context.arguments()[7] as (CarPlayStatus) -> Unit
        }.use {
            CarPlayBackgroundSession.start(plan())
            val failure = CarPlayStatus.Failed("Could not establish LOCAL_ONLY_HOTSPOT hotspot: LocalOnlyHotspot incompatible with existing shared hotspot")
            assertTrue(ConnectionGuide.state(failure, true, true).pauseRetry)
            CarPlayBackgroundSession.reconnect("Transport error before hotspot status")
            assertTrue(scheduler().isScheduled)
            report!!(failure)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("UI asks the user to fix the hotspot, but runtime has an automatic retry pending", scheduler().isScheduled)
            assertTrue(CarPlayBackgroundSession.retryStopped)
            CarPlayBackgroundSession.reconnect("Late transport error must not restart the conflicted hotspot")
            assertFalse(scheduler().isScheduled)
            ownedSinks += CarPlayBackgroundSession.snapshot()!!.sink
            CarPlayBackgroundSession.restart(manual = true)
            assertFalse("Explicit retry must recover after the user fixes settings", CarPlayBackgroundSession.retryStopped)
        }
    }
    @Test fun recoveredSessionDiscardsDeferredRetryBeforeTheWindowUnbinds() {
        var listener: AirPlaySessionListener? = null
        mockConstruction(CarPlayController::class.java) { _, context ->
            listener = context.arguments()[5] as AirPlaySessionListener
        }.use {
            val owner = Any(); val observer = binding()
            CarPlayBackgroundSession.bind(owner, observer)
            CarPlayBackgroundSession.start(plan())
            CarPlayBackgroundSession.pauseRetry(owner, true)
            CarPlayBackgroundSession.reconnect("Transient failure while settings is open")
            listener!!.onSessionActive(org.mockito.Mockito.mock(AirPlaySession::class.java))
            shadowOf(Looper.getMainLooper()).idle()
            CarPlayBackgroundSession.unbind(owner)
            assertTrue(CarPlayBackgroundSession.active)
            assertFalse("A recovered live session must not be restarted by stale deferred recovery", scheduler().isScheduled)
        }
    }

    private fun binding() = CarPlayBackgroundSession.Binding(object : AirPlaySessionListener {}, {}, {}, {})

    @Test fun replacingAnOwnerDoesNotLetStaleCallbacksResumeTheNewSettingsWindow() {
        mockConstruction(CarPlayController::class.java).use {
            val first = Any(); val firstBinding = binding()
            CarPlayBackgroundSession.bind(first, firstBinding)
            CarPlayBackgroundSession.start(plan())
            CarPlayBackgroundSession.pauseRetry(first, true)
            CarPlayBackgroundSession.reconnect("Deferred by old settings")
            val second = Any(); val secondBinding = binding()
            CarPlayBackgroundSession.bind(second, secondBinding)
            assertTrue("Replacement releases the old window's pause", scheduler().isScheduled)
            CarPlayBackgroundSession.pauseRetry(second, true)
            CarPlayBackgroundSession.pauseRetry(first, false)
            CarPlayBackgroundSession.unbind(first)
            assertFalse("Old window cannot unpause the new settings", scheduler().isScheduled)
            CarPlayBackgroundSession.pauseRetry(second, false)
            assertTrue(scheduler().isScheduled)
        }
    }

    @Test fun stoppingClearsSettingsPauseBeforeTheNextStart() {
        val owner = Any(); val observer = binding()
        CarPlayBackgroundSession.bind(owner, observer)
        CarPlayBackgroundSession.pauseRetry(owner, true)
        CarPlayBackgroundSession.stop()
        mockConstruction(CarPlayController::class.java).use {
            CarPlayBackgroundSession.start(plan())
            CarPlayBackgroundSession.reconnect("Fresh session")
            assertTrue(scheduler().isScheduled)
        }
    }

    @Test fun userActionFailureClearsDeferredRecoveryFromSettings() {
        var report: ((CarPlayStatus) -> Unit)? = null
        mockConstruction(CarPlayController::class.java) { _, context ->
            @Suppress("UNCHECKED_CAST")
            report = context.arguments()[7] as (CarPlayStatus) -> Unit
        }.use {
            val owner = Any(); val observer = binding()
            CarPlayBackgroundSession.bind(owner, observer)
            CarPlayBackgroundSession.start(plan())
            CarPlayBackgroundSession.pauseRetry(owner, true)
            CarPlayBackgroundSession.reconnect("Wait for settings")
            report!!(CarPlayStatus.Failed("Location mode is disabled"))
            shadowOf(Looper.getMainLooper()).idle()
            CarPlayBackgroundSession.pauseRetry(owner, false)
            CarPlayBackgroundSession.unbind(owner)
            assertFalse(scheduler().isScheduled)
            assertTrue(CarPlayBackgroundSession.retryStopped)
        }
    }

    @Test fun transientBluetoothFailureStillRetries() = retryableFailure(true, "RFCOMM socket closed")

    @Test fun otherProfilesKeepTheirExistingRecoveryPolicy() = retryableFailure(false, "LocalOnlyHotspot unavailable")

    private fun retryableFailure(simple: Boolean, message: String) {
        simpleFlow = simple
        var report: ((CarPlayStatus) -> Unit)? = null
        mockConstruction(CarPlayController::class.java) { _, context ->
            @Suppress("UNCHECKED_CAST")
            report = context.arguments()[7] as (CarPlayStatus) -> Unit
        }.use {
            CarPlayBackgroundSession.start(plan())
            report!!(CarPlayStatus.Failed(message))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(scheduler().isScheduled)
            assertFalse(CarPlayBackgroundSession.retryStopped)
        }
    }
}
