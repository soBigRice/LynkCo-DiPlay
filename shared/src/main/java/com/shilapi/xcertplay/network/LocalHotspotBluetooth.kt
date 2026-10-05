package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import java.io.IOException

/** A journal survives process death; recovery never claims success until STATE_ON is observed. */
object LocalHotspotBluetooth {
    private val lock = Any()
    @Volatile private var active: BluetoothRadioLease? = null
    private const val PREFS = "lynk_hotspot_radio"
    private const val RESTORE = "restore_bluetooth"

    fun needsRecovery(context: Context): Boolean = LynkLocalHotspot.supported(context) &&
        active == null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RESTORE, false)

    /** Worker thread only. An active CarPlay lease must never be recovered by a reopened menu. */
    fun recover(context: Context) = synchronized(lock) {
        if (needsRecovery(context)) BluetoothRadioLease.restore(AndroidPort(context))
    }

    internal fun acquire(context: Context): BluetoothRadioLease = synchronized(lock) {
        check(LynkLocalHotspot.supported(context))
        check(active == null) { "Bluetooth handoff is still owned by another session" }
        val port = AndroidPort(context)
        BluetoothRadioLease.restore(port)
        BluetoothRadioLease(port, lock) { lease -> if (active === lease) active = null }.also { active = it }
    }

    @Suppress("DEPRECATION")
    private class AndroidPort(context: Context) : BluetoothRadioLease.Port {
        private val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth adapter is unavailable")
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override val state get() = adapter.state
        override val pending get() = prefs.getBoolean(RESTORE, false)
        override fun journal(value: Boolean) = prefs.edit().putBoolean(RESTORE, value).commit()
        override fun disable() = adapter.disable()
        override fun enable() = adapter.enable()
        override fun await(target: Int): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 8_000
            // Cancellation must not abort restoration halfway through an asynchronous radio switch.
            var interrupted = Thread.interrupted()
            try {
                while (state != target && SystemClock.elapsedRealtime() < deadline) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { interrupted = true }
                }
                return state == target
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }
}

internal class BluetoothRadioLease(
    private val port: Port,
    private val lock: Any = Any(),
    private val released: (BluetoothRadioLease) -> Unit = {},
) : AutoCloseable {
    interface Port {
        val state: Int
        val pending: Boolean
        fun journal(value: Boolean): Boolean
        fun disable(): Boolean
        fun enable(): Boolean
        fun await(target: Int): Boolean
    }

    private var closed = false
    private var suspended = false

    fun suspendForSession() = synchronized(lock) {
        if (closed) throw IOException("Bluetooth handoff cancelled")
        if (suspended) return@synchronized
        if (port.state != BluetoothAdapter.STATE_ON) throw IOException("Bluetooth adapter is not ready for handoff")
        if (!port.journal(true)) throw IOException("Bluetooth recovery journal could not be saved")
        if (!port.disable() || !port.await(BluetoothAdapter.STATE_OFF)) {
            throw IOException("Bluetooth suspension failed; open Bluetooth settings before retrying")
        }
        suspended = true
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        try { restore(port) } finally { released(this) }
    }

    companion object {
        fun restore(port: Port) {
            if (!port.pending) return
            if (port.state == BluetoothAdapter.STATE_TURNING_OFF && !port.await(BluetoothAdapter.STATE_OFF)) {
                throw IOException("Bluetooth restore pending: radio is still switching off")
            }
            if (port.state == BluetoothAdapter.STATE_OFF && !port.enable()) {
                throw IOException("Bluetooth restore pending: open Bluetooth settings to turn it on")
            }
            if (!port.await(BluetoothAdapter.STATE_ON)) {
                throw IOException("Bluetooth restore pending: open Bluetooth settings to turn it on")
            }
            if (!port.journal(false)) throw IOException("Bluetooth recovery journal could not be cleared")
        }
    }
}
