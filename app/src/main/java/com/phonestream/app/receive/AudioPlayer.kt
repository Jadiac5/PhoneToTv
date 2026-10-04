package com.phonestream.app.receive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.phonestream.app.core.AudioBuffering
import com.phonestream.app.core.AudioConfig
import com.phonestream.app.core.AudioData
import com.phonestream.app.core.PlayoutClock

/**
 * Plays the sender's raw PCM through a low-latency AudioTrack.
 *
 * The network delivers sound in uneven bursts, the speaker wants it perfectly evenly. The cushion in between
 * ([AudioBuffering]) starts at 80 ms, grows each time the sound runs dry (a dry run is a crackle: better a bit more
 * delay than a crackle) and shrinks again after a calm minute. Playback only (re)starts once the cushion is full,
 * so a hiccup is one short gap instead of a stutter. Sound piling up far beyond the cushion is dropped.
 *
 * It is also the reference for the picture: every chunk keeps the sender's timestamp, and a few times a second
 * the player tells the [PlayoutClock] which timestamp is coming out of the speaker right now (from
 * `AudioTrack.getTimestamp`, which accounts for the track buffer and the audio driver), so the video player can
 * show every frame at the moment its sound is heard.
 */
class AudioPlayer(private val clock: PlayoutClock) : AudioSink {
    /** One piece of sound and the sender's time of its first sample. */
    private class Chunk(val data: ByteArray, val startPtsUs: Long)

    /** [startFrame] is the number of frames the track had been given before the chunk that starts at [ptsUs]. */
    private class Mark(val startFrame: Long, val ptsUs: Long)

    private val qLock = java.lang.Object()
    private val queue = ArrayDeque<Chunk>()
    private var queuedBytes = 0

    @Volatile
    private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var bytesPerMs = 192
    private var frameBytes = 4

    @Volatile
    private var sampleRate = 48_000

    @Synchronized
    override fun start(cfg: AudioConfig) {
        stop()
        if (cfg.sampleRate <= 0 || cfg.channels !in 1..2) return
        val channelMask = if (cfg.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        frameBytes = cfg.channels * 2
        sampleRate = cfg.sampleRate
        bytesPerMs = cfg.sampleRate * frameBytes / 1000
        val min = AudioTrack.getMinBufferSize(cfg.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min, bytesPerMs * TRACK_BUFFER_MS)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(cfg.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            return // no sound, video still plays
        }
        try {
            t.play()
        } catch (e: Exception) {
            t.release()
            return
        }
        track = t
        synchronized(qLock) {
            queue.clear()
            queuedBytes = 0
        }
        running = true
        thread = Thread({ playLoop(t, cfg.sampleRate) }, "ps-audio-out").apply {
            isDaemon = true
            start()
        }
    }

    private fun playLoop(t: AudioTrack, sampleRate: Int) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
        }
        val buffering = AudioBuffering(SystemClock.elapsedRealtime())
        var written = 0L // frames handed to the track so far
        var primed = false // cushion full, sound is flowing
        val marks = ArrayDeque<Mark>() // which sender time each stretch of those frames has
        val stamp = AudioTimestamp()
        var lastHeardFrame = -1L
        var lastHeardCheck = 0L
        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                buffering.onTick(now)
                if (now - lastHeardCheck >= HEARD_EVERY_MS) {
                    lastHeardCheck = now
                    lastHeardFrame = reportHeard(t, marks, sampleRate, stamp, lastHeardFrame)
                }
                val inTrackMs = ((written - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)) * 1000 / sampleRate).toInt()

                var chunk: Chunk? = null
                synchronized(qLock) {
                    if (!primed) {
                        if (queuedBytes / bytesPerMs < buffering.targetMs) {
                            qLock.wait(20)
                            return@synchronized
                        }
                        primed = true
                    }
                    if (queue.isEmpty()) qLock.wait(20)
                    // far too much waiting (burst after a stall): drop the oldest, keep the newest
                    val excess = buffering.excessMs(queuedBytes / bytesPerMs + inTrackMs)
                    var drop = excess / CHUNK_MS
                    while (drop-- > 0 && queue.size > 1) queuedBytes -= queue.removeFirst().data.size
                    chunk = queue.removeFirstOrNull()?.also { queuedBytes -= it.data.size }
                }
                if (!primed) continue
                val c = chunk
                if (c == null) {
                    if (inTrackMs <= RUN_DRY_MS) { // the speaker has nothing left to play: refill before going on
                        buffering.onUnderrun(now)
                        primed = false
                    }
                    continue
                }
                marks.addLast(Mark(written, c.startPtsUs))
                if (marks.size > MAX_MARKS) marks.removeFirst() // (normally pruned as the sound is heard)
                val pcm = c.data
                var off = 0
                while (running && off < pcm.size) {
                    val n = t.write(pcm, off, pcm.size - off)
                    if (n < 0) return
                    off += n
                }
                written += pcm.size / frameBytes
            }
        } catch (_: Exception) {
            // track released underneath us: we are being stopped
        }
    }

    /**
     * Tells the clock which sender time is audible now. The track says "frame N was presented at time T"
     * (`getTimestamp`); [marks] turns N into a sender time. Returns the frame it reported (to skip repeats: while
     * the sound is stalled the track keeps giving the same, old, still correct pair).
     */
    private fun reportHeard(t: AudioTrack, marks: ArrayDeque<Mark>, rate: Int, ts: AudioTimestamp, lastFrame: Long): Long {
        val frame: Long
        val heardNs: Long
        if (t.getTimestamp(ts)) {
            frame = ts.framePosition
            heardNs = ts.nanoTime
        } else { // no exact figure (right after start, some drivers): the head position plus a typical driver delay
            frame = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            heardNs = System.nanoTime() + HEAD_LATENCY_NS
        }
        if (frame <= 0 || frame == lastFrame || marks.isEmpty()) return lastFrame
        while (marks.size >= 2 && marks[1].startFrame <= frame) marks.removeFirst()
        val m = marks.first()
        if (frame < m.startFrame) return lastFrame
        clock.onAudioHeard(m.ptsUs + (frame - m.startFrame) * 1_000_000L / rate, heardNs)
        return frame
    }

    override fun feed(d: AudioData) {
        if (!running) return
        val bytes = d.data.copyOfRange(d.offset, d.data.size)
        val chunk = Chunk(bytes, d.ptsUs - bytes.size / frameBytes * 1_000_000L / sampleRate) // ptsUs = end of chunk
        synchronized(qLock) {
            queue.addLast(chunk)
            queuedBytes += bytes.size
            if (queuedBytes > bytesPerMs * HARD_CAP_MS) { // the player thread is stuck: don't hoard memory
                queue.clear()
                queuedBytes = 0
            }
            qLock.notifyAll()
        }
    }

    @Synchronized
    override fun stop() {
        running = false
        clock.onAudioStopped()
        synchronized(qLock) { qLock.notifyAll() }
        try { thread?.join(300) } catch (_: InterruptedException) {}
        thread = null
        track?.let {
            try { it.pause() } catch (_: Exception) {}
            try { it.flush() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        track = null
        synchronized(qLock) {
            queue.clear()
            queuedBytes = 0
        }
    }

    companion object {
        private const val CHUNK_MS = 20
        private const val TRACK_BUFFER_MS = 120
        private const val RUN_DRY_MS = 5
        private const val HARD_CAP_MS = 2000
        private const val HEARD_EVERY_MS = 40L
        private const val MAX_MARKS = 400

        /** What the audio driver typically adds between "consumed" and "audible" when the track can't tell. */
        private const val HEAD_LATENCY_NS = 40_000_000L
    }
}
