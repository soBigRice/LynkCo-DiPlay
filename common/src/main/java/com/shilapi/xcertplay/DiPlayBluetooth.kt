package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

internal object DiPlayBluetooth {
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
