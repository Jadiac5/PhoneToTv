package com.phonestream.app.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L // ns per ms

class PlayoutClockTest {
    private var now = 10_000L * MS
    private val clock = PlayoutClock { now }

    private fun us(ms: Long) = ms * 1_000

    @Test
    fun withoutAnyInformationFramesAreDueAtOnce() {
        assertEquals(now, clock.dueNs(us(5_000)))
        assertFalse(clock.followsAudio())
    }

    @Test
    fun theSoundDecidesWhenThePictureIsDue() {
        // the sound with sender time 1000 ms is audible at local 5000 ms
        clock.onAudioHeard(us(1_000), 5_000 * MS)
        assertTrue(clock.followsAudio())
        assertEquals(5_100 * MS, clock.dueNs(us(1_100)))
        assertEquals(4_900 * MS, clock.dueNs(us(900)))
    }

    @Test
    fun theUserOffsetMovesThePictureAgainstTheSound() {
        clock.onAudioHeard(us(1_000), 5_000 * MS)
        clock.syncOffsetMs = 120
        assertEquals(5_220 * MS, clock.dueNs(us(1_100)))
        clock.syncOffsetMs = -50
        assertEquals(5_050 * MS, clock.dueNs(us(1_100)))
    }

    @Test
    fun smallChangesAreSmoothedAndBigOnesFollowAtOnce() {
        clock.onAudioHeard(us(1_000), 5_000 * MS)
        clock.onAudioHeard(us(1_000), 5_020 * MS) // 20 ms later than before: noise, only a little of it is taken over
        val moved = clock.dueNs(us(1_000)) - 5_000 * MS
        assertTrue("moved $moved ns", moved in 1..(20 * MS / 8))
        clock.onAudioHeard(us(1_000), 5_200 * MS) // the sound was re-buffered: 200 ms, the picture goes with it
        assertEquals(5_200 * MS, clock.dueNs(us(1_000)))
    }

    @Test
    fun jitterInTheSoundReportsAveragesOut() {
        val rnd = Random(7)
        repeat(300) {
            now += 20 * MS
            val noise = (rnd.nextInt(21) - 10) * MS // +-10 ms
            clock.onAudioHeard(us(1_000 + it * 20L), now + 80 * MS + noise)
        }
        val offset = clock.dueNs(us(1_000 + 299 * 20L)) - (now + 80 * MS)
        assertTrue("off by ${offset / MS} ms", abs(offset) < 3 * MS)
    }

    @Test
    fun silentSoundHandsOverToThePicturesOwnClock() {
        clock.onAudioHeard(us(1_000), 5_000 * MS)
        val t0 = now
        for (i in 0 until 10) clock.onVideoArrival(us(1_000 + i * 17L), t0 + i * 17 * MS)
        now += PlayoutClock.STALE_NS + MS
        assertFalse(clock.followsAudio())
        // every one of those frames took (t0 - 1000 ms) from capture to arrival; the first is due a jitter buffer after t0
        assertEquals(t0 + PlayoutClock.MIN_JITTER_NS, clock.dueNs(us(1_000)))
    }

    @Test
    fun stoppingTheSoundMeansThePictureIsOnItsOwn() {
        clock.onAudioHeard(us(1_000), 5_000 * MS)
        clock.onAudioStopped()
        assertFalse(clock.followsAudio())
    }

    @Test
    fun withoutSoundEveryFrameIsShownAFixedTimeAfterItsCapture() {
        // frames arrive 17 ms after their capture, on a steady link
        for (i in 0 until 120) {
            now += 17 * MS
            clock.onVideoArrival(us(i * 17L), now)
        }
        val pts = us(119 * 17L)
        assertEquals(now + PlayoutClock.MIN_JITTER_NS, clock.dueNs(pts))
        // the next frame, 17 ms of picture later, is due 17 ms after that
        assertEquals(clock.dueNs(pts) + 17 * MS, clock.dueNs(pts + us(17)))
    }

    @Test
    fun aLateFrameWidensTheJitterBufferAtOnceAndItShrinksAgainSlowly() {
        val t0 = now
        var prevArrival = t0
        // arrival of frame i: on a 17 ms grid, unless the link was stalled (then the backlog comes back to back)
        fun arrive(i: Int, extraMs: Long = 0) {
            prevArrival = max(prevArrival + MS, t0 + (i + 1) * 17 * MS + extraMs * MS)
            now = prevArrival
            clock.onVideoArrival(us(i * 17L), now)
        }
        val probe = us(1_000)
        for (i in 0 until 60) arrive(i)
        val steady = clock.dueNs(probe)
        arrive(60, 200) // a frame 200 ms late
        val widened = clock.dueNs(probe)
        assertTrue("grew by ${(widened - steady) / MS} ms", widened - steady in 150 * MS..200 * MS)
        for (i in 61 until 61 + 470) arrive(i) // 8 s of calm: the stall is forgotten after 3 s, then the buffer shrinks
        val calmer = clock.dueNs(probe)
        assertTrue("shrank by ${(widened - calmer) / MS} ms", widened - calmer in 10 * MS..60 * MS)
        assertTrue("still wider than before the stall", calmer > steady)
    }

    @Test
    fun repeatedFramesCarryNoInformationAboutTheNetwork() {
        for (i in 0 until 30) {
            now += 17 * MS
            clock.onVideoArrival(us(i * 17L), now)
        }
        val due = clock.dueNs(us(30 * 17L))
        now += 100 * MS
        clock.onVideoArrival(us(29 * 17L), now) // the encoder repeats the last picture: old time, new arrival
        assertEquals(due, clock.dueNs(us(30 * 17L)))
    }

    @Test
    fun timestampsOfTheTwoStreamsThatDisagreeWildlyAreNotTrusted() {
        for (i in 0 until 30) {
            now += 17 * MS
            clock.onVideoArrival(us(i * 17L), now)
        }
        val own = clock.dueNs(us(29 * 17L))
        // the "sound" claims the picture should be shown 3 seconds from now: that is not one shared clock
        clock.onAudioHeard(us(29 * 17L), now + 3_000 * MS)
        assertEquals(own, clock.dueNs(us(29 * 17L)))
    }

    @Test
    fun theUserOffsetDoesNotCountAsDisagreement() {
        for (i in 0 until 30) {
            now += 17 * MS
            clock.onVideoArrival(us(i * 17L), now)
        }
        clock.onAudioHeard(us(29 * 17L), now + 150 * MS)
        clock.syncOffsetMs = 600
        assertEquals(now + 750 * MS, clock.dueNs(us(29 * 17L)))
    }

    @Test
    fun aNewStreamForgetsTheOldOnesTimestamps() {
        for (i in 0 until 30) {
            now += 17 * MS
            clock.onVideoArrival(us(10_000_000L + i * 17L), now)
        }
        clock.resetVideo()
        // another phone, another time base
        now += 17 * MS
        clock.onVideoArrival(us(55L), now)
        assertEquals(now + PlayoutClock.MIN_JITTER_NS, clock.dueNs(us(55L)))
    }

    @Test
    fun theLocalClockMayStartAnywhereEvenNegative() {
        now = -3_000L * MS
        for (i in 0 until 30) {
            now += 17 * MS
            clock.onVideoArrival(us(i * 17L), now)
        }
        assertEquals(now + PlayoutClock.MIN_JITTER_NS, clock.dueNs(us(29 * 17L)))
    }

    /**
     * The point of it all, with sound: frames that the network delivers unevenly (up to 40 ms late, in order, as
     * TCP does) come out at an exactly even pace, and each is there before its time because the sound's own delay
     * on the TV (here 120 ms) is the jitter buffer.
     */
    @Test
    fun unevenDeliveryBecomesEvenPlaybackWithSoundAsTheReference() {
        val rnd = Random(42)
        val interval = 16_667L // us, 60 fps
        val start = now
        val transit = 30 * MS // the fastest the network gets a frame across, plus the sender's encode time
        // the sound that was captured when the stream started is heard 120 ms after a frame of that time arrives
        clock.onAudioHeard(0, start + transit + 120 * MS)
        var prevArrival = start
        var prevDue = 0L
        for (k in 1..600) {
            val pts = k * interval
            val arrival = max(prevArrival, start + pts * 1_000 + transit + rnd.nextInt(41) * MS)
            prevArrival = arrival
            val due = clock.dueNs(pts)
            assertTrue("frame $k: ${(due - arrival) / MS} ms before its time", due - arrival >= 80 * MS)
            if (k > 1) assertEquals(interval * 1_000, due - prevDue)
            prevDue = due
        }
    }

    /** The same traffic without sound: the picture's own jitter buffer must make every frame early enough. */
    @Test
    fun unevenDeliveryBecomesEvenPlaybackOnItsOwnToo() {
        val rnd = Random(3)
        val interval = 16_667L
        val start = now
        val transit = 30 * MS
        var prevArrival = start
        var prevDue = 0L
        for (k in 1..600) {
            val pts = k * interval
            val arrival = max(prevArrival, start + pts * 1_000 + transit + rnd.nextInt(41) * MS)
            prevArrival = arrival
            now = arrival
            clock.onVideoArrival(pts, arrival)
            val due = clock.dueNs(pts)
            assertTrue("frame $k: ${(due - arrival) / MS} ms before its time", due - arrival >= 30 * MS)
            if (k > 300) {
                val step = due - prevDue
                assertTrue("frame $k: step ${step / 1_000} us", abs(step - interval * 1_000) < 8 * MS)
            }
            prevDue = due
        }
    }
}

class PacingTest {
    @Test
    fun aFrameGoesIntoTheDecoderJustBeforeItIsDue() {
        assertEquals(1_000 * MS - Pacing.DECODE_LEAD_NS, Pacing.submitAtNs(1_000 * MS, 900 * MS))
    }

    @Test
    fun aFrameDueFarAheadIsNotHeldForeverAndLateOnesGoAtOnce() {
        val now = 5_000 * MS
        assertEquals(now, Pacing.submitAtNs(now + Pacing.MAX_HOLD_NS + 1, now))
        assertTrue(Pacing.submitAtNs(now - 300 * MS, now) < now) // overdue: its "submit time" has long passed
    }

    @Test
    fun theDisplayTimeStaysCloseToNow() {
        val now = 5_000 * MS
        assertEquals(now, Pacing.renderAtNs(now - 90 * MS, now))
        assertEquals(now + 25 * MS, Pacing.renderAtNs(now + 25 * MS, now))
        assertEquals(now + Pacing.MAX_RENDER_AHEAD_NS, Pacing.renderAtNs(now + 500 * MS, now))
    }

    @Test
    fun nonsenseDueTimesMeanShowAtOnce() {
        val now = 5_000 * MS
        assertEquals(now, Pacing.plausibleDue(0, now)) // a decoder that lost the timestamp
        assertEquals(now, Pacing.plausibleDue(now + 5_000 * MS, now))
        assertEquals(now - 200 * MS, Pacing.plausibleDue(now - 200 * MS, now))
    }

    @Test
    fun staleFramesAreShownAtMostEverySoOften() {
        val now = 5_000 * MS
        val stale = now - Pacing.STALE_NS - MS
        assertTrue("on time", Pacing.showFrame(now, now, now))
        assertTrue("a bit late is still shown", Pacing.showFrame(now - Pacing.STALE_NS, now, now))
        assertFalse("stale and the last one was just shown", Pacing.showFrame(stale, now, now - 10 * MS))
        assertTrue("stale but the screen has waited long enough", Pacing.showFrame(stale, now, now - Pacing.STALE_MIN_GAP_NS))
    }

    /**
     * The picture hangs for 300 ms and then the whole backlog arrives at once and is decoded at 250 frames per
     * second. It must not be played at decoder speed ("fast forward"): the stale frames are thinned out to at most
     * one per 33 ms, and the newest frame always makes it.
     */
    @Test
    fun aBurstAfterAStallJumpsAheadInsteadOfFastForwarding() {
        val interval = 16_667L * 1_000 // ns
        val decodeEvery = 4 * MS
        val start = 10_000 * MS
        val shownAt = ArrayList<Long>()
        val shownIdx = ArrayList<Int>()
        var lastShown = 0L
        for (i in 0 until 18) {
            val now = start + 300 * MS + i * decodeEvery
            val due = start + i * interval
            if (Pacing.showFrame(due, now, lastShown)) {
                val at = Pacing.renderAtNs(due, now)
                shownAt += at
                shownIdx += i
                lastShown = at
            }
        }
        assertTrue("some frames were skipped: shown $shownIdx", shownIdx.size < 18)
        assertEquals("the newest frame always makes it", 17, shownIdx.last())
        for (j in 1 until shownAt.size) {
            val frameDue = start + shownIdx[j] * interval
            if (shownAt[j] - frameDue > Pacing.STALE_NS) {
                assertTrue("gap ${(shownAt[j] - shownAt[j - 1]) / MS} ms", shownAt[j] - shownAt[j - 1] >= Pacing.STALE_MIN_GAP_NS)
            }
        }
    }

    /**
     * A decoder that can only do 40 frames per second while 60 arrive is permanently behind. While it is, the screen
     * gets at most about 30 pictures per second, each of them the frame that is just out of the decoder (the others
     * count as skipped, which is what makes the sender lower its frame rate).
     */
    @Test
    fun aDecoderThatCannotKeepUpShowsAtMostAboutThirtyFramesPerSecond() {
        val interval = 16_667_000L
        val start = 10_000 * MS
        var lastShown = 0L
        var shown = 0
        for (i in 0 until 100) {
            val now = start + 500 * MS + i * 25 * MS
            val due = start + i * interval
            if (Pacing.showFrame(due, now, lastShown)) {
                val at = Pacing.renderAtNs(due, now)
                if (shown > 0) assertTrue(at - lastShown >= Pacing.STALE_MIN_GAP_NS)
                lastShown = at
                shown++
            }
        }
        assertTrue("shown $shown of 100", shown in 45..55)
    }
}

class AudioStampTest {
    @Test
    fun steadyReadsAdvanceByExactlyTheChunkDuration() {
        val s = AudioStamp(48_000)
        assertEquals(1_000_000, s.stamp(1_000_000, 960))
        assertEquals(1_020_000, s.stamp(1_020_000, 960))
        assertEquals(1_040_000, s.stamp(1_040_000, 960))
    }

    @Test
    fun readsThatCameBackToBackDoNotMakeTheTimesJitter() {
        val s = AudioStamp(48_000)
        s.stamp(1_000_000, 960)
        // a hiccup: the next read returns 30 ms late, the one after follows immediately
        val a = s.stamp(1_050_000, 960) // plain would be 1_020_000
        val b = s.stamp(1_050_100, 960) // plain would be 1_040_000
        assertTrue("a=$a", a - 1_020_000 in 0..2_000)
        assertTrue("b-a=${b - a}", b - a in 19_000..21_000)
    }

    @Test
    fun aRealJumpOfTheClockIsFollowedAtOnce() {
        val s = AudioStamp(48_000)
        s.stamp(1_000_000, 960)
        assertEquals(2_000_000, s.stamp(2_000_000, 960)) // the recorder stalled for a second
    }

    @Test
    fun aSlightlyFastOrSlowRecorderIsTrackedOverTime() {
        val s = AudioStamp(48_000)
        var now = 1_000_000L
        var last = s.stamp(now, 960)
        repeat(3_000) { // the recorder delivers 20 ms of sound every 20.02 ms of clock time, for a minute
            now += 20_020
            last = s.stamp(now, 960)
        }
        assertTrue("${now - last} us behind", abs(now - last) < 3_000)
    }

    @Test
    fun anEncoderTimeFarFromTheClockIsReplaced() {
        assertEquals(5_000_000, sanePts(5_000_000, 5_000_000))
        assertEquals(4_000_000, sanePts(4_000_000, 5_000_000)) // 1 s off is still believable
        assertEquals(5_000_000, sanePts(1_000_000, 5_000_000)) // 4 s off is not
        assertEquals(5_000_000, sanePts(9_000_000, 5_000_000))
    }
}
