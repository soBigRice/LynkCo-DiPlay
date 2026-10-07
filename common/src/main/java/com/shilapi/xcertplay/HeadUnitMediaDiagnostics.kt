package com.shilapi.xcertplay

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.view.InputDevice
import android.view.KeyEvent

/** Platform/media capabilities only: no VIN, serial, location, personal apps or input text. */
internal object HeadUnitMediaDiagnostics {
    private val keys = intArrayOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

    fun isControlKey(code: Int): Boolean = code in keys || code in (KeyEvent.getMaxKeyCode() + 1)..2048

    fun capture(context: Context): String = buildString {
        fun read(label: String, action: () -> Any?) {
            val value = try { action() ?: "unknown" } catch (error: Exception) { "unavailable(${error.javaClass.simpleName})" }
            DiagnosticRedactor.redact("$label $value")?.let { appendLine(it) }
        }
        val pm = context.packageManager
        read("Platform") { "device=${Build.DEVICE} product=${Build.PRODUCT} buildId=${Build.ID} " +
            "securityPatch=${Build.VERSION.SECURITY_PATCH} type=${Build.TYPE} abis=${Build.SUPPORTED_ABIS.take(4)}" }
        read("Platform features") { pm.systemAvailableFeatures.mapNotNull { it.name }.filter {
            it.contains("automotive") || it.contains("audio") || it.contains("bluetooth") ||
                it.contains("usb") || it.contains("input")
        }.sorted().take(32) }
        read("Platform vehicle libraries") { pm.systemSharedLibraryNames?.filter {
            it.contains(Regex("(?i)automotive|vehicle|ecarx|lynk|desay|android\\.car"))
        }?.sorted()?.take(16) }
        read("Platform home") {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { name -> "$name version=${pm.getPackageInfo(name, 0).versionName}" }
        }
        // MATCH_SYSTEM_ONLY avoids enumerating the user's downloaded music/personal apps.
        read("Platform system media browsers") { pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"),
            PackageManager.MATCH_SYSTEM_ONLY).mapNotNull { it.serviceInfo?.packageName }.distinct().sorted().take(16) }
        read("Power") {
            val power = context.getSystemService(PowerManager::class.java)
            "saving=${power?.isPowerSaveMode} idle=${power?.isDeviceIdleMode} " +
                "batteryExempt=${power?.isIgnoringBatteryOptimizations(context.packageName)} " +
                "backgroundRestricted=${context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted}"
        }
        read("Media session") { CarPlayMediaKeys.diagnosticState() }
        read("Media notification") {
            val manager = context.getSystemService(NotificationManager::class.java)
            val notifications = manager?.activeNotifications.orEmpty()
            val own = notifications.firstOrNull { it.id == 1 }
            "enabled=${manager?.areNotificationsEnabled()} channelImportance=${manager?.getNotificationChannel(CarPlaySessionNotification.CHANNEL)?.importance} " +
                "present=${own != null} sessionLinked=${own?.notification?.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)} " +
                "actions=${own?.notification?.actions?.size ?: 0} category=${own?.notification?.category} " +
                "visibility=own_package_only"
        }
        read("Input devices") { "count=${InputDevice.getDeviceIds().size} shownLimit=16 keySamplesLimit=48/session" }
        InputDevice.getDeviceIds().take(16).forEach { id ->
            read("Input device") {
                InputDevice.getDevice(id)?.let { device ->
                    val supported = device.hasKeys(*keys)
                    "id=$id vendor=${device.vendorId} product=${device.productId} sources=${device.sources} " +
                        "virtual=${device.isVirtual} keyboardType=${device.keyboardType} " +
                        "mediaKeys=${keys.filterIndexed { index, _ -> supported.getOrNull(index) == true }}"
                }
            }
        }
        appendLine("Vehicle bus not accessed; system service absence or unreadable state is not proof of unsupported hardware.")
    }
}
