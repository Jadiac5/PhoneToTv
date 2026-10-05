package com.phonestream.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Socket

/**
 * A phone can be on Wi-Fi and still send "everything else" somewhere other than the Wi-Fi: mobile data as the
 * default network (Wi-Fi without internet), or a VPN. A connection to a TV on the home network must leave through
 * the home network, so when the default is not Wi-Fi/Ethernet the socket is pinned to the Wi-Fi network.
 */
object LanNetwork {
    /** Pins [socket] (not yet connected) to the local Wi-Fi/Ethernet network when the default one is something else. Best effort. */
    @Suppress("DEPRECATION")
    fun bind(ctx: Context, socket: Socket) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            fun isLan(c: NetworkCapabilities?) =
                c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val active = cm.activeNetwork
            if (active != null && isLan(cm.getNetworkCapabilities(active))) return
            val lan = cm.allNetworks.firstOrNull { isLan(cm.getNetworkCapabilities(it)) } ?: return
            lan.bindSocket(socket)
        } catch (_: Exception) {
        }
    }
}
