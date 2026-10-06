package com.shilapi.xcertplay.platform

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.shared.R

/** Selected packaging policy, not a claim that OEM hidden APIs or the audio HAL are available. */
data class HeadUnitProfile(val coordinatedAudioFocus: Boolean, val wiredMediaHandoff: Boolean) {
    companion object {
        fun read(context: Context): HeadUnitProfile {
            val lynk = context.resources.getBoolean(R.bool.config_lynk_osn_profile)
            return HeadUnitProfile(coordinatedAudioFocus = lynk, wiredMediaHandoff = lynk && Build.VERSION.SDK_INT == 28)
        }
    }
}
