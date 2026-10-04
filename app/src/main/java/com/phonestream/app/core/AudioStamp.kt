package com.phonestream.app.core

import kotlin.math.abs

/**
 * Sender-side timestamps for the sound. Reading a chunk from the recorder returns when the data is there, which is
 * not evenly spaced: after a hiccup two chunks come back to back. Stamping each with the moment its read returned
 * would give the TV jittery times, so the stamps advance by exactly the chunk's duration and only follow the real
 * clock slowly (or jump when the two are far apart, e.g. after the recorder stalled).
 * Times are the end of the chunk, in microseconds of the sender's monotonic clock.
 */
class AudioStamp(private val sampleRate: Int) {
    private var expectedUs = 0L
    private var started = false

    /** [frames] samples (per channel) were just read at [nowUs]; returns the timestamp of the end of that chunk. */
    fun stamp(nowUs: Long, frames: Int): Long {
        val durationUs = frames * 1_000_000L / sampleRate
        if (!started) {
            started = true
            expectedUs = nowUs
        } else {
            expectedUs += durationUs
            val error = nowUs - expectedUs
            expectedUs = if (abs(error) > RESYNC_US) nowUs else expectedUs + error / FOLLOW
        }
        return expectedUs
    }

    companion object {
        private const val RESYNC_US = 60_000L
        private const val FOLLOW = 32
    }
}

/** An encoder timestamp is believable if it is near the sender's monotonic clock; if not (odd drivers), use [nowUs]. */
fun sanePts(ptsUs: Long, nowUs: Long): Long = if (abs(ptsUs - nowUs) > 2_000_000L) nowUs else ptsUs
