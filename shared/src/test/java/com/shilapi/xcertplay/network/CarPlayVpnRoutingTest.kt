package com.shilapi.xcertplay.network

import android.net.VpnService
import android.system.OsConstants
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [CarPlayVpnRoutingTest.RouteCapture::class])
class CarPlayVpnRoutingTest {
    @Test fun usbLocalLinkDoesNotBlockTheHeadUnitsNormalIpv4Internet() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).get()
        val builder = service.tunnelBuilder(InetAddress.getByName("fe80::2"))
        val capture = Shadow.extract<RouteCapture>(builder)
        assertTrue("IPv4 must fall through to the head unit network", OsConstants.AF_INET in capture.allowedFamilies)
        assertEquals(listOf(InetAddress.getByName("fe80::2") to 64), capture.addresses)
        assertEquals(1, capture.routes.size)
        assertEquals(64, capture.routes.single().second)
        assertTrue(capture.routes.single().first.isLinkLocalAddress)
        assertFalse("CarPlay must not install a default internet route", capture.routes.any { it.second == 0 })
    }

    // API 28 framework RouteInfo uses Android-only java.net fields absent from the host JDK.
    // Capture the platform builder contract; the packaged APK is also inspected on actual API 28.
    @Implements(VpnService.Builder::class)
    class RouteCapture {
        @RealObject lateinit var builder: VpnService.Builder
        val addresses = mutableListOf<Pair<InetAddress, Int>>()
        val routes = mutableListOf<Pair<InetAddress, Int>>()
        val allowedFamilies = mutableSetOf<Int>()
        @Implementation fun addAddress(address: InetAddress, prefix: Int): VpnService.Builder {
            addresses += address to prefix; return builder
        }
        @Implementation fun addRoute(address: InetAddress, prefix: Int): VpnService.Builder {
            routes += address to prefix; return builder
        }
        @Implementation fun allowFamily(family: Int): VpnService.Builder {
            allowedFamilies += family; return builder
        }
    }
}
