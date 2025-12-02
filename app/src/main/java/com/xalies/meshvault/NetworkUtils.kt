package com.xalies.meshvault

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

fun getLocalIpAddress(): String {
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

        // 1. Try to find the Wi-Fi interface (wlan0) or Hotspot (ap0) first
        for (intf in interfaces) {
            if (intf.name.contains("wlan") || intf.name.contains("ap")) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return "http://${addr.hostAddress}:8080"
                    }
                }
            }
        }

        // 2. Fallback: If no Wi-Fi found, return ANY valid non-loopback address
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return "http://${addr.hostAddress}:8080"
                }
            }
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
    return "Error: Check Wi-Fi"
}