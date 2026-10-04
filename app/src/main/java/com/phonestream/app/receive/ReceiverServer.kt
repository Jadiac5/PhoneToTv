package com.phonestream.app.receive

import com.phonestream.app.core.FrameAssembler
import com.phonestream.app.core.Msg
import com.phonestream.app.core.PacketStream
import com.phonestream.app.core.Proto
import com.phonestream.app.core.Query
import com.phonestream.app.media.Codecs
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

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

    /** The port actually bound (differs from the constructor argument only when that was 0). */
    @Volatile
    var localPort = port
        private set

    fun start() {
        stopped = false
        Thread({
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = 1 shl 20
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                listener.onServerError("Can't listen on port $port (${e.message ?: "unknown error"}). Is another copy of PhoneStream running?")
                return@Thread
            }
            synchronized(lock) {
                if (stopped) {
                    try { ss.close() } catch (_: Exception) {}
                    return@Thread
                }
                serverSocket = ss
                localPort = ss.localPort
            }
            listener.onListening()
            while (!stopped && !ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (stopped || ss.isClosed) break
                    continue
                }
                Thread({ handle(s) }, "ps-recv-conn").apply { isDaemon = true; start() }
            }
        }, "ps-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        synchronized(lock) {
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
        }
        disconnect()
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
            video.configure(null)
            audio.stop()
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
}
