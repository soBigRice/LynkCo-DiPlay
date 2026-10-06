package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.Inet6Address

/** Read-only topology snapshots before/after attempts; no SSID, password or hardware address. */
object HotspotDiagnostics {
    fun capture(context: Context, point: String, report: (String) -> Unit) {
        try {
            report("hotspot snapshot point=$point ${CarHotspotStatus.diagnostic(context)}")
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            report("hotspot capabilities wifiState=${wifi?.wifiState} fiveGHz=${wifi?.is5GHzBandSupported}")
            ManualHotspotInterfaces(context, report).use { reader ->
                val snapshot = reader.sample()
                report("hotspot topology point=$point ap=${snapshot.apInterfaces} default=${snapshot.defaultInterface} " +
                    "upstreams=${snapshot.upstreamInterfaces} consistent=${snapshot.consistent}")
                snapshot.interfaces.forEach { iface ->
                    report("hotspot topology iface=${iface.name} up=${iface.up} wireless=${iface.wireless} " +
                        "ipv4Private=${iface.addresses.count { it is Inet4Address && it.isSiteLocalAddress }} " +
                        "ipv4Other=${iface.addresses.count { it is Inet4Address && !it.isSiteLocalAddress }} " +
                        "ipv6Link=${iface.addresses.count { it is Inet6Address && it.isLinkLocalAddress }}")
                }
            }
        } catch (error: Exception) { report("hotspot snapshot point=$point unavailable=${error.javaClass.simpleName}") }
    }
}
