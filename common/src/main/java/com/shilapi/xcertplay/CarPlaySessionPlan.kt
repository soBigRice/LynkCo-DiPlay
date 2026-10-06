package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.platform.HeadUnitProfile
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File

/** Immutable startup inputs. Nothing here retains an Activity, View, Surface or UI callback. */
internal data class CarPlaySessionPlan(
    val context: Context,
    val runtime: CarPlayRuntimeConfig,
    val airPlay: AirPlayConfig,
    val identity: AirPlayIdentity,
    val width: Int,
    val height: Int,
    val display: CarPlaySessionDisplay,
    val softwareHevc: Boolean,
    val advancedAudioMapping: Boolean,
    val focusEnabled: Boolean,
    val mediaChannel: Int,
    val navigationChannel: Int,
    val navigationStreamType: Int,
    val mediaBufferMillis: Int,
    val microphoneEnabled: Boolean,
    val captureDirectory: File?,
) {
    init { require(context === context.applicationContext) { "Runtime must use application context" } }

    fun create(listener: AirPlaySessionListener, status: (CarPlayStatus) -> Unit,
        audioDiagnostic: (String) -> Unit): CarPlayBackgroundSession.Snapshot {
        val app = context
        val renderer = AndroidMediaSink(
            videoWidth = airPlay.main.widthPixels, videoHeight = airPlay.main.heightPixels,
            preferSoftwareHevcDecoder = softwareHevc, advancedAudioChannelMapping = advancedAudioMapping,
            audioFocusEnabled = focusEnabled, coordinatedAudioFocus = HeadUnitProfile.read(app).coordinatedAudioFocus,
            mediaChannel = mediaChannel, navigationChannel = navigationChannel, context = app,
            navigationStreamType = navigationStreamType, mediaBufferMillis = mediaBufferMillis,
            onAudioDiagnostic = audioDiagnostic, onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
        )
        val location = when {
            !runtime.locationReportingEnabled -> null
            runtime.identification.vehicleSpeedEnabled -> VehicleSpeedLocationProvider(
                AndroidCarPlayLocationProvider(app), com.shilapi.xcertplay.hud.BydNavigationOutputs.wheelSpeed(app))
            else -> AndroidCarPlayLocationProvider(app)
        }
        try {
            val next = CarPlayController(
                app, runtime, airPlay, identity,
                AirPlayPersistence.loadPairings(app) { id, key -> AirPlayPersistence.savePairing(app, id, key) },
                listener, CarPlayMediaEngine(renderer, microphoneEnabled, captureDirectory), status,
                loadPairRecord = { AirPlayPersistence.loadLockdownRecord(app) },
                savePairRecord = { AirPlayPersistence.saveLockdownRecord(app, it) },
                clearPairRecord = { AirPlayPersistence.clearLockdownRecord(app) },
                locationProvider = location,
                vehicleStatusProvider = if (runtime.identification.vehicleStatusEnabled)
                    com.shilapi.xcertplay.hud.BydNavigationOutputs.batteryStatus(app) else null,
            )
            return CarPlayBackgroundSession.Snapshot(next, renderer, width, height, display)
        } catch (error: Throwable) {
            renderer.close()
            location?.close()
            throw error
        }
    }
}
