package com.phonestream.app.net

import com.phonestream.app.core.Proto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Asks the local network "where is the receiver called X right now?" with the same UDP probe the device list
 * uses. The address the phone remembers can be out of date (the TV got a new address, the mDNS record outlived
 * it); whatever answers a probe is alive at the address it answers from.
 */
object ReceiverLocator {
    /** Reads a UDP reply ("PSTREAM!1\nname\nport") into a receiver at the address it came from, or null if it is not one. */
    fun parseReply(data: ByteArray, length: Int, from: InetAddress): Receiver? {
        val parts = String(data, 0, length, Charsets.UTF_8).split('\n')
        if (parts.size < 3 || parts[0] != Proto.UDP_REPLY) return null
        val port = parts[2].trim().toIntOrNull() ?: return null
        val host = (from as? Inet4Address)?.hostAddress ?: return null
        return Receiver(parts[1].trim().ifEmpty { host }, host, port)
    }

    /**
     * Tells a receiver that cannot be reached to refresh its network connection. Sent three times as a broadcast
     * (it needs no route to the receiver) and straight to [host]; a datagram can get lost, a repeat is cheap.
     */
    fun askForHelp(
        name: String,
        host: String,
        targets: List<InetAddress> = DeviceInfo.broadcastAddresses(),
        port: Int = Proto.DEFAULT_PORT,
    ) {
        val socket = try {
            DatagramSocket().apply { broadcast = true }
        } catch (_: Exception) {
            return
        }
        socket.use { s ->
            val msg = HelpRequest.encode(name, host)
            val all = targets + listOfNotNull(try { InetAddress.getByName(host) } catch (_: Exception) { null })
            for (round in 0 until 3) {
                for (a in all) {
                    try { s.send(DatagramPacket(msg, msg.size, a, port)) } catch (_: Exception) {}
                }
                if (round < 2) try { Thread.sleep(120) } catch (_: InterruptedException) { return }
            }
        }
    }

    /**
     * Probes every address in [targets] plus the [knownHosts] directly and collects replies for about [waitMs].
     * Returns the live receivers called [name] (case-insensitive), or every live receiver when [name] is null.
     * Blocking: call it from a worker thread.
     */
    fun find(
        name: String?,
        knownHosts: List<String> = emptyList(),
        targets: List<InetAddress> = DeviceInfo.broadcastAddresses(),
        port: Int = Proto.DEFAULT_PORT,
        waitMs: Long = 1200,
    ): List<Receiver> {
        val found = LinkedHashMap<String, Receiver>()
        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 150
            }
        } catch (_: Exception) {
            return emptyList()
        }
        socket.use { s ->
            val probe = Proto.UDP_PROBE.toByteArray(Charsets.UTF_8)
            val all = targets + knownHosts.mapNotNull { h -> try { InetAddress.getByName(h) } catch (_: Exception) { null } }
            fun sendProbes() {
                for (a in all) {
                    try { s.send(DatagramPacket(probe, probe.size, a, port)) } catch (_: Exception) {}
                }
            }
            val start = System.nanoTime()
            var resent = false
            val buf = ByteArray(512)
            sendProbes()
            while ((System.nanoTime() - start) / 1_000_000 < waitMs) {
                // A lost datagram is not a verdict: ask a second time half way through.
                if (!resent && (System.nanoTime() - start) / 1_000_000 > waitMs / 2) {
                    resent = true
                    sendProbes()
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val r = parseReply(p.data, p.length, p.address) ?: continue
                    if (name == null || r.name.equals(name, ignoreCase = true)) found.putIfAbsent(r.key, r)
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) {
                    break
                }
            }
        }
        return found.values.toList()
    }
}
