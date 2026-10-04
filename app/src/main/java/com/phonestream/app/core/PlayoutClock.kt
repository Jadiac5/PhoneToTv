package com.phonestream.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * When the TV should show a video frame. Pure logic, times in nanoseconds of one local monotonic clock
 * (`System.nanoTime()`, which is also what `AudioTrack.getTimestamp` and `MediaCodec.releaseOutputBuffer` use).
 *
 * Both streams carry the sender's capture time (pts, microseconds, one clock on the phone). Showing every frame
 * at `pts + offset` plays them at the pace they were captured, no matter how unevenly the network delivered them,
 * and what is left to decide is the one number `offset`:
 *
 *  - **With sound** the offset comes from the speaker: the audio player reports "the sound with this pts is being
 *    heard right now" ([onAudioHeard]) and the picture follows, so lips and clicks line up with what the ears get.
 *    (The sound has its own delay on the TV: cushion, speaker buffer, driver. That used to leave the picture
 *    ahead of the sound.) [syncOffsetMs] nudges this for a soundbar or Bluetooth speaker that adds delay.
 *  - **Without sound** (or while it is silent for long) the offset comes from the video itself: the fastest
 *    frame seen lately says where the network's minimum delay is, and a jitter buffer on top ([jitterNs], grows
 *    at once when frames arrive late, shrinks slowly) keeps ordinary Wi-Fi hiccups invisible.
 */
class PlayoutClock(private val nowNs: () -> Long = System::nanoTime) {
    /** Extra delay of the picture relative to the sound, in ms (negative: picture earlier). Only used with sound. */
    @Volatile
    var syncOffsetMs: Int = 0

    // ---- sound as the reference ----
    private var audioOffsetNs = 0L
    private var audioAt = 0L
    private var audioLive = false

    // ---- picture on its own: min-filter of (arrival - pts), in buckets so old minima age out ----
    private val bucketMin = LongArray(BUCKETS)
    private val bucketMax = LongArray(BUCKETS)
    private val bucketAt = LongArray(BUCKETS) { Long.MIN_VALUE }
    private var lastVideoPts = Long.MIN_VALUE
    private var jitterNs = MIN_JITTER_NS
    private var jitterAt = 0L

    /** A video stream (re)started, maybe from another phone: forget what was learned about the last one's timestamps. */
    @Synchronized
    fun resetVideo() {
        bucketAt.fill(Long.MIN_VALUE)
        lastVideoPts = Long.MIN_VALUE
        jitterNs = MIN_JITTER_NS
    }

    /** The speaker is closed (no sound in this stream): the picture paces itself. */
    @Synchronized
    fun onAudioStopped() {
        audioLive = false
    }

    /** The sound with sender time [ptsUs] is audible at local time [heardNs]. Call a few times a second. */
    @Synchronized
    fun onAudioHeard(ptsUs: Long, heardNs: Long) {
        val sample = heardNs - ptsUs * 1_000
        val now = nowNs()
        audioOffsetNs = if (!audioLive || now - audioAt > STALE_NS || abs(sample - audioOffsetNs) > JUMP_NS) sample
        else audioOffsetNs + (sample - audioOffsetNs) / SMOOTHING
        audioAt = now
        audioLive = true
    }

    /** A frame with sender time [ptsUs] arrived at [arrivalNs]. Feeds the picture-only estimate. */
    @Synchronized
    fun onVideoArrival(ptsUs: Long, arrivalNs: Long) {
        if (ptsUs <= lastVideoPts) return // a repeated frame carries an old time: no information about the network
        lastVideoPts = ptsUs
        val x = arrivalNs - ptsUs * 1_000
        val epoch = Math.floorDiv(arrivalNs, BUCKET_NS)
        val slot = Math.floorMod(epoch, BUCKETS.toLong()).toInt()
        if (bucketAt[slot] != epoch) {
            bucketAt[slot] = epoch
            bucketMin[slot] = x
            bucketMax[slot] = x
        } else {
            bucketMin[slot] = min(bucketMin[slot], x)
            bucketMax[slot] = max(bucketMax[slot], x)
        }
        // the jitter buffer: cover the worst delay of the last few seconds, grow at once, shrink slowly
        val base = baseLocked(epoch) ?: return
        var worst = 0L
        for (i in 0 until BUCKETS) {
            if (bucketAt[i] != Long.MIN_VALUE && epoch - bucketAt[i] < JITTER_BUCKETS) worst = max(worst, bucketMax[i] - base)
        }
        val target = (worst + MARGIN_NS).coerceIn(MIN_JITTER_NS, MAX_JITTER_NS)
        if (target >= jitterNs) {
            jitterNs = target
        } else {
            val dt = max(0L, arrivalNs - jitterAt)
            jitterNs = max(target, jitterNs - dt / SHRINK_RATIO) // 5 ms per second less
        }
        jitterAt = arrivalNs
    }

    private fun baseLocked(epoch: Long): Long? {
        var base: Long? = null
        for (i in 0 until BUCKETS) {
            if (bucketAt[i] != Long.MIN_VALUE && epoch - bucketAt[i] < BUCKETS) {
                val m = bucketMin[i]
                if (base == null || m < base) base = m
            }
        }
        return base
    }

    /** True while the sound is the reference. */
    @Synchronized
    fun followsAudio(): Boolean = audioLive && nowNs() - audioAt <= STALE_NS

    /** Local time at which the frame with sender time [ptsUs] should appear. */
    @Synchronized
    fun dueNs(ptsUs: Long): Long {
        val now = nowNs()
        val own = ownDueLocked(ptsUs, now)
        if (audioLive && now - audioAt <= STALE_NS) {
            val viaAudio = ptsUs * 1_000 + audioOffsetNs
            // Both clocks must roughly agree (they differ by the sound's delay, a few hundred ms at most).
            // If not, the sender's two timestamps don't share a clock: trust the picture's own.
            if (own == null || abs(viaAudio - own) <= MAX_DISAGREEMENT_NS) return viaAudio + syncOffsetMs * 1_000_000L
        }
        return own ?: now // nothing known yet: show at once
    }

    private fun ownDueLocked(ptsUs: Long, now: Long): Long? {
        val base = baseLocked(Math.floorDiv(now, BUCKET_NS)) ?: return null
        return ptsUs * 1_000 + base + jitterNs
    }

    companion object {
        private const val BUCKET_NS = 500_000_000L
        private const val BUCKETS = 20 // 10 s of minima
        private const val JITTER_BUCKETS = 6 // 3 s of maxima
        const val STALE_NS = 2_000_000_000L
        private const val JUMP_NS = 30_000_000L
        private const val SMOOTHING = 32
        const val MIN_JITTER_NS = 60_000_000L
        const val MAX_JITTER_NS = 300_000_000L
        private const val MARGIN_NS = 30_000_000L
        private const val SHRINK_RATIO = 200L
        private const val MAX_DISAGREEMENT_NS = 700_000_000L
    }
}
