package com.phonestream.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * Drops the Wi-Fi connection and joins it again: what switching Wi-Fi off and on in the settings does, which is
 * the cheap cure for a TV whose Wi-Fi has stopped answering the network while the app on it is fine. Newer Android
 * versions refuse this to ordinary apps; then [reconnect] says so and the caller tells the user to do it by hand.
 */
object WifiRecovery {
    private val main = Handler(Looper.getMainLooper())

    /** True if the system accepted the request (the rejoin is then done by itself a moment later). */
    @Suppress("DEPRECATION")
    fun reconnect(ctx: Context): Boolean {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return try {
            if (!wifi.isWifiEnabled) return false
            if (!wifi.disconnect()) return false
            // Whatever happens to the screen that asked, the Wi-Fi must come back: this runs on the app's own looper.
            main.postDelayed({ rejoin(wifi, 0) }, 1500)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun rejoin(wifi: WifiManager, round: Int) {
        try {
            wifi.reconnect()
            // The system normally rejoins on its own; if it has not within a few seconds, push once more.
            if (round == 0) main.postDelayed({ checkJoined(wifi) }, 8000)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun checkJoined(wifi: WifiManager) {
        try {
            if (wifi.isWifiEnabled && wifi.connectionInfo?.networkId == -1) {
                wifi.reassociate()
                wifi.reconnect()
            }
        } catch (_: Exception) {
        }
    }
}
