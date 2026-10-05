package com.phonestream.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.phonestream.app.core.Proto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** A device on the network that is in Receive mode and free to take a stream. */
data class Receiver(val name: String, val host: String, val port: Int) {
    val key: String get() = "$host:$port"
}

/**
 * RECEIVER side: announces "I'm here and free" two ways, because home networks are unpredictable:
 *  - mDNS / DNS-SD (NsdManager) service  _phonestream._tcp
 *  - a tiny UDP responder on the same port number for subnet-broadcast probes (works when mDNS is filtered)
 *
 * While a sender is connected the receiver is [busy] and withdraws both, so it vanishes from other
 * senders' lists ("not already connected to a device").
 */
class ReceiverAdvertiser(context: Context, private val name: String, private val port: Int = Proto.DEFAULT_PORT) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private enum class Reg { IDLE, REGISTERING, REGISTERED, UNREGISTERING }

    // All of the following is touched on the main thread only.
    private var reg = Reg.IDLE
    private var wantRegistered = false
    private var restartPending = false
    private var started = false
    private var retry: Runnable? = null

    @Volatile
    private var busy = false

    /** A phone asked this receiver, by name or address, to refresh its network connection (called on a background thread). */
    @Volatile
    var onHelpRequested: ((from: String) -> Unit)? = null

    /** The last discovery probe or help request that reached this receiver: when (ms of [System.nanoTime]) and from where. */
    @Volatile
    var lastHeardAt = 0L
        private set

    @Volatile
    var lastHeardFrom: String? = null
        private set

    private var udp: DatagramSocket? = null
    private var udpThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        main.post {
            if (started) return@post
            started = true
            try {
                val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("phonestream-discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
            }
            startUdp()
            wantRegistered = !busy
            reconcile()
        }
    }

    fun stop() {
        main.post {
            started = false
            wantRegistered = false
            restartPending = false
            retry?.let { main.removeCallbacks(it) }
            reconcile()
            stopUdp()
            try { multicastLock?.release() } catch (_: Exception) {}
            multicastLock = null
        }
    }

    /** Busy = a sender is connected: stop being discoverable until it leaves. */
    fun setBusy(value: Boolean) {
        busy = value
        main.post {
            if (!started) return@post
            wantRegistered = !value
            reconcile()
        }
    }

    /** Re-announce (e.g. after the Wi-Fi network / IP address changed). */
    fun refresh() {
        main.post {
            if (!started || busy) return@post
            if (reg == Reg.IDLE) {
                wantRegistered = true
                reconcile()
            } else {
                restartPending = true
                wantRegistered = false
                reconcile()
            }
        }
    }

    // ---- NSD registration state machine -------------------------------------------------------------

    private fun reconcile() {
        when {
            wantRegistered && reg == Reg.IDLE -> register()
            !wantRegistered && reg == Reg.REGISTERED -> unregister()
            // REGISTERING / UNREGISTERING: wait for the callback, which calls reconcile() again.
        }
    }

    private var currentListener: NsdManager.RegistrationListener? = null

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Proto.NSD_TYPE
            port = this@ReceiverAdvertiser.port
            setAttribute("n", name)
        }
        val listener = object : NsdManager.RegistrationListener {
            // NsdManager calls these on its own thread; hop to main where all the state lives.
            private fun onMain(block: () -> Unit) {
                main.post {
                    if (currentListener === this) block()
                }
            }

            override fun onServiceRegistered(i: NsdServiceInfo) {
                onMain {
                    reg = Reg.REGISTERED
                    reconcile()
                }
            }

            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {
                onMain {
                    reg = Reg.IDLE
                    currentListener = null
                    // Try again in a few seconds, as long as we still want to be visible.
                    val r = Runnable { reconcile() }
                    retry = r
                    if (wantRegistered && started) main.postDelayed(r, 3000)
                }
            }

            override fun onServiceUnregistered(i: NsdServiceInfo) {
                onMain {
                    reg = Reg.IDLE
                    currentListener = null
                    if (restartPending) {
                        restartPending = false
                        wantRegistered = started && !busy
                    }
                    reconcile()
                }
            }

            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {
                onMain {
                    reg = Reg.IDLE
                    currentListener = null
                    reconcile()
                }
            }
        }
        currentListener = listener
        reg = Reg.REGISTERING
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            reg = Reg.IDLE
            currentListener = null
        }
    }

    private fun unregister() {
        val l = currentListener ?: run { reg = Reg.IDLE; return }
        reg = Reg.UNREGISTERING
        try {
            nsd.unregisterService(l)
        } catch (e: Exception) {
            reg = Reg.IDLE
            currentListener = null
        }
    }

    // ---- UDP responder ------------------------------------------------------------------------------

    private fun startUdp() {
        if (udp != null) return
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(port))
            udp = s
            s.soTimeout = ANNOUNCE_MS.toInt()
            udpThread = Thread({
                val buf = ByteArray(256)
                var lastAnnounce = SystemClock.elapsedRealtime()
                while (!s.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        if (busy) continue
                        val text = String(p.data, 0, p.length, Charsets.UTF_8).trim()
                        if (text == Proto.UDP_PROBE) {
                            lastHeardFrom = p.address.hostAddress
                            lastHeardAt = System.nanoTime() / 1_000_000
                            val reply = replyBytes()
                            s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                        } else if (text.startsWith(Proto.UDP_HELP) && HelpRequest.isForMe(p.data, p.length, name, DeviceInfo.localIpv4())) {
                            lastHeardFrom = p.address.hostAddress
                            lastHeardAt = System.nanoTime() / 1_000_000
                            onHelpRequested?.invoke(p.address.hostAddress ?: "a phone")
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (s.isClosed) break
                    }
                    // Unprompted "I'm here" now and then: it keeps a sleepy Wi-Fi radio awake and the router's idea
                    // of where this device is fresh, which is what makes a TV answer when a phone connects later.
                    val now = SystemClock.elapsedRealtime()
                    if (!busy && !s.isClosed && now - lastAnnounce >= ANNOUNCE_MS) {
                        lastAnnounce = now
                        val reply = replyBytes()
                        for (addr in DeviceInfo.broadcastAddresses()) {
                            try { s.send(DatagramPacket(reply, reply.size, addr, port)) } catch (_: Exception) {}
                        }
                    }
                }
            }, "ps-udp-responder").apply { isDaemon = true; start() }
        } catch (_: Exception) {
            // Port taken or no network yet: mDNS (and manual IP) still work.
            udp = null
        }
    }

    private fun replyBytes() = "${Proto.UDP_REPLY}\n$name\n$port".toByteArray(Charsets.UTF_8)

    private fun stopUdp() {
        try { udp?.close() } catch (_: Exception) {}
        udp = null
        udpThread = null
    }

    private companion object {
        const val ANNOUNCE_MS = 3000L
    }
}

/**
 * SENDER side: finds free receivers via mDNS and via UDP broadcast probes, merges both sources by
 * address and keeps the list fresh. [onChanged] is always invoked on the main thread.
 */
class SenderDiscovery(context: Context, private val onChanged: (List<Receiver>) -> Unit) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private class Entry(var receiver: Receiver, var viaNsd: Boolean, var lastSeen: Long, var seenViaUdp: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val nsdNames = ConcurrentHashMap<String, String>() // NSD service name -> entry key

    @Volatile
    private var running = false

    /** The screen wants discovery on (between [start] and [stop]); [restart] pauses [running] but not this. */
    @Volatile
    private var wanted = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var probeThread: Thread? = null
    private var probeSocket: DatagramSocket? = null

    // NsdManager can only resolve one service at a time: serialise.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        wanted = true
        begin()
    }

    private fun begin() {
        if (running) return
        running = true
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("phonestream-scan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        startNsd()
        startProbing()
        main.post(pruneTask)
    }

    fun stop() {
        wanted = false
        halt()
    }

    private fun halt() {
        running = false
        main.removeCallbacks(pruneTask)
        stopNsd()
        try { probeSocket?.close() } catch (_: Exception) {}
        probeSocket = null
        probeThread = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        entries.clear()
        nsdNames.clear()
        main.post { resolveQueue.clear(); resolving = false }
    }

    /** Forgets everything found so far and looks again (after a connection failed, the list may be out of date). */
    fun restart() {
        if (!running) return
        halt()
        main.post {
            if (!wanted) return@post
            onChanged(emptyList()) // what was listed is exactly what may be out of date
            if (!running) begin()
        }
    }

    // ---- mDNS -----------------------------------------------------------------------------------------

    private fun startNsd() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (!running) return@post
                    resolveQueue.addLast(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val key = nsdNames.remove(info.serviceName) ?: return
                entries.remove(key)
                publish()
            }
        }
        discoveryListener = l
        try {
            nsd.discoverServices(Proto.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
            discoveryListener = null
        }
    }

    private fun stopNsd() {
        val l = discoveryListener ?: return
        discoveryListener = null
        try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val done = {
            main.post {
                resolving = false
                resolveNext()
            }
        }
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    done()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    try {
                        val host = info.host
                        // Our listener is IPv4; ignore IPv6 / link-local answers (UDP probing covers those cases).
                        if (host is Inet4Address && info.port > 0) {
                            val shown = info.attributes["n"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                                ?: info.serviceName
                            val r = Receiver(shown, host.hostAddress ?: return, info.port)
                            val now = SystemClock.elapsedRealtime()
                            val existing = entries[r.key]
                            if (existing != null) {
                                existing.receiver = r
                                existing.viaNsd = true
                                existing.lastSeen = now
                            } else {
                                entries[r.key] = Entry(r, viaNsd = true, lastSeen = now, seenViaUdp = false)
                            }
                            nsdNames[info.serviceName] = r.key
                            publish()
                        }
                    } finally {
                        done()
                    }
                }
            })
        } catch (e: Exception) {
            done()
        }
    }

    // ---- UDP broadcast probing ----------------------------------------------------------------------

    private fun startProbing() {
        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 400
            }
        } catch (_: Exception) {
            return
        }
        probeSocket = socket
        probeThread = Thread({
            val probe = Proto.UDP_PROBE.toByteArray(Charsets.UTF_8)
            val buf = ByteArray(512)
            while (running && !socket.isClosed) {
                for (addr in DeviceInfo.broadcastAddresses()) {
                    try {
                        socket.send(DatagramPacket(probe, probe.size, addr, Proto.DEFAULT_PORT))
                    } catch (_: Exception) {
                    }
                }
                // Collect replies for ~1.5 s, then probe again.
                val until = SystemClock.elapsedRealtime() + 1500
                while (running && !socket.isClosed && SystemClock.elapsedRealtime() < until) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        handleReply(p)
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (socket.isClosed) return@Thread
                    }
                }
            }
        }, "ps-probe").apply { isDaemon = true; start() }
    }

    private fun handleReply(p: DatagramPacket) {
        val r = ReceiverLocator.parseReply(p.data, p.length, p.address) ?: return
        val now = SystemClock.elapsedRealtime()
        val existing = entries[r.key]
        if (existing != null) {
            // keep the NSD name if it has one, they are identical in practice
            existing.lastSeen = now
            existing.seenViaUdp = true
            existing.receiver = r
        } else {
            entries[r.key] = Entry(r, viaNsd = false, lastSeen = now, seenViaUdp = true)
        }
        publish()
    }

    // ---- housekeeping -------------------------------------------------------------------------------

    private val pruneTask = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            var changed = false
            val it = entries.entries.iterator()
            while (it.hasNext()) {
                val e = it.next().value
                // A receiver that answered UDP before but went quiet is gone (or became busy).
                // One only known through mDNS stays until mDNS says it's lost.
                val stale = now - e.lastSeen > UDP_STALE_MS
                if (stale && (e.seenViaUdp || !e.viaNsd)) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) publish()
            main.postDelayed(this, 1000)
        }
    }

    private fun publish() {
        val list = entries.values.map { it.receiver }.sortedBy { it.name.lowercase() }
        main.post { if (running) onChanged(list) }
    }

    companion object {
        private const val UDP_STALE_MS = 6000L
    }
}
