package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** OEM hotspots may route IPv4 reliably while exposing an unusable IPv6 link-local address. */
internal fun wirelessHostAddress(
    addresses: List<InetAddress>,
    interfaceIndex: Int,
    preferIpv4: Boolean = false,
): InetAddress? {
    val ipv4 = addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    if (preferIpv4 && ipv4 != null) return ipv4
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return ipv4
}
