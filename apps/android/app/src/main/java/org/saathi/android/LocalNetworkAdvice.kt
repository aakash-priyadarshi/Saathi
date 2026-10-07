package org.saathi.android

import java.net.NetworkInterface

/** Read existing interfaces only. This does not scan, turn on a hotspot or infer interference. */
internal object LocalNetworkAdvice {
    fun hasWifiAddress(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()?.any { network ->
            network.isUp && network.name.matches(Regex("(?:wlan|swlan|p2p|ap|softap)[A-Za-z0-9_.-]*")) &&
                network.inetAddresses.toList().any { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        } == true
    }.getOrDefault(false)
}
