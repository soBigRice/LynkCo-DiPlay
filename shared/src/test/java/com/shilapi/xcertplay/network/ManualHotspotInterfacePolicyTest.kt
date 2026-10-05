package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualHotspotInterfacePolicyTest {
    private fun net(name: String, ipv4: String? = null) =
        ManualHotspotInterfacePolicy.Candidate(name, setOfNotNull(ipv4))

    @Test fun waitsInsteadOfAdvertisingEthernetUsbCellularOrP2p() {
        assertNull(ManualHotspotInterfacePolicy.select(listOf(net("eth0"), net("rndis0"),
            net("ccmni0"), net("p2p0"), net("apcli0")), emptySet(), null))
    }

    @Test fun rejectsWifiStationEvenWhenCellularIsTheDefaultNetwork() {
        assertNull(ManualHotspotInterfacePolicy.select(listOf(net("wlan0", "192.168.1.5")),
            setOf("rmnet0", "wlan0"), null))
        assertNull(ManualHotspotInterfacePolicy.select(listOf(net("wlan0", "192.168.1.5")),
            setOf("rmnet0"), "192.168.1.5"))
    }

    @Test fun choosesAnExistingExplicitApAndAllowsIpv6OnlyAddresses() {
        assertEquals("ap0", ManualHotspotInterfacePolicy.select(
            listOf(net("wlan0", "192.168.1.5"), net("ap0")), setOf("wlan0"), "192.168.1.5"))
    }

    @Test fun permitsAnUnambiguousWlanApButRefusesGuessingBetweenTwo() {
        assertEquals("wlan1", ManualHotspotInterfacePolicy.select(listOf(net("wlan1")), emptySet(), null))
        assertNull(ManualHotspotInterfacePolicy.select(listOf(net("wlan0"), net("wlan1")), emptySet(), null))
        assertNull(ManualHotspotInterfacePolicy.select(listOf(net("ap0"), net("ap1")), emptySet(), null))
    }
}
