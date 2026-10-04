package com.phonestream.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteGuardTest {
    private val calls = ArrayList<Boolean>()
    private val verdicts = ArrayList<Boolean>()
    private val guard = MuteGuard({ calls += it }, { verdicts += it })

    private val loud = 5000
    private val quiet = 0

    private fun feed(n: Int, peak: Int) = repeat(n) { guard.onChunk(peak) }

    @Test
    fun silenceNeverTriggersAnything() {
        feed(1000, quiet)
        assertTrue(calls.isEmpty())
        assertTrue(verdicts.isEmpty())
        assertEquals(MuteGuard.State.LISTENING, guard.state)
    }

    @Test
    fun theVeryQuietIsNotCountedAsSound() {
        feed(400, MuteGuard.AUDIBLE - 1)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun soundThatSurvivesMutingMeansItWorks() {
        feed(MuteGuard.WINDOW, loud)
        assertEquals(listOf(true), calls)
        assertEquals(MuteGuard.State.SETTLING, guard.state)
        feed(MuteGuard.SETTLE, loud)
        assertEquals(MuteGuard.State.CHECKING, guard.state)
        feed(MuteGuard.CHECK, loud)
        assertEquals(listOf(true), verdicts)
        assertEquals(listOf(true), calls) // stays muted
        assertEquals(MuteGuard.State.DONE, guard.state)
        feed(500, quiet)
        assertEquals("verdict is final", listOf(true), verdicts)
        assertEquals(listOf(true), calls)
    }

    @Test
    fun anyOneAudibleChunkDuringTheCheckIsEnough() {
        feed(MuteGuard.WINDOW + MuteGuard.SETTLE, loud)
        feed(MuteGuard.CHECK - 1, quiet)
        guard.onChunk(MuteGuard.AUDIBLE)
        assertEquals(listOf(true), verdicts)
    }

    @Test
    fun whenCaptureFollowsTheVolumeThePhoneIsUnmutedAndGivenUpOn() {
        repeat(MuteGuard.MAX_ATTEMPTS) { attempt ->
            // the music is there until the phone gets muted, then the stream goes quiet
            feed(MuteGuard.WINDOW, loud)
            feed(MuteGuard.SETTLE, quiet)
            feed(MuteGuard.CHECK, quiet)
            val last = attempt == MuteGuard.MAX_ATTEMPTS - 1
            assertEquals(if (last) listOf(false) else emptyList<Boolean>(), verdicts)
        }
        assertEquals(listOf(true, false, true, false, true, false), calls)
        assertFalse("phone left audible", calls.last())
        assertEquals(MuteGuard.State.DONE, guard.state)
    }

    @Test
    fun aPausedSongIsNotMistakenForBrokenMuting() {
        feed(MuteGuard.WINDOW, loud) // mute
        feed(MuteGuard.SETTLE + MuteGuard.CHECK, quiet) // the song paused right then: back off, unmute
        assertEquals(listOf(true, false), calls)
        assertTrue(verdicts.isEmpty())
        feed(MuteGuard.WINDOW, loud) // song resumes: second try
        feed(MuteGuard.SETTLE + MuteGuard.CHECK, loud)
        assertEquals(listOf(true, false, true), calls)
        assertEquals(listOf(true), verdicts)
    }

    @Test
    fun aWindowNeedsMostChunksToBeAudible() {
        val need = MuteGuard.WINDOW * 5 / 8
        repeat(need - 1) { guard.onChunk(loud) }
        feed(MuteGuard.WINDOW - (need - 1), quiet)
        assertTrue("not enough sound in the window", calls.isEmpty())
        repeat(need) { guard.onChunk(loud) }
        feed(MuteGuard.WINDOW - need, quiet)
        assertEquals(listOf(true), calls)
    }

    @Test
    fun knownGoodPhonesAreMutedAtOnce() {
        guard.assumeWorks()
        assertEquals(listOf(true), calls)
        assertEquals(MuteGuard.State.DONE, guard.state)
        feed(500, quiet)
        assertEquals(listOf(true), calls)
        assertTrue(verdicts.isEmpty())
    }
}

class AudioBufferingTest {
    @Test
    fun startsSmall() {
        assertEquals(AudioBuffering.START_MS, AudioBuffering(0).targetMs)
    }

    @Test
    fun growsWithEveryDropoutUpToACap() {
        val b = AudioBuffering(0)
        b.onUnderrun(1_000)
        assertEquals(AudioBuffering.START_MS + AudioBuffering.GROW_MS, b.targetMs)
        repeat(50) { b.onUnderrun(2_000L + it) }
        assertEquals(AudioBuffering.MAX_MS, b.targetMs)
    }

    @Test
    fun givesTheMarginBackSlowlyAfterACalmMinute() {
        val b = AudioBuffering(0)
        repeat(4) { b.onUnderrun(1_000) } // 80 + 160 = 240
        assertEquals(240, b.targetMs)
        b.onTick(60_999)
        assertEquals("calm for less than a minute", 240, b.targetMs)
        b.onTick(61_000)
        assertEquals(220, b.targetMs)
        b.onTick(62_000)
        assertEquals("one step per calm minute", 220, b.targetMs)
        b.onTick(121_000)
        assertEquals(200, b.targetMs)
    }

    @Test
    fun aNewDropoutRestartsTheCalmPeriod() {
        val b = AudioBuffering(0)
        b.onUnderrun(1_000) // 120
        b.onUnderrun(50_000) // 160
        b.onTick(61_000)
        assertEquals(160, b.targetMs)
        b.onTick(110_000)
        assertEquals(140, b.targetMs)
    }

    @Test
    fun neverShrinksBelowTheMinimum() {
        val b = AudioBuffering(0)
        var t = 0L
        repeat(10) { t += 61_000; b.onTick(t) }
        assertEquals(AudioBuffering.MIN_MS, b.targetMs)
    }

    @Test
    fun onlyTrimsWhenWellOverTheTarget() {
        val b = AudioBuffering(0) // target 80 ms, slack 200 ms
        assertEquals(0, b.excessMs(80))
        assertEquals(0, b.excessMs(280))
        assertEquals("trims down to the target, not just to the slack", 201, b.excessMs(281))
        assertEquals(920, b.excessMs(1_000))
    }
}
