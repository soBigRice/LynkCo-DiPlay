package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

internal object DiPlayBluetooth {
    // Never replace an explicitly selected phone just because it is temporarily absent.
    // A renamed iPhone still matches its bond address; comparison tolerates old lowercase prefs.
    fun preferredPhone(devices: Set<android.bluetooth.BluetoothDevice>, savedAddress: String?): android.bluetooth.BluetoothDevice? {
        if (savedAddress != null) return devices.firstOrNull { it.address.equals(savedAddress, ignoreCase = true) }
        return devices.filter { it.name?.contains("iPhone", ignoreCase = true) == true }.singleOrNull()
    }

    fun configuredHeadUnitAddress(context: Context): String? =
        addressCandidates(context).firstNotNullOfOrNull {
            com.shilapi.xcertplay.transport.HeadUnitBluetoothAddress.normalize(it)
        }
            ?: AirPlayPersistence.loadHeadUnitBluetoothAddress(context)

    private fun addressCandidates(context: Context): List<String> {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting)
    }

    fun localAddress(context: Context): String? =
        addressCandidates(context).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
}
