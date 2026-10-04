package com.phonestream.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerTest {
    private val ceiling = 40_000_000
    private val floor = 4_000_000
    private fun tuner(maxFps: Int = 60, start: Int = 18_000_000) = Tuner(ceiling, floor, maxFps, start)

    @Test
    fun startsWhereToldWithinTheRange() {
        assertEquals(18_000_000, tuner().bitrate)
        assertEquals(floor, tuner(start = 1).bitrate)
        assertEquals(ceiling, tuner(start = Int.MAX_VALUE).bitrate)
        assertEquals(60, tuner().fps)
    }

    private fun assertBps(expected: Double, actual: Int) = assertEquals(expected, actual.toDouble(), 2.0)

    @Test
    fun droppedFramesCutTheBitrateHard() {
        val t = tuner()
        t.onTick(0, latencyMs = -1, dropped = 3)
        assertBps(10_800_000.0, t.bitrate) // x0.6
    }

    @Test
    fun highLatencyCutsSofterThanDrops() {
        val t = tuner()
        t.onTick(0, latencyMs = Tuner.BAD_LATENCY_MS + 1, dropped = 0)
        assertBps(13_500_000.0, t.bitrate) // x0.75
        val u = tuner()
        u.onTick(0, latencyMs = 2 * Tuner.BAD_LATENCY_MS + 1, dropped = 0)
        assertBps(10_800_000.0, u.bitrate) // very late: x0.6
    }

    @Test
    fun aBadStretchIsCutOnlyOncePerGap() {
        val t = tuner()
        t.onTick(0, 500, 5)
        val after = t.bitrate
        t.onTick(100, 500, 5)
        t.onTick(Tuner.CUT_GAP_MS - 1, 500, 5)
        assertEquals(after, t.bitrate)
        t.onTick(Tuner.CUT_GAP_MS, 500, 5)
        assertTrue(t.bitrate < after)
    }

    @Test
    fun neverBelowTheFloorNorAboveTheCeiling() {
        val t = tuner()
        var now = 0L
        repeat(40) { t.onTick(now, 500, 9); now += Tuner.CUT_GAP_MS }
        assertEquals(floor, t.bitrate)
        val good = tuner(start = ceiling - 100_000)
        now = 100_000
        repeat(20) { good.onTick(now, 20, 0); now += Tuner.RAISE_GAP_MS }
        assertEquals(ceiling, good.bitrate)
    }

    @Test
    fun climbsBackSlowlyAfterAHoldAndOnlyWhenLatencyIsGood() {
        val t = tuner()
        t.onTick(0, -1, 2) // cut at t=0, hold 3 s
        val cut = t.bitrate
        t.onTick(1_000, 20, 0)
        t.onTick(2_999, 20, 0)
        assertEquals("still holding", cut, t.bitrate)
        t.onTick(3_000, 150, 0) // mediocre latency: no climb
        assertEquals(cut, t.bitrate)
        t.onTick(3_000, 20, 0)
        assertBps(cut + ceiling * 0.05, t.bitrate)
        val raised = t.bitrate
        t.onTick(3_500, 20, 0) // too soon after the last step
        assertEquals(raised, t.bitrate)
        t.onTick(4_000, 20, 0)
        assertTrue(t.bitrate > raised)
    }

    @Test
    fun noFrameLeftTheSenderMeansNoClimb() {
        val t = tuner()
        val b = t.bitrate
        repeat(10) { t.onTick(100_000L + it * 1000, latencyMs = -1, dropped = 0) }
        assertEquals(b, t.bitrate)
    }

    @Test
    fun aCutRightAfterAClimbWaitsLongerBeforeTryingAgain() {
        val t = tuner()
        t.onTick(0, -1, 2) // hold 3 s
        t.onTick(3_000, 20, 0) // climb
        t.onTick(4_000, -1, 2) // the climb broke it: hold doubles to 6 s -> until 10 s
        val low = t.bitrate
        for (s in 5..9) t.onTick(s * 1_000L, 20, 0)
        assertEquals(low, t.bitrate)
        t.onTick(10_000, 20, 0)
        assertTrue(t.bitrate > low)
    }

    @Test
    fun holdResetsAfterALongCalm() {
        val t = tuner()
        t.onTick(0, -1, 2)
        t.onTick(3_000, 20, 0)
        t.onTick(4_000, -1, 2) // hold now 6 s
        t.onTick(200_000, -1, 2) // much later: back to the basic 3 s
        val low = t.bitrate
        t.onTick(203_000, 20, 0)
        assertTrue(t.bitrate > low)
    }

    // ---- the TV's decoder ----------------------------------------------------------------------------

    private fun behind() = Stats(fps = 20, queued = 6, skipped = 0)
    private fun fine() = Stats(fps = 60, queued = 0, skipped = 0)

    @Test
    fun oneBadReportIsNotEnoughButTwoHalveTheFrameRate() {
        val t = tuner()
        t.onRemote(0, behind())
        assertEquals(60, t.fps)
        t.onRemote(1_000, behind())
        assertEquals(30, t.fps)
    }

    @Test
    fun skippedFramesCountAsBeingBehindEvenWithAnEmptyQueue() {
        val t = tuner()
        t.onRemote(0, Stats(30, 0, 4))
        t.onRemote(1_000, Stats(30, 0, 4))
        assertEquals(30, t.fps)
    }

    @Test
    fun aGoodReportInBetweenResetsTheCount() {
        val t = tuner()
        t.onRemote(0, behind())
        t.onRemote(1_000, fine())
        t.onRemote(2_000, behind())
        assertEquals(60, t.fps)
    }

    @Test
    fun theFullRateComesBackAfterAWhileAndTheWaitGrowsEachTime() {
        val t = tuner()
        t.onRemote(0, behind()); t.onRemote(1_000, behind())
        assertEquals(30, t.fps)
        t.onRemote(Tuner.FIRST_RECOVER_MS, fine())
        assertEquals("not yet: 30 s after the change", 30, t.fps)
        t.onRemote(1_000 + Tuner.FIRST_RECOVER_MS, fine())
        assertEquals(60, t.fps)

        // fails again: the next try waits twice as long
        t.onRemote(40_000, behind()); t.onRemote(41_000, behind())
        assertEquals(30, t.fps)
        t.onRemote(41_000 + Tuner.FIRST_RECOVER_MS, fine())
        assertEquals(30, t.fps)
        t.onRemote(41_000 + 2 * Tuner.FIRST_RECOVER_MS, fine())
        assertEquals(60, t.fps)
    }

    @Test
    fun stillBehindAtTheLowerRateMeansASmallerPicture() {
        val t = tuner()
        t.onRemote(0, behind()); t.onRemote(1_000, behind())
        assertEquals(30, t.fps)
        val before = t.bitrate
        t.onRemote(2_000, behind()); t.onRemote(3_000, behind())
        assertEquals(30, t.fps)
        assertEquals((before * 0.85).toInt(), t.bitrate)
    }

    @Test
    fun aPhoneCappedAt30NeverDropsBelowIt() {
        val t = tuner(maxFps = 30)
        repeat(6) { t.onRemote(it * 1_000L, behind()) }
        assertEquals(30, t.fps)
        assertFalse("bitrate eases off instead", t.bitrate == 18_000_000)
    }
}
