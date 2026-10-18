package com.example.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {

    /** Cihazın yerel ağdaki IPv4 adresi (ör. 192.168.1.25). Bulunamazsa null. */
    fun localIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                // Ethernet ve Wi-Fi önce
                .sortedBy { iface ->
                    when {
                        iface.name.startsWith("eth") -> 0
                        iface.name.startsWith("wlan") -> 1
                        else -> 2
                    }
                }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
