package com.phonestream.app.core

import kotlin.math.abs

/**
 * The per-frame decisions of the TV's video player, as pure functions of "when is this frame due" (from the
 * [PlayoutClock]) and the local clock, all in nanoseconds.
 *
 * A frame is handed to the decoder shortly before it is due ([submitAtNs]), then put on screen at its due time
 * ([renderAtNs]). A frame that comes out of the decoder long after its due time is not worth showing in a rush:
 * after a stall the whole backlog would flash past ("fast forward"), so such stale frames are shown at most
 * every [STALE_MIN_GAP_NS] and the picture jumps ahead instead ([showFrame]).
 */
object Pacing {
    /** Decoding and handing over to the display takes a bit: the frame goes in this long before it is due. */
    const val DECODE_LEAD_NS = 40_000_000L

    /** A frame is never held back longer than this (the clock must be wrong, don't freeze the picture). */
    const val MAX_HOLD_NS = 600_000_000L

    /** The display is never told to show a frame further ahead than this. */
    const val MAX_RENDER_AHEAD_NS = 60_000_000L

    /** Later than this behind its due time a frame counts as stale. */
    const val STALE_NS = 120_000_000L

    /** While frames are stale, show at most one per this (about 30 per second). */
    const val STALE_MIN_GAP_NS = 33_000_000L

    /** A due time further than this from now is nonsense (decoder lost the timestamp, clock not known yet). */
    const val MAX_PLAUSIBLE_NS = 1_000_000_000L

    /** [dueNs] as long as it is believable, otherwise "now" (show at once, like a player without any pacing). */
    fun plausibleDue(dueNs: Long, nowNs: Long): Long = if (abs(dueNs - nowNs) > MAX_PLAUSIBLE_NS) nowNs else dueNs

    /** When the decoder should get the frame: just before it is due, immediately if that has passed or is far off. */
    fun submitAtNs(dueNs: Long, nowNs: Long): Long =
        if (dueNs - nowNs > MAX_HOLD_NS) nowNs else dueNs - DECODE_LEAD_NS

    /** The timestamp for `releaseOutputBuffer`: the due time, but not in the past and not far in the future. */
    fun renderAtNs(dueNs: Long, nowNs: Long): Long = dueNs.coerceIn(nowNs, nowNs + MAX_RENDER_AHEAD_NS)

    /** Whether a decoded frame goes to the screen. [lastShownNs] is when (the display time) the previous one did. */
    fun showFrame(dueNs: Long, nowNs: Long, lastShownNs: Long): Boolean =
        nowNs - dueNs <= STALE_NS || nowNs - lastShownNs >= STALE_MIN_GAP_NS
}
