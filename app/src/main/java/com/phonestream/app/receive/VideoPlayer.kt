package com.phonestream.app.receive

import android.media.MediaCodec
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.phonestream.app.core.Stats
import com.phonestream.app.core.VideoConfig
import com.phonestream.app.core.VideoFrame
import com.phonestream.app.media.Codecs
import kotlin.math.min

/**
 * Low-latency H.264 / H.265 playback straight onto the screen's Surface.
 *
 * Three threads, so that nothing on the TV can stall the network (which also carries the sound):
 *  - the network thread only drops frames into a queue ([feed], never blocks);
 *  - an input thread hands them to the decoder (waits for a free input buffer instead of dropping the frame,
 *    because every dropped frame means a visible glitch until the next key frame);
 *  - an output thread shows what comes out. If several frames are ready at once only the newest is put on screen
 *    (the others were decoded, so later frames still decode correctly, but nobody waits to see them).
 *
 * Only a hopeless backlog (the decoder really can't keep up) is thrown away: playback jumps to the newest key
 * frame, or asks the sender for one. The sender is told ([takeStats]) and lowers the frame rate.
 */
class VideoPlayer(
    private val requestKeyFrame: () -> Unit,
    private val onFatal: (String) -> Unit,
) : VideoSink {
    private class Decoder(val codec: MediaCodec) {
        @Volatile
        var alive = true
        var inThread: Thread? = null
        var outThread: Thread? = null
    }

    private val lock = Any() // decoder lifecycle
    private var surface: Surface? = null
    private var config: VideoConfig? = null
    private var decoder: Decoder? = null
    private val failures = ArrayDeque<Long>()

    private val qLock = java.lang.Object() // the frame queue
    private val queue = ArrayDeque<VideoFrame>()

    @Volatile
    private var waitingKey = true

    @Volatile
    private var lastKeyRequest = 0L

    // stats, reported once a second
    private var minQueue = Int.MAX_VALUE
    private var skipped = 0
    private var shownAtReport = 0L
    private var lastReport = SystemClock.elapsedRealtime()

    @Volatile
    var framesShown = 0L
        private set

    /** The SurfaceView's surface appeared (non-null) or went away (null: app in background, rotation...). */
    fun setSurface(s: Surface?) {
        synchronized(lock) {
            surface = s
            rebuildLocked()
        }
    }

    /** A new video format announced by the sender (null = no stream). */
    override fun configure(cfg: VideoConfig?) {
        synchronized(lock) {
            config = cfg
            failures.clear()
            rebuildLocked()
        }
    }

    fun release() = configure(null)

    override fun feed(f: VideoFrame) {
        synchronized(qLock) {
            if (decoder == null) return
            queue.addLast(f)
            qLock.notifyAll()
        }
    }

    override fun takeStats(): Stats? {
        synchronized(qLock) {
            if (decoder == null) return null
            val now = SystemClock.elapsedRealtime()
            val shown = framesShown
            val secs = (now - lastReport).coerceAtLeast(1) / 1000.0
            val s = Stats(
                ((shown - shownAtReport) / secs).toInt(),
                if (minQueue == Int.MAX_VALUE) 0 else minQueue,
                skipped,
            )
            lastReport = now
            shownAtReport = shown
            minQueue = Int.MAX_VALUE
            skipped = 0
            return s
        }
    }

    private fun askForKeyFrame() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyRequest < KEY_REQUEST_INTERVAL_MS) return
        lastKeyRequest = now
        requestKeyFrame()
    }

    // ---- decoder lifecycle (always with [lock] held) -------------------------------------------------

    private fun rebuildLocked() {
        releaseDecoderLocked()
        synchronized(qLock) {
            queue.clear()
            minQueue = Int.MAX_VALUE
            skipped = 0
        }
        val s = surface
        val c = config
        if (s == null || !s.isValid || c == null) return
        try {
            val codec = Codecs.createDecoder(c.mime, c.width, c.height, s)
            val d = Decoder(codec)
            waitingKey = true
            lastKeyRequest = 0
            synchronized(qLock) { decoder = d }
            d.inThread = Thread({ inputLoop(d) }, "ps-vdec-in").apply { isDaemon = true; start() }
            d.outThread = Thread({ outputLoop(d) }, "ps-vdec-out").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            synchronized(qLock) { decoder = null }
            onFatal("This device cannot play ${c.mime.substringAfter('/')} ${c.width}×${c.height}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun releaseDecoderLocked() {
        val d = synchronized(qLock) {
            val cur = decoder
            decoder = null
            cur?.alive = false
            qLock.notifyAll()
            cur
        } ?: return
        // (decoderFailed() runs on one of these threads itself: it must not wait for its own death)
        for (t in arrayOf(d.inThread, d.outThread)) {
            if (t != null && t !== Thread.currentThread()) {
                try { t.join(300) } catch (_: InterruptedException) {}
            }
        }
        try { d.codec.stop() } catch (_: Exception) {}
        try { d.codec.release() } catch (_: Exception) {}
    }

    // ---- threads -------------------------------------------------------------------------------------

    /** Next frame to decode, or null when [d] is gone. Also throws away a hopeless backlog. */
    private fun takeFrame(d: Decoder): VideoFrame? {
        var askKey = false
        val f = synchronized(qLock) {
            while (d.alive && queue.isEmpty()) qLock.wait(200)
            if (!d.alive) return null
            if (queue.size > CATCH_UP_DEPTH) {
                val newestKey = queue.indexOfLast { it.key }
                if (newestKey > 0) {
                    repeat(newestKey) { queue.removeFirst() }
                    skipped += newestKey
                } else if (queue.size > MAX_QUEUE) {
                    skipped += queue.size
                    queue.clear()
                    waitingKey = true
                    askKey = true
                }
            }
            if (queue.isEmpty()) null
            else {
                minQueue = min(minQueue, queue.size - 1)
                queue.removeFirst()
            }
        }
        if (askKey) askForKeyFrame()
        return f
    }

    private fun inputLoop(d: Decoder) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        try {
            while (d.alive) {
                val f = takeFrame(d) ?: continue
                if (waitingKey && !f.key) { // joined mid-stream: nothing to decode until a key frame
                    askForKeyFrame()
                    continue
                }
                var idx = -1
                while (d.alive && idx < 0) idx = d.codec.dequeueInputBuffer(INPUT_WAIT_US)
                if (idx < 0) return
                val buf = d.codec.getInputBuffer(idx)
                if (buf == null || f.length > buf.capacity()) {
                    d.codec.queueInputBuffer(idx, 0, 0, 0, 0)
                    waitingKey = true
                    askForKeyFrame()
                    continue
                }
                buf.clear()
                buf.put(f.data, f.offset, f.length)
                d.codec.queueInputBuffer(idx, 0, f.length, f.ptsUs, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                if (f.key) waitingKey = false
            }
        } catch (e: Exception) {
            if (d.alive) decoderFailed(d, e)
        }
    }

    private fun outputLoop(d: Decoder) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        val info = MediaCodec.BufferInfo()
        var pending = -1 // a decoded frame we hold back, in case a newer one is already waiting
        try {
            while (d.alive) {
                val idx = d.codec.dequeueOutputBuffer(info, if (pending >= 0) 0 else 10_000)
                if (idx >= 0) {
                    if (pending >= 0) d.codec.releaseOutputBuffer(pending, false) // superseded: don't show
                    pending = idx
                } else if (pending >= 0) {
                    d.codec.releaseOutputBuffer(pending, true) // render immediately
                    pending = -1
                    framesShown++
                }
            }
        } catch (e: Exception) {
            if (d.alive) decoderFailed(d, e)
        }
    }

    private fun decoderFailed(d: Decoder, e: Exception) {
        var fatal: String? = null
        synchronized(lock) {
            if (decoder !== d) return
            val now = SystemClock.elapsedRealtime()
            failures.addLast(now)
            while (failures.isNotEmpty() && now - failures.first() > 10_000) failures.removeFirst()
            if (failures.size > 3) {
                fatal = "The video decoder keeps failing: ${e.message ?: e.javaClass.simpleName}"
                releaseDecoderLocked()
            } else {
                rebuildLocked()
            }
        }
        fatal?.let(onFatal)
    }

    companion object {
        private const val INPUT_WAIT_US = 20_000L
        private const val KEY_REQUEST_INTERVAL_MS = 500L

        /** More frames than this waiting (~130 ms at 60 fps): skip ahead to the newest key frame if there is one. */
        private const val CATCH_UP_DEPTH = 8

        /** More than this (~400 ms) and no key frame to jump to: give up on the backlog and ask for a key frame. */
        private const val MAX_QUEUE = 24
    }
}
