package com.phonestream.app.receive

import com.phonestream.app.core.FrameAssembler
import com.phonestream.app.core.Msg
import com.phonestream.app.core.PacketStream
import com.phonestream.app.core.Proto
import com.phonestream.app.core.Query
import com.phonestream.app.media.Codecs
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * RECEIVER side TCP endpoint. Accepts one sender at a time (a second one gets a polite "busy"),
 * feeds the incoming video / audio to the players and answers capability queries.
 * All [Listener] callbacks come from background threads.
 */
class ReceiverServer(
    private val deviceName: String,
    private val video: VideoSink,
    private val audio: AudioSink,
    private val listener: Listener,
    private val port: Int = Proto.DEFAULT_PORT,
    /** Can this device decode what the sender is considering? (Injected so the server is testable on the JVM.) */
    private val canDecode: (Query) -> Boolean = { q -> Codecs.decoderSupports(q.mime, q.width, q.height, q.fps) },
    /** How often to retry when the port is taken, and how long to keep quiet about it before telling the user. */
    private val bindRetryMs: Long = BIND_RETRY_MS,
    private val reportAfterMs: Long = REPORT_AFTER_MS,
    /** How often the receiver checks that its own listening socket still answers. */
    private val watchdogMs: Long = WATCHDOG_MS,
) {
    interface Listener {
        fun onListening()
        fun onServerError(message: String)
        fun onSessionStarted(senderName: String)
        fun onVideoSize(width: Int, height: Int)
        fun onSessionEnded(reason: String?)
    }

    private inner class Session(val socket: Socket, val ps: PacketStream, val name: String) {
        @Volatile
        var alive = true

        @Volatile
        var userClosed = false

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var active: Session? = null

    @Volatile
    private var stopped = false

    /** Raised by every start() and stop(), so a listener thread of an earlier start() can tell it is out of date. */
    private var generation = 0

    /** Connections taken off the listening socket so far; the watchdog uses it to see that accept() is alive. */
    private val accepted = AtomicLong()

    /** The port actually bound (differs from the constructor argument only when that was 0). */
    @Volatile
    var localPort = port
        private set

    fun start() {
        val gen = synchronized(lock) {
            stopped = false
            ++generation
        }
        Thread({ serve(gen) }, "ps-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            generation++
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
        }
        disconnect()
    }

    private fun isCurrent(gen: Int) = synchronized(lock) { !stopped && generation == gen }

    /** Sleeps; false if the thread was interrupted (treated as "give up"). */
    private fun pause(ms: Long): Boolean = try { Thread.sleep(ms); true } catch (_: InterruptedException) { false }

    private fun bindSocket(): ServerSocket {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.receiveBufferSize = 1 shl 20
            ss.bind(InetSocketAddress(port))
            return ss
        } catch (e: Exception) {
            try { ss.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Listens until stop(). A port that is still taken (the previous copy of the app is still dying, e.g. right
     * after an update) is retried instead of given up on, and a socket that stops accepting is replaced by a fresh
     * one, so the receiver recovers by itself instead of waiting for the device to be restarted.
     */
    private fun serve(gen: Int) {
        val startedAt = System.nanoTime()
        var reported = false
        while (isCurrent(gen)) {
            val ss = try {
                bindSocket()
            } catch (e: Exception) {
                if (!reported && (System.nanoTime() - startedAt) / 1_000_000 >= reportAfterMs) {
                    reported = true
                    listener.onServerError("Can't listen on port $port (${e.message ?: "unknown error"}). Is another copy of PhoneStream running? It keeps trying; if this stays, restart the device.")
                }
                if (!pause(bindRetryMs)) return
                continue
            }
            val installed = synchronized(lock) {
                if (stopped || generation != gen) {
                    false
                } else {
                    serverSocket = ss
                    localPort = ss.localPort
                    true
                }
            }
            if (!installed) {
                try { ss.close() } catch (_: Exception) {}
                return
            }
            watch(gen, ss)
            listener.onListening()

            var failures = 0
            while (isCurrent(gen) && !ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (!isCurrent(gen) || ss.isClosed) break
                    // Failing again and again (out of file descriptors, a socket the system broke): don't spin on it.
                    if (++failures >= ACCEPT_FAILURES_BEFORE_REBIND) break
                    if (!pause(ACCEPT_BACKOFF_MS)) return
                    continue
                }
                failures = 0
                accepted.incrementAndGet()
                Thread({ handle(s) }, "ps-recv-conn").apply { isDaemon = true; start() }
            }
            synchronized(lock) { if (serverSocket === ss) serverSocket = null }
            try { ss.close() } catch (_: Exception) {}
            if (isCurrent(gen) && !pause(bindRetryMs)) return
        }
    }

    /**
     * Every [watchdogMs] the receiver knocks on its own door. If nobody opens (the socket is gone without Java having
     * noticed, or the thread that accepts is stuck), the listener is replaced by a fresh one on a new thread. That is
     * what the user would otherwise have to do by restarting the app or the TV.
     */
    private fun watch(gen: Int, ss: ServerSocket) {
        Thread({
            while (pause(watchdogMs)) {
                if (!isCurrent(gen) || ss.isClosed) return@Thread
                if (!answers(ss.localPort)) {
                    replaceListener(gen)
                    return@Thread
                }
            }
        }, "ps-watchdog").apply { isDaemon = true; start() }
    }

    private fun answers(port: Int): Boolean {
        val before = accepted.get()
        try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), PROBE_CONNECT_MS) }
        } catch (_: Exception) {
            return false
        }
        val deadline = System.nanoTime() + PROBE_ACCEPT_MS * 1_000_000
        while (accepted.get() == before) {
            if (System.nanoTime() > deadline) return false
            if (!pause(10)) return true
        }
        return true
    }

    private fun replaceListener(gen: Int) {
        val next = synchronized(lock) {
            if (stopped || generation != gen) return
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            ++generation
        }
        Thread({ serve(next) }, "ps-accept").apply { isDaemon = true; start() }
    }

    /** The user on the receiving device ended the session. */
    fun disconnect() {
        val s = synchronized(lock) { active } ?: return
        s.userClosed = true
        Thread({
            try { s.ps.write(Proto.T_BYE, Msg.bye("The receiver disconnected")) } catch (_: Exception) {}
            s.close()
        }, "ps-recv-disconnect").start()
    }

    /** The receiver can't continue (e.g. decoder failure): tell the sender why and hang up. */
    fun abort(reason: String) {
        val s = synchronized(lock) { active } ?: return
        s.userClosed = true
        Thread({
            try { s.ps.write(Proto.T_BYE, Msg.bye(reason)) } catch (_: Exception) {}
            s.close()
        }, "ps-recv-abort").start()
    }

    fun requestKeyFrame() {
        val s = synchronized(lock) { active } ?: return
        try {
            s.ps.write(Proto.T_REQUEST_KEYFRAME)
        } catch (_: IOException) {
            s.close()
        }
    }

    // ---- one connection ------------------------------------------------------------------------------

    private fun handle(socket: Socket) {
        val ps: PacketStream
        val hello: com.phonestream.app.core.Hello
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 3000 // a real sender says HELLO immediately
            ps = PacketStream(socket.getInputStream(), socket.getOutputStream())
            val p = ps.read()
            if (p.type != Proto.T_HELLO) throw IOException("no hello")
            hello = Msg.parseHello(p.payload)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            return
        }

        val session: Session?
        var refusal = ""
        synchronized(lock) {
            if (hello.version != Proto.VERSION) {
                refusal = "The phone runs PhoneStream protocol ${hello.version}, this device ${Proto.VERSION}. Update the app on the older device."
                session = null
            } else if (stopped) {
                refusal = "Receiver is closing"
                session = null
            } else if (active != null) {
                refusal = "Busy: already receiving from ${active?.name ?: ""}"
                session = null
            } else {
                session = Session(socket, ps, hello.name)
                active = session
            }
        }

        if (session == null) {
            try {
                ps.write(Proto.T_HELLO_ACK, Msg.helloAck(false, refusal, deviceName))
            } catch (_: Exception) {
            }
            try { socket.close() } catch (_: Exception) {}
            return
        }

        var reason: String? = null
        val assembler = FrameAssembler()
        try {
            ps.write(Proto.T_HELLO_ACK, Msg.helloAck(true, "", deviceName))
            socket.soTimeout = 5000 // heartbeats arrive every second
            listener.onSessionStarted(hello.name)
            startHeartbeat(session)

            loop@ while (true) {
                val p = ps.read()
                when (p.type) {
                    Proto.T_VIDEO_CONFIG -> {
                        val cfg = Msg.parseVideoConfig(p.payload)
                        assembler.reset() // a half-received frame belongs to the old format
                        video.configure(cfg)
                        listener.onVideoSize(cfg.width, cfg.height)
                    }
                    Proto.T_VIDEO_PART -> assembler.add(p.payload)?.let(video::feed)
                    Proto.T_VIDEO_FRAME -> video.feed(Msg.parseVideoFrame(p.payload))
                    Proto.T_AUDIO_CONFIG -> audio.start(Msg.parseAudioConfig(p.payload))
                    Proto.T_AUDIO_DATA -> audio.feed(Msg.parseAudioData(p.payload))
                    Proto.T_QUERY -> {
                        val q = Msg.parseQuery(p.payload)
                        val ok = try { canDecode(q) } catch (_: Exception) { false }
                        ps.write(Proto.T_QUERY_REPLY, Msg.queryReply(q.id, ok))
                    }
                    Proto.T_BYE -> {
                        reason = Msg.parseBye(p.payload).ifEmpty { null }
                        break@loop
                    }
                    // T_HEARTBEAT: nothing to do
                }
            }
        } catch (e: SocketTimeoutException) {
            if (!session.userClosed) reason = "The sender stopped responding"
        } catch (e: Exception) {
            if (!session.userClosed) reason = "Connection to the sender was lost"
        } finally {
            session.alive = false
            // Whatever a player does on the way out, the receiver must become free again: a failure here that
            // skipped the lines below would leave it "busy" for every later sender.
            try { video.configure(null) } catch (_: Exception) {}
            try { audio.stop() } catch (_: Exception) {}
            session.close()
            synchronized(lock) { if (active === session) active = null }
            listener.onSessionEnded(reason)
        }
    }

    private fun startHeartbeat(session: Session) {
        Thread({
            try {
                while (session.alive) {
                    Thread.sleep(1000)
                    if (!session.alive) break
                    session.ps.write(Proto.T_HEARTBEAT)
                    // how the decoder is coping: the sender lowers the frame rate / bitrate if it is not
                    video.takeStats()?.let { session.ps.write(Proto.T_STATS, Msg.stats(it)) }
                }
            } catch (_: Exception) {
                session.close()
            }
        }, "ps-recv-heartbeat").apply { isDaemon = true; start() }
    }

    private companion object {
        const val BIND_RETRY_MS = 1000L
        const val REPORT_AFTER_MS = 5000L
        const val ACCEPT_BACKOFF_MS = 200L
        const val ACCEPT_FAILURES_BEFORE_REBIND = 5
        const val WATCHDOG_MS = 4000L
        const val PROBE_CONNECT_MS = 1000
        const val PROBE_ACCEPT_MS = 1500L
    }
}
