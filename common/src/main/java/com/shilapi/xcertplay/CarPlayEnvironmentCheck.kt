package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.media.MediaCodecList
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.LynkLocalHotspot
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.util.zip.ZipFile

internal enum class EnvironmentGroup(val title: Int) {
    APP(R.string.env_app), USB(R.string.env_usb), WIRELESS(R.string.env_wireless),
}
internal enum class EnvironmentState(val title: Int) {
    PASS(R.string.env_pass), ACTION(R.string.env_action), VERIFY(R.string.env_verify),
}
internal enum class EnvironmentAction(val title: Int) {
    PERMISSIONS(R.string.app_permissions), LOCATION(R.string.env_open_location),
    BLUETOOTH(R.string.env_open_bluetooth), CONNECTION(R.string.lynk_connection),
    DISPLAY(R.string.lynk_display), USB(R.string.lynk_wired),
}
internal data class EnvironmentItem(
    val id: String, val group: EnvironmentGroup, val state: EnvironmentState,
    val title: Int, val detail: Int, val action: EnvironmentAction? = null,
)

/** Nullable values mean the OEM did not expose an answer, never an implicit pass. */
internal data class EnvironmentFacts(
    val api: Int, val device: String, val codec: String,
    val nativeLibraries: Boolean?, val decoder: Boolean?, val authentication: Boolean?,
    val microphone: Boolean, val usbHost: Boolean?, val appleDevices: Int?, val usbPermission: Boolean?,
    val wifi: Boolean?, val bluetooth: Boolean?, val bluetoothPermission: Boolean,
    val bluetoothEnabled: Boolean?, val phonePaired: Boolean?, val locationPermission: Boolean, val nearbyWifiPermission: Boolean,
    val locationEnabled: Boolean?, val automaticHotspot: Boolean, val automaticSupported: Boolean,
    val manualConfigured: Boolean?, val systemHotspot: Boolean?, val sessionPresent: Boolean,
    val sessionActive: Boolean, val lynkProfile: Boolean = false, val errors: List<String> = emptyList(),
)

internal data class EnvironmentReport(val capturedAt: Long, val facts: EnvironmentFacts, val items: List<EnvironmentItem>) {
    fun state(group: EnvironmentGroup): EnvironmentState = when {
        items.any { it.group == group && it.state == EnvironmentState.ACTION } -> EnvironmentState.ACTION
        items.any { it.group == group && it.state == EnvironmentState.VERIFY } -> EnvironmentState.VERIFY
        else -> EnvironmentState.PASS
    }

    fun diagnosticText(context: Context): String = buildString {
        appendLine("Checked at=${java.time.Instant.ofEpochMilli(capturedAt)}; readOnly=true; phoneAcceptanceNotTested=true")
        appendLine("API=${facts.api}; codec=${facts.codec}; sessionPresent=${facts.sessionPresent}; sessionActive=${facts.sessionActive}")
        items.forEach { appendLine("${it.group}/${it.id}=${it.state}: ${context.getString(it.title)}; ${context.getString(it.detail)}") }
        facts.errors.forEach { appendLine("Unavailable: $it") }
    }
}

/** No radio changes, USB opens/claims, permission requests, codec allocation or network traffic. */
internal object CarPlayEnvironmentCheck {
    fun capture(context: Context): EnvironmentReport {
        val app = context.applicationContext
        val errors = mutableListOf<String>()
        fun <T> read(id: String, block: () -> T): T? = try { block() } catch (error: Exception) {
            errors += "$id=${error.javaClass.simpleName}"; null
        }
        fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val btPermission = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = read("bluetooth") { app.getSystemService(BluetoothManager::class.java)?.adapter }
        val usb = app.getSystemService(UsbManager::class.java)
        val apples = read("usb_devices") { usb?.deviceList?.values?.filter { it.vendorId == 0x05ac } }
        val wifi = app.getSystemService(WifiManager::class.java)
        val mode = AirPlayPersistence.peekWirelessHotspotMode(app)
        val codec = if (AirPlayPersistence.loadHevcEnabled(app)) "video/hevc" else "video/avc"
        val session = CarPlayBackgroundSession.hasSession()
        val facts = EnvironmentFacts(
            api = Build.VERSION.SDK_INT, device = "${Build.MANUFACTURER} ${Build.MODEL}", codec = codec,
            nativeLibraries = read("native_libraries") {
                val names = listOf("libusb_configuration.so", "liblocal_hotspot_radio.so", "libxcertplay_i2c.so")
                if (names.all { File(app.applicationInfo.nativeLibraryDir, it).isFile }) true
                else ZipFile(app.applicationInfo.sourceDir).use { apk ->
                    val abis = if (android.os.Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
                    abis.any { abi -> names.all { apk.getEntry("lib/$abi/$it") != null } }
                }
            },
            decoder = read("decoder") { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                !it.isEncoder && it.supportedTypes.any { type -> type.equals(codec, true) }
            } },
            authentication = read("authentication") {
                // Reuse local key/certificate consistency validation; never bootstrap or change the selected target.
                AirPlayPersistence.loadMfiTarget(app) == MfiTarget.LOCAL &&
                    LocalMfiAuthenticationClient.load(File(app.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY))
                        .readCertificate().isNotEmpty()
            } ?: false,
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            usbHost = read("usb_host") { app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) && usb != null },
            appleDevices = apples?.size,
            usbPermission = read("usb_permission") { apples?.takeIf { it.isNotEmpty() }?.any { usb?.hasPermission(it) == true } },
            wifi = read("wifi") { wifi != null && app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) },
            bluetooth = if (errors.any { it.startsWith("bluetooth=") }) null else adapter != null,
            bluetoothPermission = btPermission,
            bluetoothEnabled = if (btPermission) read("bluetooth_enabled") { adapter?.isEnabled } else null,
            phonePaired = if (btPermission) read("phone_pairing") {
                val selected = DiPlayPreferences.phoneAddress(app)
                selected != null && adapter?.bondedDevices?.any { it.address == selected } == true
            } else null,
            locationPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            nearbyWifiPermission = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES),
            locationEnabled = read("location") { app.getSystemService(LocationManager::class.java)?.isLocationEnabled },
            automaticHotspot = mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
            automaticSupported = LynkLocalHotspot.supported(app),
            manualConfigured = read("manual_hotspot") {
                val ssid = AirPlayPersistence.loadManualHotspotSsid(app)
                val password = AirPlayPersistence.loadManualHotspotPassphrase(app)
                ManualHotspotValidation.error(ssid, password) == null &&
                    AirPlayPersistence.loadManualHotspotSecurity(app) == ManualHotspotValidation.securityFor(password)
            },
            // The hidden getter is best effort. Failure stays unknown and does not start a hotspot to probe it.
            systemHotspot = read("hotspot_state") { CarHotspotStatus.isEnabled(app) },
            sessionPresent = session, sessionActive = CarPlayBackgroundSession.active,
            lynkProfile = app.resources.getBoolean(R.bool.config_simple_connection_flow), errors = errors.toList(),
        )
        return evaluate(facts)
    }

    fun evaluate(f: EnvironmentFacts, now: Long = System.currentTimeMillis()): EnvironmentReport {
        val items = mutableListOf<EnvironmentItem>()
        fun add(id: String, group: EnvironmentGroup, state: EnvironmentState, title: Int, detail: Int, action: EnvironmentAction? = null) {
            items += EnvironmentItem(id, group, state, title, detail, action)
        }
        fun check(id: String, group: EnvironmentGroup, value: Boolean?, title: Int, pass: Int, fail: Int, action: EnvironmentAction? = null) {
            add(id, group, when (value) { true -> EnvironmentState.PASS; false -> EnvironmentState.ACTION; null -> EnvironmentState.VERIFY },
                title, when (value) { true -> pass; false -> fail; null -> R.string.env_unknown }, if (value == false) action else null)
        }
        val app = EnvironmentGroup.APP; val usb = EnvironmentGroup.USB; val wireless = EnvironmentGroup.WIRELESS
        check("android", app, f.api >= 28, R.string.env_android, R.string.env_android_ok, R.string.env_android_bad)
        check("native", app, f.nativeLibraries, R.string.env_native, R.string.env_native_ok, R.string.env_native_bad)
        check("decoder", app, f.decoder, R.string.env_decoder, R.string.env_decoder_ok, R.string.env_decoder_bad, EnvironmentAction.DISPLAY)
        check("local_identity", app, f.authentication, R.string.env_identity, R.string.env_identity_ok, R.string.env_identity_bad)
        check("microphone", app, f.microphone, R.string.env_microphone, R.string.env_microphone_ok, R.string.env_microphone_bad, EnvironmentAction.PERMISSIONS)
        add("playback", app, EnvironmentState.VERIFY, R.string.env_playback, R.string.env_playback_pending)

        check("usb_host", usb, f.usbHost, R.string.env_usb_host, R.string.env_usb_host_ok, R.string.env_usb_host_bad)
        add("usb_phone", usb, if ((f.appleDevices ?: 0) > 0) EnvironmentState.PASS else EnvironmentState.VERIFY,
            R.string.env_usb_phone, when {
                f.appleDevices == null -> R.string.env_unknown
                f.appleDevices > 0 -> R.string.env_usb_phone_ok
                else -> R.string.env_usb_phone_pending
            })
        if ((f.appleDevices ?: 0) > 0) check("usb_permission", usb, f.usbPermission,
            R.string.env_usb_permission, R.string.env_usb_permission_ok, R.string.env_usb_permission_bad, EnvironmentAction.USB)
        // Android 9 prepare() can revoke another VPN even without showing consent. Never call it in a check.
        add("vpn_consent", usb, EnvironmentState.VERIFY, R.string.env_vpn, R.string.env_vpn_bad)
        add("usb_handshake", usb, EnvironmentState.VERIFY, R.string.env_usb_handshake, R.string.env_usb_handshake_pending)

        check("wifi", wireless, f.wifi, R.string.env_wifi, R.string.env_wifi_ok, R.string.env_wifi_bad)
        check("bluetooth", wireless, f.bluetooth, R.string.env_bluetooth, R.string.env_bluetooth_ok, R.string.env_bluetooth_bad)
        check("bluetooth_permission", wireless, f.bluetoothPermission, R.string.env_bt_permission, R.string.env_permission_ok, R.string.env_permission_bad, EnvironmentAction.PERMISSIONS)
        if (f.sessionPresent) {
            // A running 2.4 GHz handoff intentionally disables Bluetooth. Never suggest enabling it here.
            add("bluetooth_power", wireless, EnvironmentState.VERIFY, R.string.env_bt_power, R.string.env_bt_session);
        } else if (f.bluetooth == true && f.bluetoothPermission) {
            check("bluetooth_power", wireless, f.bluetoothEnabled, R.string.env_bt_power, R.string.env_bt_power_ok, R.string.env_bt_power_bad, EnvironmentAction.BLUETOOTH)
        }
        check("phone_pairing", wireless, f.phonePaired, R.string.env_pairing, R.string.env_pairing_ok, R.string.env_pairing_bad, EnvironmentAction.CONNECTION)
        // The API 28 transport requests fine location even for manual hotspot interface discovery.
        if (f.api < 33) check("location_permission", wireless, f.locationPermission, R.string.env_location_permission,
            R.string.env_permission_ok, R.string.env_location_permission_bad, EnvironmentAction.PERMISSIONS)
        else check("nearby_wifi_permission", wireless, f.nearbyWifiPermission, R.string.env_nearby_wifi,
            R.string.env_permission_ok, R.string.env_permission_bad, EnvironmentAction.PERMISSIONS)
        if (f.automaticHotspot) {
            check("hotspot_api", wireless, f.automaticSupported, R.string.env_hotspot_api, R.string.env_hotspot_api_ok, R.string.env_hotspot_api_bad, EnvironmentAction.CONNECTION)
            check("location_switch", wireless, f.locationEnabled, R.string.env_location_switch, R.string.env_location_ok, R.string.env_location_bad, EnvironmentAction.LOCATION)
            if (!f.sessionPresent) check("hotspot_conflict", wireless, f.systemHotspot?.not(), R.string.env_hotspot_conflict,
                R.string.env_hotspot_conflict_ok, R.string.env_hotspot_conflict_bad, EnvironmentAction.CONNECTION)
        } else {
            check("hotspot_config", wireless, f.manualConfigured, R.string.env_hotspot_config, R.string.env_hotspot_config_ok, R.string.env_hotspot_config_bad, EnvironmentAction.CONNECTION)
            if (f.lynkProfile && f.systemHotspot == true) {
                add("hotspot_enabled", wireless, EnvironmentState.VERIFY, R.string.env_hotspot_switch, R.string.env_hotspot_on)
            } else check("hotspot_enabled", wireless, f.systemHotspot, R.string.env_hotspot_switch, R.string.env_hotspot_on, R.string.env_hotspot_off, EnvironmentAction.CONNECTION)
        }
        add("wireless_handoff", wireless, EnvironmentState.VERIFY, R.string.env_handoff, R.string.env_handoff_pending)
        return EnvironmentReport(now, f, items)
    }
}
