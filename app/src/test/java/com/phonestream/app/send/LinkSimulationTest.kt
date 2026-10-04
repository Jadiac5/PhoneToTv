package com.phonestream.app.send

import com.phonestream.app.core.FrameAssembler
import com.phonestream.app.core.Msg
import com.phonestream.app.core.PacketStream
import com.phonestream.app.core.Proto
import com.phonestream.app.core.VideoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sender's [PacketWriter] pushing a live stream (30 fps video with key frames + 20 ms audio chunks) through a link
 * of limited bandwidth. This is what a weak Wi-Fi does to the stream, minus the radio: what matters is that the sound
 * stays whole and prompt and that the picture stays "live" (frames are dropped, never queued up for seconds).
 */
class LinkSimulationTest {
    private class Arrival(val atNs: Long, val type: Int, val payload: ByteArray)

    /** Every packet needs size / [bytesPerSec] seconds on the "wire"; the writer is blocked meanwhile, like on a full socket. */
    private class SlowLink(private val bytesPerSec: Long) : OutputStream() {
        val arrivals: MutableList<Arrival> = Collections.synchronizedList(ArrayList())
        private var busyUntil = System.nanoTime()

        override fun write(b: Int) = throw UnsupportedOperationException()

        override fun write(b: ByteArray, off: Int, len: Int) {
            // Up to 40 ms of idle capacity can be caught up on, so one late wake-up does not cost bandwidth for good.
            busyUntil = maxOf(busyUntil, System.nanoTime() - 40_000_000L) + len * 1_000_000_000L / bytesPerSec
            val wait = busyUntil - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            arrivals += Arrival(busyUntil, b[off].toInt() and 0xFF, b.copyOfRange(off + 5, off + len))
        }
    }

    private class Result(
        val writer: PacketWriter,
        val audioSentAt: LongArray,
        val videoSentAt: LongArray,
        val audio: List<Pair<Int, Long>>, // chunk index, arrival
        val video: List<Pair<VideoFrame, Long>>, // frame, arrival of its last slice
        val keyRequests: Int,
    )

    private fun sleepUntil(ns: Long) {
        while (true) {
            val d = ns - System.nanoTime()
            if (d <= 0) return
            Thread.sleep(d / 1_000_000, (d % 1_000_000).toInt())
        }
    }

    private fun simulate(bytesPerSec: Long, runMs: Long, keyBytes: Int, deltaBytes: Int): Result {
        val link = SlowLink(bytesPerSec)
        val keyWanted = AtomicBoolean(false)
        var keyRequests = 0
        val writer = PacketWriter(
            PacketStream(ByteArrayInputStream(ByteArray(0)), link),
            onError = { throw AssertionError("write failed: $it") },
            onNeedKeyFrame = { keyRequests++; keyWanted.set(true) }, // what the real encoder does on REQUEST_KEYFRAME
            clock = { System.nanoTime() / 1_000_000 },
        )
        writer.start()
        val gen = writer.newGeneration()

        val audioChunks = (runMs / 20).toInt()
        val videoFrames = (runMs / 33).toInt()
        val audioSentAt = LongArray(audioChunks)
        val videoSentAt = LongArray(videoFrames)
        val t0 = System.nanoTime()

        val audioThread = Thread {
            for (i in 0 until audioChunks) {
                sleepUntil(t0 + i * 20_000_000L)
                audioSentAt[i] = System.nanoTime()
                writer.sendAudio(Msg.audioData(i.toLong(), ByteArray(3840))) // 20 ms of 48 kHz stereo PCM16
            }
        }
        val videoThread = Thread {
            for (i in 0 until videoFrames) {
                sleepUntil(t0 + i * 33_000_000L)
                val key = i % 60 == 0 || keyWanted.getAndSet(false)
                videoSentAt[i] = System.nanoTime()
                writer.sendVideo(gen, key, i.toLong(), ByteArray(if (key) keyBytes else deltaBytes))
            }
        }
        audioThread.start(); videoThread.start()
        audioThread.join(); videoThread.join()

        // let whatever is still queued drain (audio is never dropped except past 500 ms of backlog)
        val deadline = System.nanoTime() + 4_000_000_000L
        while (System.nanoTime() < deadline && link.arrivals.count { it.type == Proto.T_AUDIO_DATA } < audioChunks) Thread.sleep(20)
        writer.close(null, 1000)

        val arrivals = synchronized(link.arrivals) { ArrayList(link.arrivals) }
        val assembler = FrameAssembler()
        val video = ArrayList<Pair<VideoFrame, Long>>()
        val audio = ArrayList<Pair<Int, Long>>()
        for (a in arrivals) {
            when (a.type) {
                Proto.T_AUDIO_DATA -> audio += Msg.parseAudioData(a.payload).ptsUs.toInt() to a.atNs
                Proto.T_VIDEO_PART -> assembler.add(a.payload)?.let { video += it to a.atNs }
            }
        }
        return Result(writer, audioSentAt, videoSentAt, audio, video, keyRequests)
    }

    @Test
    fun anOverloadedLinkKeepsTheSoundWholeAndThePictureLive() {
        // sound alone needs ~193 KB/s; video offers ~100 KB/s on average; the link carries 270 KB/s
        val r = simulate(bytesPerSec = 270_000, runMs = 5_000, keyBytes = 20_000, deltaBytes = 3_000)

        // sound: every chunk, in order, nothing thrown away, never held up for long
        assertEquals("audio chunks lost", 0, r.writer.audioDropped.get())
        assertEquals((0 until r.audioSentAt.size).toList(), r.audio.map { it.first })
        val audioLateMs = r.audio.map { (r.audioSentAt[it.first].let { s -> it.second - s }) / 1_000_000 }
        assertTrue("worst audio delay ${audioLateMs.max()} ms", audioLateMs.max() < 800)
        assertTrue("average audio delay ${audioLateMs.average()} ms", audioLateMs.average() < 250)

        // picture: the link really was too small, so frames had to go...
        assertTrue("overload expected", r.writer.videoDropped.get() > 0)
        assertTrue("encoder was never asked for a key frame", r.keyRequests > 0)
        // ...but plenty still got through, none of them stale, and a delta frame never follows a hole
        assertTrue("only ${r.video.size} frames arrived", r.video.size >= 20)
        val videoLateMs = r.video.map { (f, at) -> (at - r.videoSentAt[f.ptsUs.toInt()]) / 1_000_000 }
        assertTrue("worst video delay ${videoLateMs.max()} ms", videoLateMs.max() < 1_500)
        assertTrue("first frame must be a key frame", r.video.first().first.key)
        var previous = -1L
        for ((f, _) in r.video) {
            if (previous >= 0 && f.ptsUs != previous + 1) assertTrue("delta frame ${f.ptsUs} after a gap", f.key)
            previous = f.ptsUs
        }
    }

    @Test
    fun aLinkWithRoomToSpareDropsNothing() {
        val r = simulate(bytesPerSec = 3_000_000, runMs = 1_500, keyBytes = 20_000, deltaBytes = 3_000)
        assertEquals(0, r.writer.audioDropped.get())
        assertEquals(0, r.writer.videoDropped.get())
        assertEquals(0, r.keyRequests)
        assertEquals((0 until r.audioSentAt.size).toList(), r.audio.map { it.first })
        assertEquals((0 until r.videoSentAt.size).map { it.toLong() }, r.video.map { it.first.ptsUs })
        val videoLateMs = r.video.map { (f, at) -> (at - r.videoSentAt[f.ptsUs.toInt()]) / 1_000_000 }
        assertTrue("video delay ${videoLateMs.max()} ms on an idle link", videoLateMs.max() < 300)
    }
}
