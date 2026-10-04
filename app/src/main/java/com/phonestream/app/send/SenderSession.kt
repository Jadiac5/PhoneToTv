package com.phonestream.app.send

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import com.phonestream.app.Prefs
import com.phonestream.app.core.AspectMode
import com.phonestream.app.core.AudioConfig
import com.phonestream.app.core.Fit
import com.phonestream.app.core.MuteGuard
import com.phonestream.app.core.Msg
import com.phonestream.app.core.PacketStream
import com.phonestream.app.core.Planner
import com.phonestream.app.core.Preset
import com.phonestream.app.core.Proto
import com.phonestream.app.core.Query
import com.phonestream.app.core.StreamPlan
import com.phonestream.app.core.Tuner
import com.phonestream.app.core.VideoConfig
import com.phonestream.app.media.Codecs
import com.phonestream.app.net.DeviceInfo
import com.phonestream.app.net.Receiver
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One streaming session to one receiver: connect, handshake, negotiate what both devices can do,
 * then capture + encode + send until somebody ends it.
 *
 * Threads: a HandlerThread owns all the mutable state (planning, reconfiguration, the 500 ms tick);
 * a reader thread handles incoming packets; the writer / encoder / audio threads live in their classes.
 * [end] may be called from any thread, any number of times.
 */
class SenderSession(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val target: Receiver,
    private var preset: Preset,
    private var aspect: AspectMode,
    private val wantAudio: Boolean,
    private var muteWanted: Boolean,
    private val onEnded: (reason: String?) -> Unit,
) {
    private class Pending {
        val latch = CountDownLatch(1)

        @Volatile
        var ok = false
    }

    private class Metrics(val w: Int, val h: Int, val dpi: Int)

    private val thread = HandlerThread("ps-session").also { it.start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val startLock = Any()

    @Volatile
    private var beginDone = false

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: PacketWriter? = null

    @Volatile
    private var streamer: ScreenStreamer? = null

    private var audio: AudioStreamer? = null
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val capabilityCache = HashMap<String, Boolean>()
    private var nextQueryId = 1
    private var lastNative: Pair<Int, Int>? = null
    private var displayListenerRegistered = false

    // adaptive rate control
    private var tuner: Tuner? = null
    private var appliedBps = 0
    private var appliedFps = 0

    // phone speaker
    private var phoneMute: PhoneMute? = null

    /** Fed by the audio thread, replaced on the session thread. */
    @Volatile
    private var guard: MuteGuard? = null

    // stats
    private var lastStatsAt = 0L
    private var lastBytes = 0L
    private var lastDropped = 0
    private var lastTickDropped = 0
    private val recentDrops = IntArray(4)
    private var tickCount = 0

    fun start() {
        handler.post { begin() }
    }

    fun setPreset(p: Preset) {
        handler.post {
            if (closed.get() || writer == null || p == preset) return@post
            preset = p
            StreamState.update { it.copy(preset = p) }
            applyPlan()
        }
    }

    fun setAspect(a: AspectMode) {
        handler.post {
            if (closed.get() || writer == null || a == aspect) return@post
            val old = aspect
            aspect = a
            StreamState.update { it.copy(aspect = a) }
            val m = nativeMetrics()
            // A restart is a brief hiccup: only do it when the picture's shape really changes.
            if (streamer?.canReshape == true && (Planner.reshapes(m.w, m.h, old) || Planner.reshapes(m.w, m.h, a))) applyPlan()
        }
    }

    /** Switches the phone's own speaker off / on while streaming. */
    fun setPhoneMute(on: Boolean) {
        handler.post {
            if (closed.get() || writer == null || on == muteWanted) return@post
            muteWanted = on
            StreamState.update { it.copy(muteWanted = on, muteNote = "") }
            if (on) {
                if (Prefs.muteSupport(ctx) == 2) Prefs.setMuteSupport(ctx, 0) // asked again: test again
                startMuting()
            } else stopMuting()
        }
    }

    /** Ends the session. [reason] is shown to the user (null = they stopped it themselves). */
    fun end(reason: String?) {
        if (!closed.compareAndSet(false, true)) return
        Thread({ teardown(reason) }, "ps-teardown").start()
    }

    // ---- start-up -----------------------------------------------------------------------------------

    private fun begin() {
        // Held for the whole start-up so teardown() can never run in the middle of it and miss the
        // encoder / audio capture that start-up is just about to create.
        synchronized(startLock) {
            try {
                StreamState.update {
                    it.copy(
                        phase = Phase.CONNECTING, receiverName = target.name, error = null, audioOn = false,
                        audioNote = "", status = "Connecting to ${target.name}…", preset = preset, dropped = 0,
                        mbps = 0f, congested = false, aspect = aspect, reshaped = false, aspectNote = "",
                        phoneMuted = false, muteWanted = muteWanted, muteNote = "",
                    )
                }
                val ps = connect() ?: return
                val w = PacketWriter(ps, { e -> end("Connection lost (${e.message ?: "network error"})") }, { streamer?.requestKeyFrame() })
                writer = w
                streamer = ScreenStreamer(projection, w, handler) { msg -> end(msg) }
                w.start()
                startReader(ps)

                if (!applyPlan()) return
                startAudio()
                registerDisplayListener()

                lastStatsAt = SystemClock.elapsedRealtime()
                handler.postDelayed(tick, TICK_MS)
                if (!closed.get()) {
                    StreamState.update { it.copy(phase = Phase.STREAMING, status = "Streaming to ${target.name}") }
                }
            } catch (e: Exception) {
                end(describe(e))
            } finally {
                beginDone = true
            }
        }
    }

    private fun connect(): PacketStream? {
        val s = Socket()
        socket = s
        if (closed.get()) {
            s.close()
            return null
        }
        s.tcpNoDelay = true
        s.keepAlive = true
        // Small on purpose: whatever the kernel buffers is delay we can't take back. The PacketWriter does the queueing.
        s.sendBufferSize = SEND_BUFFER
        s.connect(InetSocketAddress(target.host, target.port), 5000)
        s.soTimeout = 5000 // from now on: no packet (not even a heartbeat) for 5 s = receiver is gone

        val ps = PacketStream(s.getInputStream(), s.getOutputStream())
        ps.write(Proto.T_HELLO, Msg.hello(DeviceInfo.deviceName(ctx)))
        val reply = try {
            ps.read()
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (e: IOException) {
            if (closed.get()) throw e
            // PhoneStream 1.0 receivers simply hang up on a newer sender.
            throw IOException("${target.name} hung up. If it runs an older PhoneStream, update the app on it too: both devices need the same version.")
        }
        if (reply.type != Proto.T_HELLO_ACK) throw IOException("The device answered, but it is not a PhoneStream receiver")
        val ack = Msg.parseHelloAck(reply.payload)
        if (!ack.accepted) throw IOException(ack.reason.ifEmpty { "The receiver refused the connection" })
        if (ack.version < Proto.VERSION) {
            throw IOException("${target.name} runs an older PhoneStream. Update the app on it too: both devices need the same version.")
        }
        return ps
    }

    private fun startReader(ps: PacketStream) {
        Thread({
            try {
                while (!closed.get()) {
                    val p = ps.read()
                    when (p.type) {
                        Proto.T_REQUEST_KEYFRAME -> streamer?.requestKeyFrame()
                        Proto.T_QUERY_REPLY -> {
                            val (id, ok) = Msg.parseQueryReply(p.payload)
                            pending[id]?.let {
                                it.ok = ok
                                it.latch.countDown()
                            }
                        }
                        Proto.T_STATS -> {
                            val stats = Msg.parseStats(p.payload)
                            handler.post {
                                tuner?.onRemote(SystemClock.elapsedRealtime(), stats)
                                pushTuner()
                            }
                        }
                        Proto.T_BYE -> {
                            end(Msg.parseBye(p.payload).ifEmpty { "The receiver ended the session" })
                            return@Thread
                        }
                        // T_HEARTBEAT: nothing to do, the read itself resets the timeout
                    }
                }
            } catch (e: SocketTimeoutException) {
                end("The receiver stopped responding")
            } catch (e: IOException) {
                end("Connection to the receiver was lost")
            } catch (e: Exception) {
                end(describe(e))
            }
        }, "ps-reader").apply { isDaemon = true; start() }
    }

    // ---- negotiation --------------------------------------------------------------------------------

    private fun nativeMetrics(): Metrics {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return Metrics(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    /** Asks the receiver whether ITS decoder can play this. Cached; an unresponsive receiver counts as "yes". */
    private fun remoteSupports(mime: String, w: Int, h: Int, fps: Int): Boolean {
        val key = "$mime/$w/$h/$fps"
        capabilityCache[key]?.let { return it }
        val wr = writer ?: return false
        val id = nextQueryId++
        val p = Pending()
        pending[id] = p
        wr.sendControl(Proto.T_QUERY, Msg.query(Query(id, mime, w, h, fps)))
        val answered = try {
            p.latch.await(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        pending.remove(id)
        if (closed.get()) return false
        if (!answered) return true
        capabilityCache[key] = p.ok
        return p.ok
    }

    /**
     * Picks the best format for the current preset / aspect mode / screen size that both ends can handle and
     * (re)starts the encoder with it. Returns false if the session had to be ended.
     */
    private fun applyPlan(): Boolean {
        val wr = writer ?: return false
        val st = streamer ?: return false
        val m = nativeMetrics()
        lastNative = m.w to m.h
        val rejected = HashSet<String>()
        // Without the GL detour this phone can only show the screen as it is.
        val effectiveAspect = if (st.canReshape) aspect else AspectMode.ORIGINAL
        val aspectNote =
            if (!st.canReshape && Planner.reshapes(m.w, m.h, aspect)) "This phone can't reshape the picture, so it is sent as it is." else ""

        repeat(8) {
            if (closed.get()) return false
            val plan: StreamPlan = Planner.plan(m.w, m.h, preset, effectiveAspect) { mime, w, h, fps ->
                "$mime/$w/$h/$fps" !in rejected &&
                    Codecs.encoderSupports(mime, w, h, fps) &&
                    remoteSupports(mime, w, h, fps)
            } ?: run {
                end("No video format works on both devices. Try a lower resolution.")
                return false
            }

            val gen = wr.newGeneration()
            try {
                val rates = st.apply(plan, m.dpi, gen) {
                    wr.sendControl(Proto.T_VIDEO_CONFIG, Msg.videoConfig(VideoConfig(plan.mime, plan.width, plan.height, plan.fps)))
                }
                val t = Tuner(rates.ceilingBps, Planner.floorBitrate(rates.ceilingBps), plan.fps, rates.startBps)
                tuner = t
                appliedBps = t.bitrate
                appliedFps = t.fps
                st.setFpsCap(t.fps)
                StreamState.update {
                    it.copy(
                        width = plan.width, height = plan.height, fps = plan.fps, codec = plan.codecLabel,
                        nativeW = m.w, nativeH = m.h, preset = preset, aspect = aspect,
                        reshaped = plan.fit != Fit.SCALE, aspectNote = aspectNote,
                    )
                }
                return true
            } catch (e: CaptureException) {
                end("Screen capture failed: ${e.message}")
                return false
            } catch (e: Exception) {
                // This encoder config was rejected at configure/start time; try the next best.
                rejected += "${plan.mime}/${plan.width}/${plan.height}/${plan.fps}"
            }
        }
        end("The video encoder rejected every format. Try a lower resolution.")
        return false
    }

    /** Hands the tuner's current decisions to the encoder / frame limiter (only what changed). */
    private fun pushTuner() {
        val t = tuner ?: return
        if (t.bitrate != appliedBps) {
            appliedBps = t.bitrate
            streamer?.setBitrate(appliedBps)
        }
        if (t.fps != appliedFps) {
            appliedFps = t.fps
            streamer?.setFpsCap(appliedFps)
        }
    }

    private fun startAudio() {
        val wr = writer ?: return
        if (!wantAudio) {
            StreamState.update { it.copy(audioOn = false, audioNote = "Sound is turned off") }
            return
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            StreamState.update { it.copy(audioOn = false, audioNote = "No sound: microphone permission was not granted") }
            return
        }
        val a = AudioStreamer(projection, wr, { msg ->
            StreamState.update { it.copy(audioOn = false, audioNote = msg) }
            handler.post { stopMuting() } // the TV is no longer getting sound: give the phone its own back
        }, { peak -> guard?.onChunk(peak) })
        val err = a.prepare()
        if (err != null) {
            StreamState.update { it.copy(audioOn = false, audioNote = "No sound: $err") }
            return
        }
        wr.sendControl(Proto.T_AUDIO_CONFIG, Msg.audioConfig(AudioConfig(Proto.AUDIO_RATE, Proto.AUDIO_CHANNELS)))
        val err2 = a.begin()
        if (err2 != null) {
            a.stop()
            StreamState.update { it.copy(audioOn = false, audioNote = "No sound: $err2") }
            return
        }
        audio = a
        StreamState.update { it.copy(audioOn = true, audioNote = "") }
        if (muteWanted) startMuting()
    }

    // ---- the phone's own speaker ----------------------------------------------------------------------

    /** Only ever while the TV really receives sound, otherwise the phone would be silenced for nothing. */
    private fun startMuting() {
        if (audio == null || closed.get() || guard != null) return
        if (Prefs.muteSupport(ctx) == 2) {
            StreamState.update { it.copy(phoneMuted = false, muteNote = MUTE_UNSUPPORTED) }
            return
        }
        val pm = phoneMute ?: PhoneMute(ctx, handler).also { phoneMute = it }
        val g = MuteGuard(
            setMuted = { on ->
                pm.setMuted(on)
                StreamState.update { it.copy(phoneMuted = pm.isMuted) }
            },
            onVerdict = { works ->
                Prefs.setMuteSupport(ctx, if (works) 1 else 2)
                if (!works) StreamState.update { it.copy(phoneMuted = false, muteNote = MUTE_UNSUPPORTED) }
            },
        )
        if (Prefs.muteSupport(ctx) == 1) g.assumeWorks() // already proven on this phone
        guard = g
    }

    private fun stopMuting() {
        guard = null
        phoneMute?.setMuted(false)
        StreamState.update { it.copy(phoneMuted = false) }
    }

    // ---- rotation / fold / resolution changes -----------------------------------------------------

    private val rescan = Runnable {
        if (closed.get() || writer == null) return@Runnable
        val m = nativeMetrics()
        if (lastNative != (m.w to m.h)) applyPlan()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            handler.removeCallbacks(rescan)
            handler.postDelayed(rescan, 400) // rotation fires several events; wait for it to settle
        }
    }

    private fun registerDisplayListener() {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, handler)
        displayListenerRegistered = true
    }

    // ---- 500 ms tick: rate control; every second also a heartbeat + the numbers for the screen ------------

    private val tick = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val wr = writer
            if (wr != null) {
                val now = SystemClock.elapsedRealtime()
                tickCount++

                val dropped = wr.videoDropped.get()
                tuner?.onTick(now, wr.takeLatencyMax(), dropped - lastTickDropped)
                lastTickDropped = dropped
                pushTuner()

                if (tickCount % 2 == 0) {
                    wr.sendControl(Proto.T_HEARTBEAT)
                    val dt = (now - lastStatsAt).coerceAtLeast(1)
                    val sent = wr.bytesSent.get()
                    val mbps = (sent - lastBytes) * 8f / 1000f / dt // bits / ms / 1000 = Mbit/s
                    recentDrops[(tickCount / 2) % recentDrops.size] = dropped - lastDropped
                    val congested = recentDrops.sum() >= 3
                    lastStatsAt = now
                    lastBytes = sent
                    lastDropped = dropped
                    StreamState.update { it.copy(mbps = mbps, dropped = dropped, congested = congested) }
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ---- shutdown -----------------------------------------------------------------------------------

    private fun teardown(reason: String?) {
        // Unblock a start-up that is still waiting on the network (connect, handshake, capability queries)...
        for (p in pending.values) p.latch.countDown()
        if (!beginDone) {
            try { socket?.close() } catch (_: Exception) {}
        }
        // ...then wait for it to finish (it notices `closed` and returns quickly) before cleaning up after it.
        synchronized(startLock) {
            handler.removeCallbacksAndMessages(null)
            if (displayListenerRegistered) {
                try {
                    (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
                } catch (_: Exception) {
                }
            }
            for (p in pending.values) p.latch.countDown()
            writer?.close(Msg.bye(reason ?: "Sender stopped"), 300)
            guard = null
            try { audio?.stop() } catch (_: Exception) {}
            try { phoneMute?.close() } catch (_: Exception) {} // the phone gets its volume back
            try { streamer?.stop() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
        onEnded(reason)
    }

    private fun describe(e: Exception): String = when (e) {
        is ConnectException -> "Can't reach ${target.name}. Is PhoneStream open on it in Receive mode?"
        is SocketTimeoutException -> "${target.name} did not answer"
        is NoRouteToHostException, is UnknownHostException -> "Can't find ${target.host} on the network"
        is IOException -> e.message ?: "Network error"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val TICK_MS = 500L
        const val SEND_BUFFER = 128 * 1024
        const val MUTE_UNSUPPORTED =
            "This phone can't be silenced without silencing the TV too (its sound capture follows the volume), so the phone stays audible."
    }
}
