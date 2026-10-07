package com.shilapi.xcertplay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    private var runtimeEpoch = -1L
    private var latestStartId = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshNotification = Runnable {
        if (runtimeEpoch >= 0 && CarPlayBackgroundSession.ownsService(runtimeEpoch)) {
            try {
                getSystemService(NotificationManager::class.java).notify(1, buildNotification())
            } catch (error: RuntimeException) {
                CarPlayBackgroundSession.snapshot()?.controller?.recordMediaControlDiagnostic(
                    "notification-update unavailable=${error.javaClass.simpleName}")
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (resources.getBoolean(R.bool.config_simple_connection_flow)) {
            CarPlayMediaKeys.observeNotification(this) {
                main.removeCallbacks(refreshNotification)
                main.post(refreshNotification)
            }
        }
    }
    private fun buildNotification() = CarPlaySessionNotification.build(this, runtimeEpoch,
        if (resources.getBoolean(R.bool.config_simple_connection_flow)) CarPlayMediaKeys.notificationState() else null)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == CarPlaySessionNotification.ACTION_MEDIA) {
            val epoch = intent.getLongExtra(EXTRA_RUNTIME_EPOCH, -1)
            // A stale notification may start a new Service instance. It must not reconnect or
            // control a replacement runtime, and it must not stop an already running Service.
            if (epoch == runtimeEpoch && CarPlayBackgroundSession.ownsService(epoch) &&
                resources.getBoolean(R.bool.config_simple_connection_flow)) {
                CarPlayMediaKeys.dispatchNotification(intent.getIntExtra(CarPlaySessionNotification.EXTRA_MEDIA_INDEX, -1))
            }
            if (runtimeEpoch < 0) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            val epoch = intent.getLongExtra(EXTRA_RUNTIME_EPOCH, runtimeEpoch)
            CarPlayBackgroundSession.stopServiceOwner(epoch) { if (CarPlayBackgroundSession.ownsService(epoch)) stopSelfResult(latestStartId) }
            return START_NOT_STICKY
        }
        runtimeEpoch = intent?.getLongExtra(EXTRA_RUNTIME_EPOCH, runtimeEpoch) ?: runtimeEpoch
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CarPlaySessionNotification.CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Without it Android stops location updates while another car app (the reversing camera,
            // the car's own map) covers CarPlay, and the iPhone gets no position until DiPlay is back.
            if (AirPlayPersistence.loadLocationReportingEnabled(this) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        CarPlayMediaKeys.removeNotificationObserver(this)
        main.removeCallbacks(refreshNotification)
        CarPlayBackgroundSession.stopServiceOwner(runtimeEpoch)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        if (com.shilapi.xcertplay.hud.BydOutputSettings.integrationAllowed(this)) {
            com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        }
        val epoch = runtimeEpoch
        CarPlayBackgroundSession.stopServiceOwner(epoch) { if (CarPlayBackgroundSession.ownsService(epoch)) stopSelfResult(latestStartId) }
    }
    companion object {
        const val EXTRA_RUNTIME_EPOCH = "runtime_epoch"
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
    }
}
