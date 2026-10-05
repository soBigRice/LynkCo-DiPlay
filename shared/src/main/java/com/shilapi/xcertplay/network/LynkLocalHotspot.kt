package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.shared.R
import java.io.IOException

/** This compatibility path is scoped to the approved OS N / Android 9 profile. */
object LynkLocalHotspot {
    fun supported(context: Context): Boolean = Build.VERSION.SDK_INT == 28 &&
        context.resources.getBoolean(R.bool.config_lynk_local_hotspot)
}

internal object LocalHotspotRadioPolicy {
    fun channel(band: String, configuredChannel: Int, frequencyMHz: Int?, allowTwoPointFour: Boolean): Int {
        val actualBand = when (frequencyMHz) {
            null -> band
            in 2412..2484 -> "2.4 GHz"
            in 5160..5895 -> "5 GHz"
            else -> "unknown"
        }
        if (actualBand != "5 GHz" && !(allowTwoPointFour && actualBand == "2.4 GHz")) {
            throw IOException("LocalOnlyHotspot unsupported band; use the car hotspot")
        }
        val channel = if (frequencyMHz == null) configuredChannel else wifiFrequencyMhzToChannel(frequencyMHz)
            ?: throw IOException("LocalOnlyHotspot invalid measured channel")
        val valid = if (actualBand == "2.4 GHz") channel in 1..14 else channel in 32..177
        if (!valid) throw IOException("LocalOnlyHotspot AP channel unavailable; use the car hotspot or export diagnostics")
        return channel
    }
}
