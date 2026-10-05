package com.shilapi.xcertplay.network

/** A running system AP can already have its address, including only scoped IPv6. */
internal object ManualHotspotInterfacePolicy {
    data class Candidate(val name: String, val ipv4: Set<String>)

    fun select(candidates: List<Candidate>, upstreamInterfaces: Set<String>, stationIpv4: String?): String? {
        val possible = candidates.filter {
            it.name !in upstreamInterfaces &&
                (stationIpv4 == null || stationIpv4 !in it.ipv4)
        }
        val explicitAp = possible.filter { it.name.matches(Regex("(?:ap|softap)[0-9]+")) }
        if (explicitAp.isNotEmpty()) return explicitAp.singleOrNull()?.name
        // Some firmware uses wlan0/1 for SoftAP. An unrelated interface or an ambiguous
        // Wi-Fi set cannot safely stand in for the AP while its address is being assigned.
        return possible.filter { it.name.matches(Regex("(?:wlan|swlan)[0-9]+")) }.singleOrNull()?.name
    }
}
