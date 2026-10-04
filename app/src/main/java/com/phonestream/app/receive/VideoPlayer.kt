package com.phonestream.app.receive

import android.media.MediaCodec
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.phonestream.app.core.Pacing
import com.phonestream.app.core.PlayoutClock
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
 *  - an input thread hands them to the decoder shortly before they are due (waits for a free input buffer instead
 *    of dropping the frame, because every dropped frame means a visible glitch until the next key frame);
 *  - an output thread puts what comes out on the screen at its due time.
 *
 * "Due" comes from the [PlayoutClock]: the frame's capture time on the phone plus the delay the sound has on its
 * way to the speaker. So the picture plays at the pace it was captured however unevenly the network delivered it
 * (a late frame no longer makes the picture hang and then race to catch up) and it stays in step with the sound.
 * A frame that comes out of the decoder long after its due time (the TV was stalled) is not rushed onto the
 * screen together with all the others behind it: see [Pacing.showFrame].
 *
 * Only a hopeless backlog (the decoder really can't keep up) is thrown away: playback jumps to the newest key
 * frame, or asks the sender for one. The sender is told ([takeStats]) and lowers the frame rate.
 */
class VideoPlayer(
    private val clock: PlayoutClock,
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
        clock.onVideoArrival(f.ptsUs, System.nanoTime())
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
        clock.resetVideo()
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

    /** When [f] is due, as a believable time. */
    private fun dueOf(f: VideoFrame, now: Long) = Pacing.plausibleDue(clock.dueNs(f.ptsUs), now)

    /** How many frames at the head of the queue are due to go into the decoder by now (with [qLock] held). */
    private fun readyCount(now: Long): Int {
        var n = 0
        for (f in queue) {
            if (Pacing.submitAtNs(dueOf(f, now), now) > now) break
            n++
        }
        return n
    }

    /**
     * Next frame to decode, or null when [d] is gone. Waits until the frame is due (a bit before: the decoder
     * needs time), and throws away a hopeless backlog: frames that are due but still waiting for the decoder.
     * Frames that are merely waiting for their time are the jitter buffer, not a backlog.
     */
    private fun takeFrame(d: Decoder): VideoFrame? {
        var askKey = false
        val f = synchronized(qLock) {
            var taken: VideoFrame? = null
            while (d.alive && taken == null) {
                if (queue.isEmpty()) {
                    qLock.wait(200)
                    continue
                }
                val now = System.nanoTime()
                var ready = readyCount(now)
                if (ready > CATCH_UP_DEPTH) {
                    var newestKey = -1 // only among the due frames: a key frame still waiting for its time is no reason to jump
                    for (i in 0 until ready) if (queue[i].key) newestKey = i
                    if (newestKey > 0) {
                        repeat(newestKey) { queue.removeFirst() }
                        skipped += newestKey
                    } else if (ready > MAX_QUEUE) {
                        skipped += queue.size
                        queue.clear()
                        waitingKey = true
                        askKey = true
                    }
                    if (queue.isEmpty()) continue
                    ready = readyCount(now)
                }
                val head = queue.first()
                val at = if (waitingKey) now else Pacing.submitAtNs(dueOf(head, now), now)
                if (at > now) {
                    qLock.wait(((at - now) / 1_000_000L).coerceIn(1L, 200L))
                    continue
                }
                minQueue = min(minQueue, (ready - 1).coerceAtLeast(0))
                taken = queue.removeFirst()
            }
            taken
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
        var lastShown = 0L // display time of the frame shown last
        try {
            while (d.alive) {
                val idx = d.codec.dequeueOutputBuffer(info, 10_000)
                if (idx < 0) continue
                val now = System.nanoTime()
                val due = Pacing.plausibleDue(clock.dueNs(info.presentationTimeUs), now)
                if (Pacing.showFrame(due, now, lastShown)) {
                    val at = Pacing.renderAtNs(due, now)
                    d.codec.releaseOutputBuffer(idx, at) // the display shows it at that time
                    lastShown = at
                    framesShown++
                } else {
                    d.codec.releaseOutputBuffer(idx, false) // decoded, but too late to be worth showing
                    synchronized(qLock) { skipped++ }
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

        /** More frames than this due and waiting (~130 ms at 60 fps): skip ahead to the newest key frame if there is one. */
        private const val CATCH_UP_DEPTH = 8

        /** More than this (~400 ms) and no key frame to jump to: give up on the backlog and ask for a key frame. */
        private const val MAX_QUEUE = 24
    }
}
