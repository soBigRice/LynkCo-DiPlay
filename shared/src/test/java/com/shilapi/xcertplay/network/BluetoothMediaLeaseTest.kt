package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothProfile
import org.junit.Assert.*
import org.junit.Test

class BluetoothMediaLeaseTest {
    private val phone = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"
    private inner class Port : BluetoothMediaLease.Port {
        val devices = mutableSetOf(phone)
        var accepted = true
        var delayed = false
        var disconnects = 0
        var connects = 0
        override fun connected() = devices.toSet()
        override fun state(address: String) = if (address in devices) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
        override fun disconnect(address: String): Boolean {
            disconnects++
            if (accepted && !delayed) devices.remove(address)
            return accepted
        }
        override fun connect(address: String): Boolean { connects++; devices += address; return true }
        override fun awaitDisconnected(address: String) = address !in devices
    }
    @Test fun commandMatchesOnlyItsConnectedPhoneAndRestoresOnce() {
        val port = Port(); val lines = mutableListOf<String>(); val lease = BluetoothMediaLease(port, lines::add)
        lease.handoff(phone.lowercase()); lease.handoff(phone)
        assertEquals(1, port.disconnects)
        lease.close(); lease.close()
        assertEquals(1, port.connects)
        assertTrue(lines.none { it.contains(phone, true) })
    }
    @Test fun unknownOrMissingTargetNeverGuessesTheOnlyConnectedPhone() {
        for (id in listOf(null, "not-a-mac", other)) {
            val port = Port(); val lease = BluetoothMediaLease(port) {}
            lease.handoff(id); lease.close()
            assertEquals(0, port.disconnects); assertEquals(0, port.connects)
        }
    }
    @Test fun cancellationAtAcceptanceBoundaryDoesNotTouchThePhone() {
        val port = Port(); val lease = BluetoothMediaLease(port) {}
        lease.handoff(phone) { false }; lease.close()
        assertEquals(0, port.disconnects); assertEquals(0, port.connects)
    }

    @Test fun rejectedDisconnectDoesNotAcquireRestorationOwnership() {
        val port = Port().apply { accepted = false }; val lease = BluetoothMediaLease(port) {}
        lease.handoff(phone); port.devices.clear(); lease.close()
        assertEquals(0, port.connects)
    }
    @Test fun anotherConnectedPhoneMustNotBeDisplacedByRestore() {
        val port = Port(); val lease = BluetoothMediaLease(port) {}
        lease.handoff(phone); port.devices += other; lease.close()
        assertEquals(setOf(other), port.devices); assertEquals(0, port.connects)
    }
    @Test fun delayedDisconnectStillRestoresAfterCancellation() {
        val port = Port().apply { delayed = true }; val lease = BluetoothMediaLease(port) {}
        lease.handoff(phone); port.devices.clear(); lease.close()
        assertEquals(1, port.connects)
    }
}
