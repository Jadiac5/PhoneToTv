package com.phonestream.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** What the receiving device can tell about its own connection to the network (for the screen, and for self-checks). */
data class WifiState(val rssi: Int, val frequencyMhz: Int, val linkMbps: Int)

object NetworkHealth {
    /** The Wi-Fi link, or null on Ethernet / when not connected to Wi-Fi. */
    @Suppress("DEPRECATION")
    fun wifi(ctx: Context): WifiState? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wm?.connectionInfo
        if (info == null || info.networkId == -1 || info.rssi <= -127) null
        else WifiState(info.rssi, if (android.os.Build.VERSION.SDK_INT >= 21) info.frequency else 0, info.linkSpeed)
    } catch (_: Exception) {
        null
    }

    /** The default gateway (the router) of the active network, if it has an IPv4 one. */
    fun gateway(ctx: Context): String? = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork)
        lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    } catch (_: Exception) {
        null
    }

    /**
     * Whether the router answers us. A refused connection counts as an answer (something is there and said no);
     * only silence and "no route" mean the link is broken. Blocking: call it from a worker thread.
     */
    fun routerReachable(host: String, timeoutMs: Int = 1500): Boolean {
        try {
            if (InetAddress.getByName(host).isReachable(timeoutMs)) return true
        } catch (_: Exception) {
        }
        for (port in intArrayOf(80, 443, 53)) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 700) }
                return true
            } catch (e: ConnectException) {
                if (e.message?.contains("ECONNREFUSED") == true) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun band(frequencyMhz: Int): String = when {
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5900 -> "5 GHz"
        frequencyMhz >= 5925 -> "6 GHz"
        else -> ""
    }

    /** One line for the idle screen: enough to tell, from a photo of the TV, what state its network is in. */
    fun summary(wifi: WifiState?, hasAddress: Boolean, routerOk: Boolean?, contactAgoSec: Long?, contactFrom: String?, resetNote: String?): String {
        val parts = ArrayList<String>()
        parts += when {
            !hasAddress -> "No network"
            wifi == null -> "Wired or no Wi-Fi info"
            else -> listOfNotNull(
                "Wi-Fi", band(wifi.frequencyMhz).ifEmpty { null }, "${wifi.rssi} dBm", wifi.linkMbps.takeIf { it > 0 }?.let { "$it Mbps" }
            ).joinToString(" · ")
        }
        when (routerOk) {
            true -> parts += "router answers"
            false -> parts += "router NOT answering"
            null -> {}
        }
        parts += if (contactAgoSec == null) "no phone heard yet"
        else "phone heard ${ago(contactAgoSec)}" + (contactFrom?.let { " ($it)" } ?: "")
        if (!resetNote.isNullOrEmpty()) parts += resetNote
        return parts.joinToString("  ·  ")
    }

    private fun ago(sec: Long): String = when {
        sec < 5 -> "just now"
        sec < 90 -> "${sec}s ago"
        sec < 5400 -> "${sec / 60} min ago"
        else -> "${sec / 3600} h ago"
    }
}
