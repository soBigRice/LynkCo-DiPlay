package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothAdapter.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BluetoothRadioLeaseTest {
    private class Radio : BluetoothRadioLease.Port {
        override var state = STATE_ON
        override var pending = false
        var disables = 0
        var enables = 0
        var journalWorks = true
        var enableWorks = true
        var offWait: (() -> Unit)? = null
        override fun journal(value: Boolean): Boolean {
            if (!journalWorks) return false
            pending = value; return true
        }
        override fun disable(): Boolean { assertTrue(pending); disables++; state = STATE_OFF; return true }
        override fun enable(): Boolean { enables++; if (enableWorks) state = STATE_ON; return enableWorks }
        override fun await(target: Int): Boolean { if (target == STATE_OFF) offWait?.invoke(); return state == target }
    }

    @Test fun repeatedHandoffAndCloseSwitchOnlyOnce() {
        val radio = Radio(); val lease = BluetoothRadioLease(radio)
        lease.suspendForSession(); lease.suspendForSession()
        assertEquals(STATE_OFF, radio.state); assertTrue(radio.pending)
        lease.close(); lease.close()
        assertEquals(1, radio.disables); assertEquals(1, radio.enables); assertFalse(radio.pending)
    }

    @Test fun oldLeaseCannotRestoreANewerSession() {
        val radio = Radio(); val first = BluetoothRadioLease(radio)
        first.suspendForSession(); first.close()
        val next = BluetoothRadioLease(radio)
        next.suspendForSession(); first.close()
        assertEquals(STATE_OFF, radio.state); assertTrue(radio.pending)
        assertEquals(1, radio.enables)
        next.close(); assertEquals(STATE_ON, radio.state)
    }

    @Test fun closeBeforeHandoffPreventsLateDisable() {
        val radio = Radio(); val lease = BluetoothRadioLease(radio)
        lease.close()
        assertThrows(IOException::class.java) { lease.suspendForSession() }
        assertEquals(0, radio.disables); assertEquals(0, radio.enables)
    }

    @Test fun originallyOffRadioIsNeverTakenOver() {
        val radio = Radio().apply { state = STATE_OFF }; val lease = BluetoothRadioLease(radio)
        assertThrows(IOException::class.java) { lease.suspendForSession() }
        lease.close(); assertEquals(0, radio.enables); assertFalse(radio.pending)
    }

    @Test fun journalFailurePreventsDisablingBluetooth() {
        val radio = Radio().apply { journalWorks = false }
        assertThrows(IOException::class.java) { BluetoothRadioLease(radio).suspendForSession() }
        assertEquals(0, radio.disables)
    }

    @Test fun failedRestoreRetainsJournalForNextAppLaunch() {
        val radio = Radio(); val lease = BluetoothRadioLease(radio)
        lease.suspendForSession(); radio.enableWorks = false
        assertThrows(IOException::class.java) { lease.close() }
        assertTrue(radio.pending)
        radio.enableWorks = true
        BluetoothRadioLease.restore(radio)
        assertEquals(STATE_ON, radio.state); assertFalse(radio.pending)
    }

    @Test fun manuallyRestoredRadioClearsJournalWithoutAnotherEnable() {
        val radio = Radio().apply { pending = true }
        BluetoothRadioLease.restore(radio)
        assertEquals(0, radio.enables); assertFalse(radio.pending)
    }

    @Test fun cancelWaitsForInFlightDisableThenRestores() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val radio = Radio().apply { offWait = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) } }
        val lease = BluetoothRadioLease(radio); val pool = Executors.newFixedThreadPool(2)
        try {
            val suspend = pool.submit { lease.suspendForSession() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val close = pool.submit { lease.close() }
            assertFalse(close.isDone)
            release.countDown(); suspend.get(2, TimeUnit.SECONDS); close.get(2, TimeUnit.SECONDS)
            assertEquals(STATE_ON, radio.state); assertFalse(radio.pending)
            assertThrows(IOException::class.java) { lease.suspendForSession() }
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
