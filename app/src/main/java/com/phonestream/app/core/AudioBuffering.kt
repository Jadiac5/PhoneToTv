package com.phonestream.app.core

import kotlin.math.max
import kotlin.math.min

/**
 * How much sound the TV keeps in hand before playing it. Too little and every network hiccup is a crackle;
 * too much and the sound lags the picture. So: start small, grow a little each time the sound runs dry,
 * shrink again after a long calm. Pure logic, times in ms.
 */
class AudioBuffering(startMs: Long) {
    var targetMs = START_MS
        private set

    private var lastUnderrun = startMs
    private var lastShrink = startMs

    /** The sound ran dry: keep more in hand from now on. */
    fun onUnderrun(now: Long) {
        targetMs = min(targetMs + GROW_MS, MAX_MS)
        lastUnderrun = now
        lastShrink = now
    }

    /** Call now and then; gives some of the safety margin back once things have been calm for a minute. */
    fun onTick(now: Long) {
        if (targetMs > MIN_MS && now - lastUnderrun >= CALM_MS && now - lastShrink >= CALM_MS) {
            targetMs = max(targetMs - SHRINK_MS, MIN_MS)
            lastShrink = now
        }
    }

    /** How many ms of the oldest sound to throw away, given [totalMs] waiting (queue + already in the speaker's buffer). */
    fun excessMs(totalMs: Int): Int = if (totalMs > targetMs + SLACK_MS) totalMs - targetMs else 0

    companion object {
        const val START_MS = 80
        const val MIN_MS = 60
        const val MAX_MS = 400
        const val GROW_MS = 40
        const val SHRINK_MS = 20
        const val CALM_MS = 60_000L
        const val SLACK_MS = 200
    }
}
