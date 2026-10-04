package com.phonestream.app.net

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object DeviceInfo {
    /** The name the user gave this device (Settings > About > Device name), falling back to the model. */
    fun deviceName(ctx: Context): String {
        val cr = ctx.contentResolver
        val sources = listOf<() -> String?>(
            { Settings.Global.getString(cr, "device_name") },
            { Settings.Secure.getString(cr, "bluetooth_name") },
            { Settings.System.getString(cr, "device_name") },
        )
        for (src in sources) {
            try {
                val v = src()?.trim()
                if (!v.isNullOrEmpty()) return v
            } catch (_: Exception) {
            }
        }
        val model = Build.MODEL?.trim().orEmpty().ifEmpty { "Android device" }
        val maker = Build.MANUFACTURER?.trim().orEmpty()
        return if (maker.isNotEmpty() && !model.startsWith(maker, ignoreCase = true)) {
            maker.replaceFirstChar { it.uppercase() } + " " + model
        } else model
    }

    private fun interfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
    } catch (_: Exception) {
        emptyList()
    }

    /** This device's IPv4 addresses on the local network(s). */
    fun localIpv4(): List<String> = interfaces()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()

    /** Directed broadcast address of every connected subnet, plus the global one. */
    fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        for (ni in interfaces()) {
            try {
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
            } catch (_: Exception) {
            }
        }
        try {
            out += InetAddress.getByName("255.255.255.255")
        } catch (_: Exception) {
        }
        return out.toList()
    }
}
