package com.phonestream.app.send

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import com.phonestream.app.core.Fit
import com.phonestream.app.core.StreamPlan
import com.phonestream.app.core.sanePts
import com.phonestream.app.media.Codecs

/** The screen capture itself (e.g. a revoked/unsupported MediaProjection), as opposed to a codec problem. */
class CaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The bitrate range an encoder was started with: the [Tuner] steers within it while the stream runs. */
class Rates(val ceilingBps: Int, val startBps: Int)

/**
 * MediaProjection -> VirtualDisplay -> [GlRelay] -> hardware encoder -> [PacketWriter].
 *
 * The VirtualDisplay is created ONCE (Android 14 forbids creating a second one from the same projection)
 * and is resized / re-pointed whenever the plan changes. If this phone can't do the GL detour ([canReshape]
 * is false) the VirtualDisplay feeds the encoder directly: no frame-rate limiter, no 16:9 crop / stretch.
 */
class ScreenStreamer(
    private val projection: MediaProjection,
    private val writer: PacketWriter,
    private val handler: Handler,
    private val onFatal: (String) -> Unit,
) {
    private val lifecycle = Any()
    private var display: VirtualDisplay? = null
    private var stopped = false

    private var relay: GlRelay? = null
    private var relayInput: Surface? = null

    @Volatile
    private var enc: Enc? = null

    @Volatile
    private var lastKeyRequest = 0L

    init {
        try {
            val r = GlRelay()
            relayInput = r.start()
            relay = r
        } catch (_: Throwable) {
            relay = null // direct path
        }
    }

    /** Can this phone give the picture another shape (Fill / Stretch 16:9) and limit its frame rate? */
    val canReshape: Boolean get() = relay != null

    /**
     * Starts (or restarts) encoding with [plan]. [beforeStart] runs after the encoder has been created
     * successfully but before it produces any output -- the place to announce the new format to the receiver.
     * Throws if this plan cannot be used; the caller then tries another one.
     */
    fun apply(plan: StreamPlan, dpi: Int, gen: Int, beforeStart: () -> Unit): Rates {
        synchronized(lifecycle) {
            if (stopped) throw CaptureException("Streaming already stopped")
            stopEncoderLocked()
            val r = relay
            if (r == null && plan.fit != Fit.SCALE) throw CaptureException("This phone can't reshape the picture")

            val encoder = Codecs.createEncoder(plan)
            val codec = encoder.codec
            val surface: Surface
            try {
                surface = codec.createInputSurface()
            } catch (e: Exception) {
                codec.release()
                throw e
            }

            try {
                if (r != null) {
                    r.setSourceSize(plan.srcWidth, plan.srcHeight)
                    showDisplay(plan.srcWidth, plan.srcHeight, dpi, relayInput!!, resizeOnly = true)
                    r.attach(surface, plan, plan.fps)
                } else {
                    showDisplay(plan.width, plan.height, dpi, surface, resizeOnly = false)
                }
                beforeStart()
                codec.start()
            } catch (e: Exception) {
                try { if (r != null) r.detach() else display?.surface = null } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
                try { surface.release() } catch (_: Exception) {}
                throw e
            }

            enc = Enc(plan, codec, surface, gen).also { it.start() }
            return Rates(encoder.ceilingBps, encoder.startBps)
        }
    }

    /** Creates the VirtualDisplay on first use, afterwards resizes it (and, in direct mode, re-points it). */
    private fun showDisplay(w: Int, h: Int, dpi: Int, surface: Surface, resizeOnly: Boolean) {
        val d = display
        if (d == null) {
            display = try {
                projection.createVirtualDisplay(
                    "PhoneStream", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, handler
                ) ?: throw CaptureException("The system refused to start screen capture")
            } catch (e: CaptureException) {
                throw e
            } catch (e: Exception) {
                throw CaptureException(e.message ?: "Screen capture failed", e)
            }
        } else {
            d.resize(w, h, dpi)
            if (!resizeOnly) d.surface = surface
        }
    }

    /** Asks the encoder for a key frame soon (rate limited so a congested link can't flood it). */
    fun requestKeyFrame() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyRequest < MIN_KEY_INTERVAL_MS) return
        lastKeyRequest = now
        val codec = enc?.codec ?: return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
            // encoder is being torn down; the replacement starts with a key frame anyway
        }
    }

    /** Changes the encoder's target bitrate while it runs. */
    fun setBitrate(bps: Int) {
        val codec = enc?.codec ?: return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        } catch (_: Exception) {
            // not every encoder accepts it, or it is being torn down: the stream just keeps its current rate
        }
    }

    /** Frame-rate limit while running (only effective when [canReshape]). */
    fun setFpsCap(fps: Int) {
        try { relay?.setFpsCap(fps) } catch (_: Exception) {}
    }

    fun stop() {
        synchronized(lifecycle) {
            stopped = true
            stopEncoderLocked()
            try { display?.release() } catch (_: Exception) {}
            display = null
            try { relay?.release() } catch (_: Exception) {}
            relay = null
            relayInput = null
        }
    }

    private fun stopEncoderLocked() {
        val e = enc ?: return
        enc = null
        // First stop feeding the encoder's surface, only then tear the encoder down.
        try {
            val r = relay
            if (r != null) r.detach() else display?.surface = null
        } catch (_: Exception) {
        }
        e.stop()
    }

    private inner class Enc(val plan: StreamPlan, val codec: MediaCodec, val surface: Surface, val gen: Int) {
        @Volatile
        private var running = true
        private val thread = Thread({ drain() }, "ps-encoder").apply { isDaemon = true }

        fun start() = thread.start()

        fun stop() {
            running = false
            try { thread.join(1500) } catch (_: InterruptedException) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { surface.release() } catch (_: Exception) {}
        }

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            var config: ByteArray? = null
            while (running) {
                val idx = try {
                    codec.dequeueOutputBuffer(info, 50_000)
                } catch (e: Exception) {
                    if (running) onFatal("Video encoder failed: ${e.message ?: e.javaClass.simpleName}")
                    return
                }
                if (idx < 0) continue // timeout / format change / buffers changed
                try {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val body = ByteArray(info.size)
                        buf.get(body)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            config = body
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val cfg = config
                            // Every key frame must carry SPS/PPS(/VPS) so the receiver can start decoding there.
                            val data = if (key && cfg != null && !body.startsWith(cfg)) cfg + body else body
                            writer.sendVideo(gen, key, sanePts(info.presentationTimeUs, System.nanoTime() / 1000), data)
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        onFatal("Video encoder failed: ${e.message ?: e.javaClass.simpleName}")
                        return
                    }
                } finally {
                    try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                }
            }
        }
    }

    companion object {
        private const val MIN_KEY_INTERVAL_MS = 150L

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            if (size < prefix.size) return false
            for (i in prefix.indices) if (this[i] != prefix[i]) return false
            return true
        }
    }
}
