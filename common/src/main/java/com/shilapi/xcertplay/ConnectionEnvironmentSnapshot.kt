package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections

/** Counts, capabilities and USB descriptors only: never names, addresses or credential values. */
internal object ConnectionEnvironmentSnapshot {
    fun capture(context: Context): String = buildString {
        appendLine("Snapshot at=${java.time.Instant.now()}")
        fun section(name: String, read: () -> String) {
            val value = try { read() } catch (error: Exception) { "unavailable=${error.javaClass.simpleName}" }
            value.lineSequence().forEach { line -> DiagnosticRedactor.redact("$name $line")?.let { appendLine(it) } }
        }
        section("Permissions") {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO,
                if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH)
                .joinToString(" ") { "${it.substringAfterLast('.')}=${context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED}" }
        }
        section("Audio output") { com.shilapi.xcertplay.media.AudioOutputDiagnostics.snapshot(context) }
        section("Hardware") {
            val info = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
            "cpuCores=${Runtime.getRuntime().availableProcessors()} totalRamMiB=${info.totalMem / (1024 * 1024)} " +
                "availableRamMiB=${info.availMem / (1024 * 1024)} lowMemory=${info.lowMemory}"
        }
        section("Location") { "enabled=${context.getSystemService(LocationManager::class.java)?.isLocationEnabled}" }
        section("Bluetooth") {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val selected = DiPlayPreferences.phoneAddress(context)
            "present=${adapter != null} state=${adapter?.state} bondedCount=${adapter?.bondedDevices?.size} " +
                "phoneSelected=${selected != null} selectedBonded=${adapter?.bondedDevices?.any { it.address == selected }}"
        }
        section("Hotspot settings") {
            "mode=${AirPlayPersistence.loadWirelessHotspotMode(context)} " +
                "nameSaved=${AirPlayPersistence.loadManualHotspotSsid(context).isNotBlank()} " +
                "credentialSaved=${AirPlayPersistence.loadManualHotspotPassphrase(context).isNotEmpty()} " +
                "band=${AirPlayPersistence.loadManualHotspotBand(context)} channel=${AirPlayPersistence.loadManualHotspotChannel(context)} " +
                "security=${AirPlayPersistence.loadManualHotspotSecurity(context)}"
        }
        section("Hotspot runtime") {
            val lines = mutableListOf<String>()
            com.shilapi.xcertplay.network.HotspotDiagnostics.capture(context, "export", lines::add)
            lines.joinToString("\n")
        }
        section("WiFi") { "adapterState=${context.applicationContext.getSystemService(WifiManager::class.java)?.wifiState}" }
        section("Networks") {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.mapIndexed { index, network ->
                val caps = cm.getNetworkCapabilities(network)
                val links = cm.getLinkProperties(network)
                val transports = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN)
                    .filter { caps?.hasTransport(it) == true }
                "scope=head-unit index=$index active=${cm.activeNetwork == network} transports=$transports " +
                    "interface=${links?.interfaceName} internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                    "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "ipv4DefaultRoutes=${links?.routes?.count { it.isDefaultRoute && it.destination.address is Inet4Address }} " +
                    "ipv6DefaultRoutes=${links?.routes?.count { it.isDefaultRoute && it.destination.address is Inet6Address }}"
            }.joinToString("\n").ifEmpty { "none" }
        }
        section("Interfaces") {
            Collections.list(NetworkInterface.getNetworkInterfaces()).joinToString("\n") { iface ->
                val addresses = Collections.list(iface.inetAddresses)
                "interface=${iface.name} up=${iface.isUp} loopback=${iface.isLoopback} " +
                    "ipv4Count=${addresses.count { it is Inet4Address }} ipv6Count=${addresses.count { it is Inet6Address }}"
            }
        }
        section("USB") {
            val manager = context.getSystemService(UsbManager::class.java)
            val devices = manager.deviceList.values
            buildString {
                appendLine("attachedCount=${devices.size} appleCount=${devices.count { it.vendorId == 0x05ac }}")
                devices.filter { it.vendorId == 0x05ac }.forEach { device ->
                    appendLine("vid=${device.vendorId} pid=${device.productId} access=${manager.hasPermission(device)} configurations=${device.configurationCount}")
                    for (c in 0 until device.configurationCount) {
                        val config = device.getConfiguration(c)
                        appendLine("configuration=${config.id} interfaces=${config.interfaceCount}")
                        for (i in 0 until config.interfaceCount) {
                            val iface = config.getInterface(i)
                            appendLine("interface=${iface.id} alternate=${iface.alternateSetting} class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol} endpoints=${iface.endpointCount}")
                        }
                    }
                }
            }
        }
    }

    fun isConnectionEvent(message: String): Boolean = listOf(
        "ERROR ", "CONNECTION_DIAGNOSTIC", "Initial CarPlay", "STEP ", "IAP2", "iap2", "mfi ",
        "wired ", "wireless ", "ncm ", "AirPlay session", "AirPlay transport", "connection", "Reconnect",
    ).any { message.startsWith(it, ignoreCase = true) }
}
