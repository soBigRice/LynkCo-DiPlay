package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** One iPhone's temporary driver handoff. Restoration waits for BOTH USB data connections. */
internal class UsbDriverRecovery(
    private val open: () -> Access?,
    private val diagnostic: (String) -> Unit,
) : Closeable {
    data class Interface(val id: Int, val deviceClass: Int)
    interface Access : Closeable {
        fun active(): Int?
        fun driver(id: Int): String
        fun detachAndClaim(id: Int, expected: String): Int
        fun release(id: Int): Int
        fun select(id: Int): Int
        /** Raw Linux CONNECT return: 1 = bound, 0 = no matching driver, negative = -errno. */
        fun reconnect(id: Int): Int
    }

    private var references = 1
    private val ownerClosed = AtomicBoolean()
    private var access: Access? = null
    private var original = 0
    private var target = 0
    private var targetSelected = false
    private var handoffStarted = false
    private val originals = linkedMapOf<Int, String>()
    private val claimed = mutableSetOf<Int>()

    @Synchronized fun retain(): Closeable {
        check(references > 0) { "USB recovery already closed" }
        references++
        val released = AtomicBoolean()
        return Closeable { if (released.compareAndSet(false, true)) releaseReference() }
    }

    /** Called once, only after the ordinary SET returned EBUSY and active config was read back. */
    fun switch(originalId: Int, targetId: Int, interfaces: List<Interface>): Boolean {
        check(access == null && references > 0)
        val io = open() ?: throw IphoneUsbException.DeviceUnavailable("USB driver handoff could not open recovery connection")
        access = io
        original = originalId
        target = targetId
        if (io.active() != original) return blocked("configuration-changed")
        // Preflight all interfaces before the first mutation. Never detach usbfs or unknown drivers.
        for (intf in interfaces.distinctBy { it.id }) {
            val driver = io.driver(intf.id)
            if (driver == NO_DRIVER) continue
            val allowed = (intf.deviceClass == 1 && driver == "snd-usb-audio") ||
                (intf.deviceClass == 3 && driver == "usbhid")
            if (!allowed) return blocked("unsupported-owner iface=${intf.id}")
            originals[intf.id] = driver
        }
        if (originals.isEmpty()) return blocked("no-known-driver")
        diagnostic("USB handoff begin config=$original interfaces=${originals.size}")
        for ((id, expected) in originals) {
            if (io.active() != original) return blocked("configuration-changed")
            // Detaching an audio control interface can also unbind its streaming interfaces.
            val current = io.driver(id)
            if (current == NO_DRIVER) continue
            if (current != expected) return blocked("owner-changed iface=$id")
            handoffStarted = true
            val error = io.detachAndClaim(id, expected)
            diagnostic("USB handoff iface=$id driver=$expected errno=$error")
            if (error != 0) return blocked("detach-failed iface=$id errno=$error")
            claimed.add(id)
        }
        if (!releaseTemporaryClaims(io)) return blocked("release-failed")
        if (io.active() != original) return blocked("configuration-changed")
        val error = io.select(target)
        targetSelected = error == 0
        val active = io.active()
        diagnostic("USB handoff set=$target errno=$error active=${active ?: "unavailable"}")
        return error == 0 && active == target
    }

    private fun blocked(reason: String): Boolean {
        diagnostic("USB handoff stopped reason=$reason")
        return false
    }

    private fun releaseTemporaryClaims(io: Access): Boolean {
        var success = true
        for (id in claimed.toList().asReversed()) {
            val error = io.release(id)
            diagnostic("USB handoff release iface=$id errno=$error")
            if (error == 0) claimed.remove(id) else success = false
        }
        return success
    }

    override fun close() { if (ownerClosed.compareAndSet(false, true)) releaseReference() }

    private fun releaseReference() {
        val last = synchronized(this) { --references == 0 }
        if (last) restore()
    }

    private fun restore() {
        val io = access ?: return
        var restored = false
        try {
            if (!releaseTemporaryClaims(io)) return
            if (!handoffStarted) { restored = true; return }
            var active = io.active()
            if (active != original) {
                // Never reset a configuration selected by someone else or detach new owners.
                if (!targetSelected || active != target) return
                val error = io.select(original)
                active = io.active()
                diagnostic("USB restore set=$original errno=$error active=${active ?: "unavailable"}")
                if (active != original) return
            }
            restored = true
            for ((id, expected) in originals) {
                val current = io.driver(id)
                if (current == expected) continue
                if (current != NO_DRIVER) { restored = false; continue }
                val result = io.reconnect(id)
                val matches = io.driver(id) == expected
                diagnostic("USB restore iface=$id probeResult=$result driverRestored=$matches")
                if (!matches) restored = false
            }
        } catch (error: Exception) {
            restored = false
            diagnostic("USB restore failureClass=${error.javaClass.simpleName}")
        } catch (_: LinkageError) {
            restored = false
            diagnostic("USB restore native-unavailable")
        } finally {
            try { io.close() } catch (error: Exception) {
                restored = false
                diagnostic("USB restore closeFailure=${error.javaClass.simpleName}")
            }
            diagnostic("USB restore outcome=${if (restored) "drivers-restored" else "replug-required"}")
        }
    }

    companion object { private const val NO_DRIVER = "errno:61" }
}
