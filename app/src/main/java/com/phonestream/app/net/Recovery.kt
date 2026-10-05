package com.phonestream.app.net

import com.phonestream.app.core.Proto

/**
 * "I can't reach you" sent by a phone to a receiver as a UDP broadcast. A broadcast needs no route and no ARP
 * lookup of the receiver's address, which is exactly what may be broken when a connection fails with
 * "No route to host", so it can arrive when the connection itself cannot.
 */
object HelpRequest {
    fun encode(name: String, host: String): ByteArray = "${Proto.UDP_HELP}\n$name\n$host".toByteArray(Charsets.UTF_8)

    /** True if [data] is a help request addressed to a receiver called [myName] or living at one of [myAddresses]. */
    fun isForMe(data: ByteArray, length: Int, myName: String, myAddresses: List<String>): Boolean {
        val parts = String(data, 0, length, Charsets.UTF_8).split('\n')
        if (parts.size < 3 || parts[0].trim() != Proto.UDP_HELP) return false
        val name = parts[1].trim()
        val host = parts[2].trim()
        return (name.isNotEmpty() && name.equals(myName, ignoreCase = true)) || (host.isNotEmpty() && host in myAddresses)
    }
}

/**
 * When a receiver may reset its network connection by itself. Dropping and rejoining the Wi-Fi costs a few
 * seconds of silence, so it is rate limited, and an automatic reset that never helps is not repeated forever.
 */
class HealPolicy(
    private val cooldownMs: Long = 90_000,
    private val routerFailuresNeeded: Int = 3,
    private val autoResetsWithoutProgress: Int = 3,
) {
    private var lastHealAt = Long.MIN_VALUE
    private var routerFailures = 0
    private var autoResets = 0

    private fun cooledDown(now: Long) = lastHealAt == Long.MIN_VALUE || now - lastHealAt >= cooldownMs

    /** A periodic check of the router. True when the connection should be reset now. */
    fun onRouterCheck(reachable: Boolean, now: Long): Boolean {
        if (reachable) {
            routerFailures = 0
            autoResets = 0
            return false
        }
        if (++routerFailures < routerFailuresNeeded) return false
        if (autoResets >= autoResetsWithoutProgress || !cooledDown(now)) return false
        routerFailures = 0
        autoResets++
        lastHealAt = now
        return true
    }

    /** A phone said it cannot reach this receiver. True when the connection should be reset now. */
    fun onHelpRequest(now: Long): Boolean {
        if (!cooledDown(now)) return false
        lastHealAt = now
        return true
    }

    /** The user asked for it: always allowed, and it restarts the cool-down. */
    fun onManual(now: Long) {
        lastHealAt = now
        routerFailures = 0
        autoResets = 0
    }
}
