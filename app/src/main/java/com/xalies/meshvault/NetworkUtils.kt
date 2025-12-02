package com.xalies.meshvault

import java.net.Inet4Address
import java.net.NetworkInterface

fun getLocalIpAddress(): String {
    try {
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val intf = en.nextElement()
            val enumIpAddr = intf.inetAddresses
            while (enumIpAddr.hasMoreElements()) {
                val inetAddress = enumIpAddr.nextElement()
                if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                    return "http://${inetAddress.hostAddress}:8080"
                }
            }
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
    return "Unavailable"
}