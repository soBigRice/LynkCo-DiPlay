package com.shilapi.xcertplay.transport

/** Record address provenance without exporting a hardware address or changing legacy fallback. */
internal object WirelessBluetoothIdentity {
    data class Resolution(val address: String, val source: String, val adapterState: String, val settingsState: String) {
        fun diagnosticSummary(configDerived: Boolean): String =
            "Bluetooth identity source=$source adapter=$adapterState settings=$settingsState " +
                "configDerived=${source == "CONFIG_FALLBACK" && configDerived}"
    }

    fun resolve(adapter: () -> String?, settings: () -> String?, fallback: String,
                saved: String? = null): Resolution {
        val first = probe(adapter)
        val second = probe(settings)
        val manual = HeadUnitBluetoothAddress.normalize(saved)
        val source = when {
            first.first != null -> "ADAPTER"
            second.first != null -> "SETTINGS"
            manual != null -> "MANUAL"
            else -> "CONFIG_FALLBACK"
        }
        return Resolution(first.first ?: second.first ?: manual ?: fallback, source, first.second, second.second)
    }

    private fun probe(read: () -> String?): Pair<String?, String> {
        val value = try { read() }
        catch (_: SecurityException) { return null to "permission-denied" }
        catch (_: RuntimeException) { return null to "read-failed" }
        val state = when {
            value == null -> "unavailable"
            !value.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) -> "invalid"
            value.equals("02:00:00:00:00:00", true) -> "placeholder"
            value == "00:00:00:00:00:00" || value.equals("FF:FF:FF:FF:FF:FF", true) -> "invalid"
            else -> "usable"
        }
        return value.takeIf { state == "usable" } to state
    }
}
