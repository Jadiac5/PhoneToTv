package com.phonestream.app.receive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.phonestream.app.core.AudioBuffering
import com.phonestream.app.core.AudioConfig
import com.phonestream.app.core.AudioData

/**
 * Plays the sender's raw PCM through a low-latency AudioTrack.
 *
 * The network delivers sound in uneven bursts, the speaker wants it perfectly evenly. The cushion in between
 * ([AudioBuffering]) starts at 80 ms, grows each time the sound runs dry (a dry run is a crackle: better a bit more
 * delay than a crackle) and shrinks again after a calm minute. Playback only (re)starts once the cushion is full,
 * so a hiccup is one short gap instead of a stutter. Sound piling up far beyond the cushion is dropped.
 */
class AudioPlayer : AudioSink {
    private val qLock = java.lang.Object()
    private val queue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0

    @Volatile
    private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var bytesPerMs = 192
    private var frameBytes = 4

    @Synchronized
    override fun start(cfg: AudioConfig) {
        stop()
        if (cfg.sampleRate <= 0 || cfg.channels !in 1..2) return
        val channelMask = if (cfg.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        frameBytes = cfg.channels * 2
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
        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                buffering.onTick(now)
                val inTrackMs = ((written - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)) * 1000 / sampleRate).toInt()

                var chunk: ByteArray? = null
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
                    while (drop-- > 0 && queue.size > 1) queuedBytes -= queue.removeFirst().size
                    chunk = queue.removeFirstOrNull()?.also { queuedBytes -= it.size }
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
                var off = 0
                while (running && off < c.size) {
                    val n = t.write(c, off, c.size - off)
                    if (n < 0) return
                    off += n
                }
                written += c.size / frameBytes
            }
        } catch (_: Exception) {
            // track released underneath us: we are being stopped
        }
    }

    override fun feed(d: AudioData) {
        if (!running) return
        val chunk = d.data.copyOfRange(d.offset, d.data.size)
        synchronized(qLock) {
            queue.addLast(chunk)
            queuedBytes += chunk.size
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
    }
}
