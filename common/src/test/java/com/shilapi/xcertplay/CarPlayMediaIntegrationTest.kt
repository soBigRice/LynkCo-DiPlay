package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayMediaIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var current = mock(CarPlayController::class.java)
    private lateinit var phoneUpdate: (CarPlayNowPlaying) -> Unit
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    @Before fun setup() {
        doAnswer { call ->
            call.getArgument<((CarPlayNowPlaying) -> Unit)?>(0)?.let { phoneUpdate = it }
            null
        }.`when`(current).nowPlayingListener = any()
        CarPlayMediaKeys.attach(context, current, resumeFocus = {})
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CarPlaySessionNotification.CHANNEL, "test", NotificationManager.IMPORTANCE_LOW))
    }
    @After fun cleanup() { CarPlayMediaKeys.detach(current); idle() }

    @Test fun pausedMetadataCreatesSystemControlsWithoutRequestingAudioFocus() {
        phoneUpdate(CarPlayNowPlaying(title = "private-song", artist = "private-artist", elapsedMillis = 0))
        idle()
        val state = requireNotNull(CarPlayMediaKeys.notificationState())
        assertFalse(state.playing)
        assertTrue(CarPlayMediaKeys.diagnosticState().contains("active=true"))
        assertNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
        val report = HeadUnitMediaDiagnostics.capture(context)
        assertFalse(report.contains("private-song")); assertFalse(report.contains("private-artist"))
        assertTrue(report.contains("otherSessions=not_requested"))
        assertTrue(report.contains("Media notification enabled="))
        assertTrue(report.contains("sessionLinked="))
    }

    @Test fun mediaNotificationPublishesTokenExplicitActionsAndRetainsDisconnect() {
        phoneUpdate(CarPlayNowPlaying(title = "Song", playing = true)); idle()
        val state = requireNotNull(CarPlayMediaKeys.notificationState())
        val notification = CarPlaySessionNotification.build(context, 7, state)
        assertEquals(Notification.CATEGORY_TRANSPORT, notification.category)
        @Suppress("DEPRECATION")
        assertEquals(state.token, notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
        assertArrayEquals(intArrayOf(0, 1, 2), notification.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS))
        assertEquals(4, notification.actions.size)
        assertEquals(listOf(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.PAUSE, CarPlayMediaButton.NEXT),
            notification.actions.take(3).map { shadowOf(it.actionIntent).savedIntent.getIntExtra(CarPlaySessionNotification.EXTRA_MEDIA_INDEX, -1) })
        assertEquals(DiPlaySessionService.ACTION_STOP, shadowOf(notification.actions.last().actionIntent).savedIntent.action)
        assertNotNull(notification.actions.last().getIcon())
        phoneUpdate(CarPlayNowPlaying(title = "Song", playing = false)); idle()
        val paused = CarPlaySessionNotification.build(context, 7, CarPlayMediaKeys.notificationState())
        assertEquals(CarPlayMediaButton.PLAY, shadowOf(paused.actions[1].actionIntent).savedIntent
            .getIntExtra(CarPlaySessionNotification.EXTRA_MEDIA_INDEX, -1))
    }

    @Test fun oldNotificationButtonsDoNotAcquireTheReplacementRuntimeEpoch() {
        CarPlayMediaKeys.onMediaAudioChanged(true); idle()
        val state = requireNotNull(CarPlayMediaKeys.notificationState())
        val old = CarPlaySessionNotification.build(context, 7, state).actions[0].actionIntent
        val next = CarPlaySessionNotification.build(context, 8, state).actions[0].actionIntent
        assertNotEquals(old, next)
        assertEquals(7L, shadowOf(old).savedIntent.getLongExtra(DiPlaySessionService.EXTRA_RUNTIME_EPOCH, -1))
        assertEquals(8L, shadowOf(next).savedIntent.getLongExtra(DiPlaySessionService.EXTRA_RUNTIME_EPOCH, -1))
    }

    @Test fun foregroundNextIsSentOnceAndVolumeOrTextAreNotConsumed() {
        CarPlayMediaKeys.onMediaAudioChanged(true); idle()
        assertTrue(CarPlayMediaKeys.dispatchForegroundKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)))
        assertTrue(CarPlayMediaKeys.dispatchForegroundKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 1)))
        assertTrue(CarPlayMediaKeys.dispatchForegroundKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)))
        assertFalse(CarPlayMediaKeys.dispatchForegroundKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertFalse(CarPlayMediaKeys.dispatchForegroundKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        // Robolectric's native key-name lookup returns the numeric code; command semantics
        // must be identical regardless of whether Android supplies a symbolic label.
        verify(current, times(1)).sendMediaButton(eq(CarPlayMediaButton.NEXT), startsWith("activity_"))
        assertFalse(HeadUnitMediaDiagnostics.isControlKey(KeyEvent.KEYCODE_A))
        assertFalse(HeadUnitMediaDiagnostics.isControlKey(KeyEvent.KEYCODE_1))
    }

    @Test fun releasedMediaCallbackCannotControlANewPhoneSession() {
        CarPlayMediaKeys.onMediaAudioChanged(true); idle()
        val old = current
        val callback = ReflectionHelpers.getField<CarPlayMediaCallback>(CarPlayMediaKeys, "activeCallback")
        current = mock(CarPlayController::class.java)
        CarPlayMediaKeys.attach(context, current, resumeFocus = {})
        CarPlayMediaKeys.onMediaAudioChanged(true); idle()
        callback.onSkipToNext()
        verify(old, never()).sendMediaButton(CarPlayMediaButton.NEXT, "next")
        verify(current, never()).sendMediaButton(CarPlayMediaButton.NEXT, "next")
        assertTrue(CarPlayMediaKeys.dispatchNotification(CarPlayMediaButton.PLAY))
        verify(current).sendMediaButton(CarPlayMediaButton.PLAY, "notification")
        assertFalse(CarPlayMediaKeys.dispatchNotification(999))
        CarPlayMediaKeys.detach(current)
        assertFalse(CarPlayMediaKeys.dispatchNotification(CarPlayMediaButton.PLAY))
    }

    @Test fun positionOnlyUpdatesDoNotRefreshTheNotificationAndOldObserverCannotRemoveANewerOne() {
        val old = Any(); val owner = Any(); var changes = 0
        CarPlayMediaKeys.observeNotification(old) { error("stale observer") }
        CarPlayMediaKeys.observeNotification(owner) { changes++ }
        CarPlayMediaKeys.removeNotificationObserver(old)
        try {
            phoneUpdate(CarPlayNowPlaying(title = "Song", playing = true, elapsedMillis = 0)); idle()
            val initial = changes
            assertTrue(initial > 0)
            phoneUpdate(CarPlayNowPlaying(title = "Song", playing = true, elapsedMillis = 1_000)); idle()
            assertEquals(initial, changes)
            phoneUpdate(CarPlayNowPlaying(title = "Song", playing = false, elapsedMillis = 1_000)); idle()
            assertEquals(initial + 1, changes)
        } finally { CarPlayMediaKeys.removeNotificationObserver(owner) }
    }

    @Test fun notificationServiceRoutesOnlyItsCurrentEpochAndDoesNotRestartAConnection() {
        CarPlayMediaKeys.onMediaAudioChanged(true); idle()
        val handle = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = spy(handle.get())
        val resources = spy(service.resources)
        doReturn(true).`when`(resources).getBoolean(com.shilapi.xcertplay.host.R.bool.config_simple_connection_flow)
        doReturn(resources).`when`(service).resources
        val epoch = ReflectionHelpers.getField<Long>(CarPlayBackgroundSession, "serviceEpoch")
        ReflectionHelpers.setField(service, "runtimeEpoch", epoch)
        fun command(owner: Long, index: Int) = Intent().setAction(CarPlaySessionNotification.ACTION_MEDIA)
            .putExtra(DiPlaySessionService.EXTRA_RUNTIME_EPOCH, owner).putExtra(CarPlaySessionNotification.EXTRA_MEDIA_INDEX, index)
        try {
            service.onStartCommand(command(epoch, CarPlayMediaButton.PLAY), 0, 3)
            verify(current).sendMediaButton(CarPlayMediaButton.PLAY, "notification")
            service.onStartCommand(command(epoch - 1, CarPlayMediaButton.NEXT), 0, 4)
            verify(current, never()).sendMediaButton(CarPlayMediaButton.NEXT, "notification")
            verify(service, never()).stopSelfResult(anyInt())
            assertNull(shadowOf(handle.get()).lastForegroundNotification)
            ReflectionHelpers.setField(service, "runtimeEpoch", -1L)
            service.onStartCommand(command(epoch, CarPlayMediaButton.NEXT), 0, 5)
            verify(service).stopSelfResult(5)
            verify(current, never()).sendMediaButton(CarPlayMediaButton.NEXT, "notification")
        } finally { handle.destroy() }
    }
}
