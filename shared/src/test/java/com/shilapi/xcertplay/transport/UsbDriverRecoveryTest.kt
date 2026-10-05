package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class UsbDriverRecoveryTest {
    private val interfaces = listOf(UsbDriverRecovery.Interface(0, 1),
        UsbDriverRecovery.Interface(1, 1), UsbDriverRecovery.Interface(1, 1), UsbDriverRecovery.Interface(2, 3))
    private val logs = mutableListOf<String>()
    private val io = FakeAccess()
    private fun owner() = UsbDriverRecovery({ io }, logs::add)

    @Test fun releasesAllClaimsBeforeSwitchAndRestoresOnlyAfterBothDataOwnersClose() {
        val owner = owner()
        assertTrue(owner.switch(2, 6, interfaces))
        assertEquals(6, io.config)
        assertEquals(listOf("detach:0", "detach:2", "release:2", "release:0", "set:6"), io.events)
        val ncm = owner.retain()
        owner.close()
        owner.close()
        assertEquals(6, io.config)
        assertFalse(io.events.contains("close"))
        ncm.close()
        ncm.close()
        assertEquals(2, io.config)
        assertEquals(io.expected, io.drivers)
        assertEquals(1, io.events.count { it == "close" })
        assertTrue(logs.last().contains("drivers-restored"))
        assertThrows(IllegalStateException::class.java) { owner.retain() }
    }

    @Test fun unknownOrUsbfsOwnerIsRejectedBeforeAnyDetach() {
        for (driver in listOf("usbfs", "ipheth", "errno:13")) {
            val device = FakeAccess().apply { drivers[2] = driver }
            UsbDriverRecovery({ device }, logs::add).use { assertFalse(it.switch(2, 6, interfaces)) }
            assertEquals(listOf("close"), device.events)
        }
    }

    @Test fun driverNameAloneDoesNotAuthorizeADeviceClass() {
        owner().use { assertFalse(it.switch(2, 6, listOf(UsbDriverRecovery.Interface(0, 255)))) }
        assertEquals(listOf("close"), io.events)
    }

    @Test fun partialAudioDetachIncludingSiblingIsRecoveredWhenHidTakeoverFails() {
        io.failDetach = 2
        owner().use { assertFalse(it.switch(2, 6, interfaces)) }
        assertFalse(io.events.any { it.startsWith("set:") })
        assertEquals(io.expected, io.drivers)
        assertTrue(logs.last().contains("drivers-restored"))
    }

    @Test fun failedSetReattachesOldConfigurationWithoutRetryingTheMutation() {
        io.failSet = 6
        owner().use { assertFalse(it.switch(2, 6, interfaces)) }
        assertEquals(listOf("set:6"), io.events.filter { it.startsWith("set:") })
        assertEquals(io.expected, io.drivers)
    }

    @Test fun newlyBoundTargetDriverPreventsRestoreWithoutBeingDetached() {
        val owner = owner()
        assertTrue(owner.switch(2, 6, interfaces))
        io.failSet = 2
        val mutations = io.events.size
        owner.close()
        assertEquals(listOf("set:2", "close"), io.events.drop(mutations))
        assertTrue(logs.last().contains("replug-required"))
    }

    @Test fun externallyChangedConfigurationIsNotReset() {
        val owner = owner()
        assertTrue(owner.switch(2, 6, interfaces))
        io.config = 4
        owner.close()
        assertEquals(4, io.config)
        assertFalse(io.events.contains("set:2"))
    }

    @Test fun failedProbeIsNotReportedAsRestored() {
        io.failSet = 6
        io.probeMatches = false
        owner().use { assertFalse(it.switch(2, 6, interfaces)) }
        assertTrue(logs.any { it.contains("probeResult=0 driverRestored=false") })
        assertTrue(logs.last().contains("replug-required"))
    }

    @Test fun cancelledNcmBeforeUsbMuxDoesNotRestoreTooSoon() {
        val owner = owner()
        assertTrue(owner.switch(2, 6, interfaces))
        owner.retain().close()
        assertEquals(6, io.config)
        owner.close()
        assertEquals(2, io.config)
    }

    @Test fun thrownProbeStillClosesManagementConnectionAndRequiresReplug() {
        val owner = owner()
        assertTrue(owner.switch(2, 6, interfaces))
        io.throwProbe = true
        owner.close()
        assertEquals("close", io.events.last())
        assertTrue(logs.last().contains("replug-required"))
        assertFalse(logs.any { it.contains("outcome=drivers-restored") })
    }

    @Test fun driverRaceIsNotOverwrittenDuringRecovery() {
        io.failDetach = 2
        val owner = owner()
        assertFalse(owner.switch(2, 6, interfaces))
        io.drivers[1] = "usbfs"
        owner.close()
        assertEquals("usbfs", io.drivers[1])
        assertFalse(io.events.contains("probe:1"))
        assertTrue(logs.last().contains("replug-required"))
    }

    private class FakeAccess : UsbDriverRecovery.Access {
        val expected = mapOf(0 to "snd-usb-audio", 1 to "snd-usb-audio", 2 to "usbhid")
        val drivers = expected.toMutableMap()
        val events = mutableListOf<String>()
        var config = 2
        var failDetach = -1
        var failSet = -1
        var probeMatches = true
        var throwProbe = false
        override fun active() = config
        override fun driver(id: Int) = drivers[id] ?: "errno:61"
        override fun detachAndClaim(id: Int, expected: String): Int {
            events.add("detach:$id")
            if (id == failDetach || driver(id) != expected) return 16
            drivers[id] = "usbfs"
            if (id == 0) drivers.remove(1) // snd-usb-audio releases its streaming sibling too.
            return 0
        }
        override fun release(id: Int): Int {
            events.add("release:$id")
            assertEquals("usbfs", drivers.remove(id))
            return 0
        }
        override fun select(id: Int): Int {
            events.add("set:$id")
            assertFalse(drivers.containsValue("usbfs"))
            if (id == failSet) return 16
            config = id
            drivers.clear()
            return 0
        }
        override fun reconnect(id: Int): Int {
            events.add("probe:$id")
            if (throwProbe) throw IllegalStateException("simulated reconnect failure")
            if (!probeMatches) return 0
            drivers[id] = expected.getValue(id)
            return 1
        }
        override fun close() { events.add("close") }
    }
}
