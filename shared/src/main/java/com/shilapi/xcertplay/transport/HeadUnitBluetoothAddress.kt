package com.shilapi.xcertplay.transport

/** Syntax validation only; callers must obtain this from the head unit, never a generated ID. */
object HeadUnitBluetoothAddress {
    fun normalize(value: String?): String? {
        val address = value?.trim()?.replace('-', ':')?.uppercase(java.util.Locale.ROOT) ?: return null
        if (!address.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) return null
        return address.takeUnless { it in setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF") }
    }
}
