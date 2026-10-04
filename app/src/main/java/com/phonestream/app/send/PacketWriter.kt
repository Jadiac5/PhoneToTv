package com.phonestream.app.send

import android.os.SystemClock
import com.phonestream.app.core.Msg
import com.phonestream.app.core.PacketStream
import com.phonestream.app.core.Proto
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Single writer thread in front of the socket, with three queues: control > audio > video.
 *
 * Video frames go out in slices ([Proto.VIDEO_SLICE]) and between any two slices the writer looks at the control
 * and audio queues first. So a 500 KB key frame can no longer hold the sound up for 100 ms: that wait was a
 * crackle on every key frame.
 *
 * Latency is more important than completeness: when the network can't keep up, video that has been waiting too
 * long is thrown away (never audio/control). The stream jumps to the newest key frame already in the queue, or,
 * with none, resumes at the next one, so the picture on the TV stays "live" instead of drifting further behind.
 * (The [Tuner] lowers the bitrate long before this has to happen more than once.)
 */
class PacketWriter(
    private val ps: PacketStream,
    private val onError: (IOException) -> Unit,
    private val onNeedKeyFrame: () -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private class Item(val type: Int, val payload: ByteArray)
    private class Frame(val gen: Int, val key: Boolean, val ptsUs: Long, val data: ByteArray, val enqueuedAt: Long)

    /** What the writer thread writes next; [done] is the frame this was the last slice of. */
    private class Out(val type: Int, val payload: ByteArray, val done: Frame?)

    private val lock = ReentrantLock()
    private val nonEmpty = lock.newCondition()
    private val control = ArrayDeque<Item>()
    private val audio = ArrayDeque<Item>()
    private val video = ArrayDeque<Frame>()
    private var videoBytes = 0L
    private var current: Frame? = null // the frame being sliced out right now
    private var currentOff = 0
    private var needKey = false
    private var closed = false
    private var generation = 0

    val bytesSent = AtomicLong()
    val videoDropped = AtomicInteger()
    val audioDropped = AtomicInteger()

    /** Worst enqueue-to-fully-written time of a video frame since the last [takeLatencyMax]; -1 = none went out. */
    private val latencyMax = AtomicInteger(-1)

    private val thread = Thread({ runLoop() }, "ps-writer").apply { isDaemon = true }

    fun start() = thread.start()

    fun takeLatencyMax(): Int = latencyMax.getAndSet(-1)

    /**
     * Invalidates all video from older encoders (their queued frames are discarded, a half-sent one is abandoned)
     * and returns the generation number the NEW encoder must tag its frames with.
     */
    fun newGeneration(): Int = lock.withLock {
        generation++
        video.clear()
        videoBytes = 0
        needKey = false
        generation
    }

    fun sendControl(type: Int, payload: ByteArray = PacketStream.EMPTY) {
        lock.withLock {
            if (closed) return
            control.addLast(Item(type, payload))
            nonEmpty.signal()
        }
    }

    /** [packet] is an already encoded [Msg.audioData] payload. */
    fun sendAudio(packet: ByteArray) {
        lock.withLock {
            if (closed) return
            audio.addLast(Item(Proto.T_AUDIO_DATA, packet))
            while (audio.size > MAX_AUDIO_QUEUE) {
                audio.removeFirst()
                audioDropped.incrementAndGet()
            }
            nonEmpty.signal()
        }
    }

    fun sendVideo(gen: Int, key: Boolean, ptsUs: Long, data: ByteArray) {
        var askKey = false
        lock.lock()
        try {
            if (closed || gen != generation) return
            val now = clock()
            val head = video.firstOrNull()
            if (head != null && (now - head.enqueuedAt > MAX_VIDEO_AGE_MS || videoBytes > MAX_VIDEO_BYTES)) {
                // We are behind: the backlog is stale. Jump to the newest key frame in it, or drop it all.
                val newestKey = video.indexOfLast { it.key }
                if (newestKey > 0) {
                    repeat(newestKey) { videoBytes -= video.removeFirst().data.size }
                    videoDropped.addAndGet(newestKey)
                } else {
                    videoDropped.addAndGet(video.size)
                    video.clear()
                    videoBytes = 0
                    if (!key) needKey = true
                }
            }
            if (needKey && !key) {
                videoDropped.incrementAndGet()
                askKey = true
            } else {
                if (key) needKey = false
                video.addLast(Frame(gen, key, ptsUs, data, now))
                videoBytes += data.size
                nonEmpty.signal()
            }
        } finally {
            lock.unlock()
        }
        if (askKey) onNeedKeyFrame()
    }

    /**
     * Stops accepting new data. Whatever is in the control queue (e.g. a BYE) is still flushed, waiting at
     * most [drainMs] milliseconds for it.
     */
    fun close(bye: ByteArray?, drainMs: Long) {
        lock.withLock {
            if (closed) return
            if (bye != null) control.addLast(Item(Proto.T_BYE, bye))
            closed = true
            audio.clear()
            video.clear()
            videoBytes = 0
            nonEmpty.signalAll()
        }
        try {
            thread.join(drainMs)
        } catch (_: InterruptedException) {
        }
    }

    private fun takeNext(): Out? {
        lock.lock()
        try {
            while (true) {
                control.removeFirstOrNull()?.let { return Out(it.type, it.payload, null) }
                if (closed) return null
                audio.removeFirstOrNull()?.let { return Out(it.type, it.payload, null) }

                var f = current
                if (f != null && f.gen != generation) { // its encoder was replaced halfway: abandon it
                    current = null
                    f = null
                }
                if (f == null) {
                    f = video.removeFirstOrNull()
                    if (f != null) {
                        videoBytes -= f.data.size
                        current = f
                        currentOff = 0
                    }
                }
                if (f != null) {
                    val off = currentOff
                    val len = min(Proto.VIDEO_SLICE, f.data.size - off)
                    currentOff = off + len
                    val done = currentOff >= f.data.size
                    if (done) current = null
                    return Out(Proto.T_VIDEO_PART, Msg.videoPart(f.key, f.ptsUs, f.data, off, len), if (done) f else null)
                }
                nonEmpty.await()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun runLoop() {
        try {
            while (true) {
                val out = takeNext() ?: return
                try {
                    ps.write(out.type, out.payload)
                    bytesSent.addAndGet(out.payload.size + 5L)
                } catch (e: IOException) {
                    val wasClosed = lock.withLock { closed }
                    if (!wasClosed) onError(e)
                    return
                }
                out.done?.let { latencyMax.accumulateAndGet((clock() - it.enqueuedAt).toInt(), ::maxOf) }
            }
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        /** Video older than this when a new frame arrives means the network is not keeping up. */
        const val MAX_VIDEO_AGE_MS = 300L
        const val MAX_VIDEO_BYTES = 6L * 1024 * 1024
        const val MAX_AUDIO_QUEUE = 25 // 25 x 20 ms = 500 ms
    }
}
