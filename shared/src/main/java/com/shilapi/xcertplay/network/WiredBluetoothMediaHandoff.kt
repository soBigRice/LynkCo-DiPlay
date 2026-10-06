package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only the phone named by a live wired CarPlay command may relinquish A2DP media.
 * No radio, bond, profile priority, HFP or saved wireless phone selection is changed.
 */
class WiredBluetoothMediaHandoff(context: Context, private val report: (String) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val owner = AtomicReference<Any?>()
    private val closed = AtomicBoolean(false)
    private var accepting = true
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lynk-wired-media-handoff").apply { isDaemon = true }
    }
    private var port: AndroidPort? = null
    private var lease: BluetoothMediaLease? = null

    @Synchronized fun begin(session: Any, deviceId: String?) {
        if (!accepting || closed.get() || owner.get() === session) return
        owner.set(session)
        worker.execute {
            release()
            if (closed.get() || owner.get() !== session) return@execute
            try {
                val proxy = openProfile() ?: return@execute
                port = AndroidPort(proxy)
                if (closed.get() || owner.get() !== session) { release(); return@execute }
                lease = BluetoothMediaLease(port!!, report)
                lease!!.handoff(deviceId) { target ->
                    // end/close and a new owner cannot pass between the last check and acceptance.
                    synchronized(this@WiredBluetoothMediaHandoff) {
                        if (closed.get() || owner.get() !== session) false else port!!.disconnect(target)
                    }
                }
                if (closed.get() || owner.get() !== session) release()
            } catch (error: Exception) {
                report("wired Bluetooth media handoff unavailable error=${error.javaClass.simpleName}; radio and pairing unchanged")
                release()
            }
        }
    }

    @Synchronized fun end(session: Any) {
        if (!closed.get() && owner.compareAndSet(session, null)) worker.execute(::release)
    }

    /** Invalidate before controller teardown starts; resource restoration stays on the worker. */
    @Synchronized fun cancel() { accepting = false; owner.set(null) }

    /** Called by controller teardown, never the UI or AirPlay receive thread. */
    override fun close() {
        synchronized(this) {
            if (!closed.compareAndSet(false, true)) return
            cancel()
            worker.execute(::release)
            worker.shutdown()
        }
        var interrupted = false
        while (!worker.isTerminated) {
            try { worker.awaitTermination(1, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun release() {
        try { lease?.close() }
        catch (error: Exception) { report("wired Bluetooth media restore unavailable error=${error.javaClass.simpleName}") }
        finally {
            lease = null
            port?.let { runCatching { adapter?.closeProfileProxy(A2DP_SINK, it.proxy) } }
            port = null
        }
    }

    private fun openProfile(): BluetoothProfile? {
        if (adapter?.isEnabled != true) {
            report("wired Bluetooth media handoff skipped radioEnabled=false")
            return null
        }
        val ready = CountDownLatch(1)
        val lock = Any()
        var accepting = true
        var result: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) = synchronized(lock) {
                if (accepting) { result = proxy; ready.countDown() }
                else adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) { ready.countDown() }
        }
        try {
            if (adapter.getProfileProxy(app, listener, A2DP_SINK)) ready.await(2, TimeUnit.SECONDS)
        } finally { synchronized(lock) { accepting = false } }
        return result.also {
            if (it == null) report("wired Bluetooth media handoff unavailable reason=a2dp_sink_proxy; use car Bluetooth media settings")
        }
    }

    private inner class AndroidPort(val proxy: BluetoothProfile) : BluetoothMediaLease.Port {
        override fun connected(): Set<String> = proxy.connectedDevices.map { it.address }.toSet()
        private fun device(address: String): BluetoothDevice = checkNotNull(adapter).getRemoteDevice(address)
        override fun state(address: String): Int = proxy.getConnectionState(device(address))
        override fun disconnect(address: String): Boolean = invoke("disconnect", address)
        override fun connect(address: String): Boolean = invoke("connect", address)
        private fun invoke(method: String, address: String): Boolean =
            proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, device(address)) == true
        override fun awaitDisconnected(address: String): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (state(address) != BluetoothProfile.STATE_DISCONNECTED && System.nanoTime() < deadline) Thread.sleep(50)
            return state(address) == BluetoothProfile.STATE_DISCONNECTED
        }
    }

    private companion object {
        // Android 9 framework BluetoothProfile.A2DP_SINK (hidden API). OEM denial is reported,
        // never bypassed by disabling the entire adapter or changing profile priority.
        const val A2DP_SINK = 11
    }
}

internal class BluetoothMediaLease(private val port: Port, private val report: (String) -> Unit) : Closeable {
    interface Port {
        fun connected(): Set<String>
        fun state(address: String): Int
        fun disconnect(address: String): Boolean
        fun connect(address: String): Boolean
        fun awaitDisconnected(address: String): Boolean
    }
    private var requested: String? = null
    private var closed = false

    fun handoff(deviceId: String?, requestDisconnect: (String) -> Boolean = port::disconnect) {
        check(!closed)
        val normalized = deviceId?.uppercase()?.takeIf { it.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) }
        val connected = port.connected()
        val target = connected.singleOrNull { it.equals(normalized, ignoreCase = true) }
        report("wired Bluetooth media targetMatched=${target != null} connectedCount=${connected.size}")
        if (target == null || requested != null) return
        val accepted = requestDisconnect(target)
        if (accepted) requested = target
        val disconnected = accepted && port.awaitDisconnected(target)
        report("wired Bluetooth media disconnectAccepted=$accepted disconnected=$disconnected")
    }

    override fun close() {
        if (closed) return
        closed = true
        val target = requested ?: return
        // A delayed disconnect may finish after the initial wait. Observe it before restoring.
        if (port.state(target) == BluetoothProfile.STATE_DISCONNECTING) port.awaitDisconnected(target)
        val connected = port.connected()
        val disconnected = port.state(target) == BluetoothProfile.STATE_DISCONNECTED
        if (connected.isEmpty() && disconnected) {
            report("wired Bluetooth media restoreRequested=${port.connect(target)}; connection confirmation belongs to system")
        } else report("wired Bluetooth media restore skipped connectedCount=${connected.size} targetDisconnected=$disconnected")
        requested = null
    }
}
