package com.shilapi.xcertplay.media

import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack

/** State only. No PCM samples, device names, music metadata or Bluetooth addresses are retained. */
object AudioOutputDiagnostics {
    /** Called only by a bounded background snapshot or export, never the PCM write loop. */
    fun inventory(context: Context): List<String> {
        val manager = context.getSystemService(AudioManager::class.java)
        fun read(block: () -> Any?): String = try { block()?.toString() ?: "unknown" }
            catch (error: Exception) { "unavailable(${error.javaClass.simpleName})" }
        return buildList {
            add("Audio policy ${snapshot(context)} fixedVolume=${read { manager?.isVolumeFixed }} " +
                "speakerphone=${read { manager?.isSpeakerphoneOn }} sco=${read { manager?.isBluetoothScoOn }} " +
                "micMuted=${read { manager?.isMicrophoneMute }} focusOwner=not_exposed_to_app")
            // AOSP defines this operation name, but its constant is not part of the public SDK.
            // The public read-only API checks only our own UID; OEM denial stays "unavailable".
            add("Audio policy playAudioOp=${read { context.getSystemService(android.app.AppOpsManager::class.java)
                ?.checkOpNoThrow("android:play_audio", android.os.Process.myUid(), context.packageName) }} " +
                "outputRate=${read { manager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }} " +
                "outputFrames=${read { manager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }}")
            add("Audio playback visibility=platform_filtered usageCounts=${read {
                manager?.activePlaybackConfigurations?.groupingBy { it.audioAttributes.usage }?.eachCount()?.toSortedMap()
            }}")
            for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL,
                AudioManager.STREAM_ALARM, AudioManager.STREAM_SYSTEM)) {
                add("Audio volume stream=$stream level=${read { manager?.getStreamVolume(stream) }} " +
                    "max=${read { manager?.getStreamMaxVolume(stream) }} muted=${read { manager?.isStreamMute(stream) }}")
            }
            try {
                val devices = manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                add("Audio devices count=${devices?.size ?: "unknown"} shownLimit=8")
                devices?.take(8)?.forEach { device ->
                    // Do not retain productName or a device address (Bluetooth can expose identity).
                    add("Audio device id=${device.id} type=${device.type} sink=${device.isSink} " +
                        "rates=${device.sampleRates.take(12)} channels=${device.channelCounts.take(8)} " +
                        "encodings=${device.encodings.take(12)}")
                }
            } catch (error: Exception) { add("Audio devices unavailable=${error.javaClass.simpleName}") }
        }
    }

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
