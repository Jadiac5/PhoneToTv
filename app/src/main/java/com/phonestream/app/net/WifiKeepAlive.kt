package com.phonestream.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps the Wi-Fi radio fully awake while the receiving screen is open. A TV stick's radio that has dozed off
 * answers nothing for a while, so a phone's connect attempt fails ("No route to host") even though the app on
 * the TV is listening. Harmless on Ethernet or when the system ignores the request.
 */
class WifiKeepAlive(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val locks = ArrayList<WifiManager.WifiLock>()

    @Suppress("DEPRECATION")
    fun acquire() {
        if (locks.isNotEmpty()) return
        val modes = if (Build.VERSION.SDK_INT >= 29)
            listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        else listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        for ((i, mode) in modes.withIndex()) {
            try {
                val lock = wifi?.createWifiLock(mode, "PhoneStream:receive$i") ?: continue
                lock.setReferenceCounted(false)
                lock.acquire()
                locks += lock
            } catch (_: Exception) {
            }
        }
    }

    fun release() {
        for (l in locks) {
            try { if (l.isHeld) l.release() } catch (_: Exception) {}
        }
        locks.clear()
    }
}
