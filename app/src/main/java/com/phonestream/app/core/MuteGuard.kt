package com.phonestream.app.core

import kotlin.math.max

/**
 * Silences the phone while it streams, but only if that doesn't silence the TV as well.
 *
 * Android's playback capture is *believed* to take the sound before the phone's volume is applied, but that is
 * up to the phone maker. So the first time, we watch: once real sound is being captured we mute the phone and
 * check that sound keeps arriving. If it vanishes, the capture follows the volume: we unmute and report that
 * muting can't work on this phone (instead of leaving the TV silent). A silence that is only the music pausing
 * looks the same for a moment, so the test is repeated a few times before giving up.
 *
 * Feed it the loudest sample (0..32768) of every 20 ms audio chunk through [onChunk], from one thread.
 */
class MuteGuard(
    /** Mutes / unmutes the phone. */
    private val setMuted: (Boolean) -> Unit,
    /** Called once with the verdict: true = muting works here, false = it doesn't. */
    private val onVerdict: (works: Boolean) -> Unit,
) {
    enum class State { LISTENING, SETTLING, CHECKING, DONE }

    var state = State.LISTENING
        private set

    private var windowChunks = 0
    private var windowAudible = 0
    private var windowMax = 0
    private var phaseChunks = 0
    private var checkMax = 0
    private var attempts = 0

    /** Skips the test: the phone is already known to behave (muting verified earlier). */
    fun assumeWorks() {
        state = State.DONE
        setMuted(true)
    }

    fun onChunk(peak: Int) {
        when (state) {
            State.DONE -> {}
            State.LISTENING -> {
                windowChunks++
                if (peak >= AUDIBLE) windowAudible++
                windowMax = max(windowMax, peak)
                if (windowChunks >= WINDOW) {
                    if (windowAudible >= WINDOW * 5 / 8) {
                        setMuted(true)
                        state = State.SETTLING
                        phaseChunks = 0
                    }
                    windowChunks = 0
                    windowAudible = 0
                    windowMax = 0
                }
            }
            State.SETTLING -> if (++phaseChunks >= SETTLE) {
                state = State.CHECKING
                phaseChunks = 0
                checkMax = 0
            }
            State.CHECKING -> {
                checkMax = max(checkMax, peak)
                if (++phaseChunks >= CHECK) {
                    if (checkMax >= AUDIBLE) {
                        state = State.DONE
                        onVerdict(true)
                    } else {
                        setMuted(false)
                        if (++attempts >= MAX_ATTEMPTS) {
                            state = State.DONE
                            onVerdict(false)
                        } else state = State.LISTENING
                    }
                }
            }
        }
    }

    companion object {
        const val AUDIBLE = 300 // about -40 dBFS
        const val WINDOW = 40 // 0.8 s
        const val SETTLE = 10 // 0.2 s for the volume change to take hold
        const val CHECK = 50 // 1 s
        const val MAX_ATTEMPTS = 3
    }
}
