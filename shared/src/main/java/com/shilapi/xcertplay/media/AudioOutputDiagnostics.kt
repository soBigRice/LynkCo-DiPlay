package com.shilapi.xcertplay.media

import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack

/** State only. No PCM samples, device names, music metadata or Bluetooth addresses are retained. */
object AudioOutputDiagnostics {
    fun snapshot(context: Context?, track: AudioTrack? = null): String {
        fun read(block: () -> Any?): String = try { block()?.toString() ?: "unknown" }
            catch (error: Exception) { "unavailable(${error.javaClass.simpleName})" }
        val manager = context?.getSystemService(AudioManager::class.java)
        return "mode=${read { manager?.mode }} " +
            "musicVolume=${read { manager?.getStreamVolume(AudioManager.STREAM_MUSIC) }} " +
            "musicMax=${read { manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }} " +
            "musicMuted=${read { manager?.isStreamMute(AudioManager.STREAM_MUSIC) }} " +
            "musicActive=${read { manager?.isMusicActive }} " +
            "routeId=${read { track?.routedDevice?.id }} routeType=${read { track?.routedDevice?.type }} " +
            // BUS addresses identify the vendor mixer, never a remote Bluetooth address.
            "busRoute=${read { track?.routedDevice?.takeIf { it.type == 21 }?.address?.take(64)?.replace(Regex("[^A-Za-z0-9_.:/-]"), "_") }} " +
            "a2dpSinkState=${read { context?.getSystemService(BluetoothManager::class.java)?.adapter?.getProfileConnectionState(11) }}"
    }
}

internal class PcmSignalStats {
    private var samples = 0L
    private var nonzero = 0L
    private var peak = 0

    fun observe(data: ByteArray, offset: Int, length: Int) {
        for (index in offset until offset + length - 1 step 2) {
            val value = ((data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)).toShort().toInt()
            samples++
            if (value != 0) nonzero++
            peak = maxOf(peak, kotlin.math.abs(value))
        }
    }

    fun take(): String {
        val line = "pcmSamples=$samples pcmNonzero=$nonzero pcmPeak=$peak"
        samples = 0; nonzero = 0; peak = 0
        return line
    }
}
