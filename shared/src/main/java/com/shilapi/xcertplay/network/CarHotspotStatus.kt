package com.shilapi.xcertplay.network

import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.net.wifi.WifiManager

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * The user can turn it on in car settings or opt into [CarHotspotTethering] after granting permission.
 */
object CarHotspotStatus {
    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val EXTRA_WIFI_AP_MODE = "wifi_ap_mode"

    /** Keep all OEM sources separate: a stale getter and a real interface are different evidence. */
    fun diagnostic(context: Context): String {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java)
        fun readRaw(block: () -> Any?): String = try { block()?.toString() ?: "unknown" }
            catch (error: Exception) { "unavailable(${error.javaClass.simpleName})" }
        val state = readRaw { wifi?.let { WifiManager::class.java.getMethod("getWifiApState").invoke(it) } }
        val enabled = readRaw { wifi?.let { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(it) } }
        val sticky = readRaw {
            app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED))?.let {
                "state=${it.getIntExtra(EXTRA_WIFI_AP_STATE, -1)},mode=${it.getIntExtra(EXTRA_WIFI_AP_MODE, -1)}," +
                    "failure=${it.getIntExtra("wifi_ap_error_code", -1)},iface=${it.getStringExtra("wifi_ap_interface_name")?.take(32)}"
            }
        }
        return "AP sources getState=$state isEnabled=$enabled sticky=[$sticky]"
    }

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java)
        return read(
            state = { wifi?.let { WifiManager::class.java.getMethod("getWifiApState").invoke(it) as? Int } },
            enabled = { wifi?.let { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(it) as? Boolean } },
            sticky = { app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)) },
        )
    }

    /** Only this stricter mode-aware result may block a new LocalOnlyHotspot request. */
    fun isTetheringEnabled(context: Context): Boolean? {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java)
        return readTethering(
            { wifi?.let { WifiManager::class.java.getMethod("getWifiApState").invoke(it) as? Int } },
            { wifi?.let { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(it) as? Boolean } },
            { app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)) },
        )
    }

    internal fun read(state: () -> Int?, enabled: () -> Boolean?, sticky: () -> Intent?): Boolean? {
        stateEnabled(runCatching(state).getOrNull())?.let { return it }
        runCatching(enabled).getOrNull()?.let { return it }
        val intent = runCatching(sticky).getOrNull() ?: return null
        if (intent.hasExtra(EXTRA_WIFI_AP_MODE) && intent.getIntExtra(EXTRA_WIFI_AP_MODE, -1) != 1) return null
        return stateEnabled(intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1))
    }

    internal fun stateEnabled(state: Int?): Boolean? = state?.takeIf { it in 10..14 }?.let { it == 13 }

    internal fun readTethering(state: () -> Int?, enabled: () -> Boolean?, sticky: () -> Intent?): Boolean? {
        val generic = stateEnabled(runCatching(state).getOrNull()) ?: runCatching(enabled).getOrNull()
        if (generic == false) return false
        val intent = runCatching(sticky).getOrNull() ?: return null
        // Generic AP enabled includes LOHS. Only a positively identified tethered AP can
        // block a new local request; mode unknown stays unknown on restricted OEM builds.
        if (intent.getIntExtra(EXTRA_WIFI_AP_MODE, -1) != 1) return null
        return stateEnabled(intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1))
    }
}
