package com.example.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

enum class NetworkKind {
    HOTSPOT,
    WIFI,
    USB_TETHER,
    OTHER
}

data class NetworkEndpoint(
    val interfaceName: String,
    val ipAddress: String,
    val kind: NetworkKind
) {
    fun toFtpUrl(port: Int, username: String? = null, allowAnonymous: Boolean = true): String {
        return if (allowAnonymous || username.isNullOrBlank()) {
            "ftp://$ipAddress:$port/"
        } else {
            "ftp://$username@$ipAddress:$port/"
        }
    }
}

object NetworkAddressDetector {

    fun detectEndpoints(preferHotspot: Boolean = true): List<NetworkEndpoint> {
        val result = mutableListOf<NetworkEndpoint>()
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())

        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            val isWanOrTunnel = name.startsWith("rmnet") || name.startsWith("ccmni") ||
                name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy")
            if (isWanOrTunnel) continue

            val addresses = Collections.list(nif.inetAddresses)
            for (addr in addresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (!isLocalPrivateIp(ip)) continue
                    result.add(NetworkEndpoint(nif.name, ip, classifyInterface(name, ip)))
                }
            }
        }

        return result.sortedWith(compareBy<NetworkEndpoint> { endpoint ->
            when {
                preferHotspot && endpoint.kind == NetworkKind.HOTSPOT -> 0
                endpoint.kind == NetworkKind.WIFI -> if (preferHotspot) 1 else 0
                endpoint.kind == NetworkKind.HOTSPOT -> 1
                endpoint.kind == NetworkKind.USB_TETHER -> 2
                else -> 3
            }
        }.thenBy { it.ipAddress })
    }

    fun isLocalPrivateIp(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "::1") return true
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        return addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
    }

    private fun classifyInterface(ifName: String, ip: String): NetworkKind {
        if (ifName.startsWith("ap") ||
            ifName.startsWith("swlan") ||
            ifName.startsWith("softap") ||
            ifName == "wlan1" ||
            ip.startsWith("192.168.43.") ||
            ip.startsWith("192.168.137.")
        ) {
            return NetworkKind.HOTSPOT
        }
        if (ifName.startsWith("rndis") || ifName.startsWith("usb")) {
            return NetworkKind.USB_TETHER
        }
        if (ifName.startsWith("wlan") || ifName.startsWith("eth")) {
            return NetworkKind.WIFI
        }
        return NetworkKind.OTHER
    }
}
