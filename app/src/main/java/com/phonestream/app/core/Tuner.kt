package com.phonestream.app.core

import kotlin.math.max
import kotlin.math.min

/**
 * Decides how hard to push the link, so the picture stays live instead of freezing and catching up.
 * Pure logic with an injected clock (all times in ms) so it can be tested on the JVM.
 *
 *  - [bitrate]: cut quickly when the network falls behind, climb back slowly while it keeps up (AIMD).
 *    After a cut that came right after a climb, the next climb waits longer (the link's limit is near).
 *  - [fps]: when the TV reports that its decoder can't keep up at full rate, halve the frame rate for a while,
 *    then try again (waiting longer each time it fails).
 */
class Tuner(
    private val ceilingBps: Int,
    private val floorBps: Int,
    private val maxFps: Int,
    startBps: Int,
) {
    var bitrate: Int = startBps.coerceIn(floorBps, ceilingBps)
        private set
    var fps: Int = maxFps
        private set

    private var lastCut = Long.MIN_VALUE / 2
    private var lastRaise = Long.MIN_VALUE / 2
    private var holdUntil = 0L
    private var hold = BASE_HOLD_MS

    private var behindRuns = 0
    private var fpsChangedAt = 0L
    private var recoverAfter = FIRST_RECOVER_MS

    /**
     * Once per tick. [latencyMs] is the worst time a video frame spent between "encoder produced it" and
     * "fully handed to the network" during the tick (-1 if no frame went out), [dropped] the frames the
     * sender had to throw away.
     */
    fun onTick(nowMs: Long, latencyMs: Int, dropped: Int) {
        val bad = dropped > 0 || latencyMs > BAD_LATENCY_MS
        if (bad) {
            if (nowMs - lastCut >= CUT_GAP_MS) {
                val factor = if (dropped > 0 || latencyMs > 2 * BAD_LATENCY_MS) 0.6 else 0.75
                setBitrate(bitrate * factor)
                if (nowMs - lastCut > 60_000) hold = BASE_HOLD_MS
                else if (nowMs - lastRaise < 10_000) hold = min(hold * 2, MAX_HOLD_MS)
                holdUntil = nowMs + hold
                lastCut = nowMs
            }
            return
        }
        if (latencyMs in 0..GOOD_LATENCY_MS && nowMs >= holdUntil && nowMs - lastRaise >= RAISE_GAP_MS) {
            setBitrate(bitrate + ceilingBps * 0.05)
            lastRaise = nowMs
        }
    }

    /** Once per report from the TV. */
    fun onRemote(nowMs: Long, s: Stats) {
        val behind = s.queued >= BEHIND_QUEUE || s.skipped > 0
        behindRuns = if (behind) behindRuns + 1 else 0
        if (behindRuns >= 2) {
            behindRuns = 0
            if (fps > FALLBACK_FPS && maxFps > FALLBACK_FPS) {
                fps = FALLBACK_FPS
                fpsChangedAt = nowMs
            } else if (nowMs - lastCut >= CUT_GAP_MS) {
                // already at the lower rate and the decoder still can't cope: less data per frame helps a little
                setBitrate(bitrate * 0.85)
                lastCut = nowMs
                holdUntil = max(holdUntil, nowMs + hold)
            }
        } else if (!behind && fps < maxFps && nowMs - fpsChangedAt >= recoverAfter) {
            fps = maxFps
            fpsChangedAt = nowMs
            recoverAfter = min(recoverAfter * 2, MAX_RECOVER_MS)
        }
    }

    private fun setBitrate(v: Double) {
        bitrate = v.toInt().coerceIn(floorBps, ceilingBps)
    }

    companion object {
        const val BAD_LATENCY_MS = 220
        const val GOOD_LATENCY_MS = 100
        const val CUT_GAP_MS = 800L
        const val RAISE_GAP_MS = 1000L
        const val BASE_HOLD_MS = 3_000L
        const val MAX_HOLD_MS = 30_000L
        const val BEHIND_QUEUE = 3
        const val FALLBACK_FPS = 30
        const val FIRST_RECOVER_MS = 30_000L
        const val MAX_RECOVER_MS = 240_000L
    }
}
