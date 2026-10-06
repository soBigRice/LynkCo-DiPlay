package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WiredBluetoothMediaHandoffTest {
    class Profile(private val device: BluetoothDevice) : BluetoothProfile {
        val selecting = CountDownLatch(1)
        val resumeSelection = CountDownLatch(1)
        var disconnects = 0
        override fun getConnectedDevices(): List<BluetoothDevice> {
            selecting.countDown()
            check(resumeSelection.await(3, TimeUnit.SECONDS))
            return listOf(device)
        }
        override fun getDevicesMatchingConnectionStates(states: IntArray) = listOf(device)
        override fun getConnectionState(device: BluetoothDevice) = BluetoothProfile.STATE_CONNECTED
        fun disconnect(device: BluetoothDevice): Boolean { disconnects++; return false }
        fun connect(device: BluetoothDevice): Boolean = error("No accepted disconnect to restore")
    }

    @Test fun cancelledControllerCannotDisconnectAfterTheProfileLookupReturns() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")
        val profile = Profile(device)
        shadowOf(adapter).setProfileProxy(11, profile)
        val handoff = WiredBluetoothMediaHandoff(RuntimeEnvironment.getApplication()) {}
        try {
            handoff.begin(Any(), device.address)
            assertTrue(profile.selecting.await(3, TimeUnit.SECONDS))
            handoff.cancel()
            // A late command after controller invalidation must not create a new owner either.
            handoff.begin(Any(), device.address)
            profile.resumeSelection.countDown()
        } finally { profile.resumeSelection.countDown(); handoff.close() }
        assertEquals(0, profile.disconnects)
        assertFalse(shadowOf(adapter).hasActiveProfileProxy(11))
    }
}
