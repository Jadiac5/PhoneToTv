package com.phonestream.app.send

import com.phonestream.app.core.FrameAssembler
import com.phonestream.app.core.Msg
import com.phonestream.app.core.Packet
import com.phonestream.app.core.PacketStream
import com.phonestream.app.core.Proto
import com.phonestream.app.core.VideoFrame
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class PacketWriterTest {
    /**
     * An OutputStream the test can hold shut, so packets pile up in the writer's queues like on a slow network.
     * Every packet (one write call) needs a permit; [open] hands out plenty.
     */
    private class GatedOutput : OutputStream() {
        private val permits = Semaphore(0)
        private val sink = ByteArrayOutputStream()

        fun open() = permits.release(1_000_000)
        fun allow(n: Int) = permits.release(n)

        override fun write(b: Int) {
            permits.acquire()
            synchronized(sink) { sink.write(b) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            permits.acquire()
            synchronized(sink) { sink.write(b, off, len) }
        }

        fun packets(): List<Packet> {
            val bytes = synchronized(sink) { sink.toByteArray() }
            val ps = PacketStream(ByteArrayInputStream(bytes), ByteArrayOutputStream())
            val out = ArrayList<Packet>()
            try {
                while (true) out += ps.read()
            } catch (_: Exception) {
            }
            return out
        }
    }

    private lateinit var out: GatedOutput
    private lateinit var writer: PacketWriter

    @Volatile
    private var now = 0L

    @Volatile
    private var keyRequests = 0

    @Before
    fun setUp() {
        out = GatedOutput()
        writer = PacketWriter(
            PacketStream(ByteArrayInputStream(ByteArray(0)), out),
            onError = { throw AssertionError("unexpected write error: $it") },
            onNeedKeyFrame = { keyRequests++ },
            clock = { now },
        )
        writer.start()
    }

    @After
    fun tearDown() {
        out.open()
        writer.close(null, 1000)
    }

    /** Sends a heartbeat and waits until the writer thread has picked it up (and is therefore stuck in the gate). */
    private fun jamWriter() {
        writer.sendControl(Proto.T_HEARTBEAT)
        Thread.sleep(150)
    }

    private fun awaitPackets(n: Int): List<Packet> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val p = out.packets()
            if (p.size >= n) return p
            Thread.sleep(10)
        }
        throw AssertionError("expected $n packets, got ${out.packets().size}")
    }

    /** Waits for [n] packets, gives stragglers a moment to show up, and returns everything that was written. */
    private fun settled(n: Int): List<Packet> {
        awaitPackets(n)
        Thread.sleep(200)
        return out.packets()
    }

    /** Puts the slices of every frame in [packets] back together, like the receiver does. */
    private fun frames(packets: List<Packet>): List<VideoFrame> {
        val assembler = FrameAssembler()
        return packets.filter { it.type == Proto.T_VIDEO_PART }.mapNotNull { assembler.add(it.payload) }
    }

    @Test
    fun controlBeatsAudioBeatsVideo() {
        jamWriter()
        val g = writer.newGeneration()
        writer.sendVideo(g, true, 1, byteArrayOf(1))
        writer.sendAudio(Msg.audioData(2, ByteArray(8)))
        writer.sendControl(Proto.T_QUERY, ByteArray(0))
        out.open()

        val types = awaitPackets(4).map { it.type }
        assertEquals(listOf(Proto.T_HEARTBEAT, Proto.T_QUERY, Proto.T_AUDIO_DATA, Proto.T_VIDEO_PART), types)
    }

    @Test
    fun aBigFrameTravelsInSlicesAndComesOutIdentical() {
        val data = ByteArray(Proto.VIDEO_SLICE * 3 + 123) { (it * 13).toByte() }
        val g = writer.newGeneration()
        writer.sendVideo(g, true, 777, data)
        out.open()

        val packets = settled(4)
        assertEquals(4, packets.count { it.type == Proto.T_VIDEO_PART })
        assertTrue("no slice may exceed the slice size", packets.all { it.payload.size <= Proto.VIDEO_SLICE + 13 })
        val f = frames(packets).single()
        assertTrue(f.key)
        assertEquals(777L, f.ptsUs)
        assertArrayEquals(data, f.data)
    }

    @Test
    fun soundSlipsInBetweenTheSlicesOfAKeyFrame() {
        val data = ByteArray(Proto.VIDEO_SLICE * 3) { 5 }
        val g = writer.newGeneration()
        writer.sendVideo(g, true, 1, data)
        Thread.sleep(150) // the writer has taken the first slice and waits for the network
        writer.sendAudio(Msg.audioData(2, ByteArray(16)))
        out.allow(1) // first slice leaves...
        Thread.sleep(150) // ...and the audio that arrived meanwhile must go next, before slice two
        out.open()

        val types = settled(4).map { it.type }
        assertEquals(listOf(Proto.T_VIDEO_PART, Proto.T_AUDIO_DATA, Proto.T_VIDEO_PART, Proto.T_VIDEO_PART), types)
        assertArrayEquals(data, frames(out.packets()).single().data)
    }

    @Test
    fun staleVideoIsDroppedAndStreamResumesAtTheNextKeyFrame() {
        jamWriter()
        val g = writer.newGeneration()
        now = 0
        writer.sendVideo(g, true, 10, byteArrayOf(1))
        writer.sendVideo(g, false, 11, byteArrayOf(2))
        assertEquals(0, writer.videoDropped.get())

        now = PacketWriter.MAX_VIDEO_AGE_MS + 100 // the network has not kept up
        writer.sendVideo(g, false, 12, byteArrayOf(3)) // backlog (2 frames) + this delta are dropped
        assertEquals(3, writer.videoDropped.get())
        assertEquals(1, keyRequests)

        writer.sendVideo(g, false, 13, byteArrayOf(4)) // still waiting for a key frame
        assertEquals(4, writer.videoDropped.get())

        writer.sendVideo(g, true, 14, byteArrayOf(5)) // resync
        writer.sendVideo(g, false, 15, byteArrayOf(6)) // deltas after it flow normally
        out.open()

        val packets = settled(3)
        val frames = frames(packets)
        assertEquals(listOf(14L, 15L), frames.map { it.ptsUs })
        assertTrue(frames[0].key)
        assertEquals(3, packets.size)
    }

    @Test
    fun aLateBacklogJumpsToTheNewestKeyFrameInsteadOfDroppingEverything() {
        jamWriter()
        val g = writer.newGeneration()
        now = 0
        writer.sendVideo(g, true, 1, byteArrayOf(1))
        writer.sendVideo(g, false, 2, byteArrayOf(2))
        writer.sendVideo(g, true, 3, byteArrayOf(3)) // a newer key frame is already waiting
        writer.sendVideo(g, false, 4, byteArrayOf(4))

        now = PacketWriter.MAX_VIDEO_AGE_MS + 100
        writer.sendVideo(g, false, 5, byteArrayOf(5))
        assertEquals("only what came before the newest key frame is dropped", 2, writer.videoDropped.get())
        assertEquals("no need to ask the encoder for a new key frame", 0, keyRequests)
        out.open()

        val f = frames(settled(4))
        assertEquals(listOf(3L, 4L, 5L), f.map { it.ptsUs })
        assertTrue(f[0].key)
    }

    @Test
    fun framesFromAReplacedEncoderAreDiscarded() {
        jamWriter()
        val old = writer.newGeneration()
        writer.sendVideo(old, true, 1, byteArrayOf(1)) // queued
        val fresh = writer.newGeneration() // new encoder: queued frame is gone
        writer.sendVideo(old, true, 2, byteArrayOf(2)) // late frame from the old encoder: ignored
        writer.sendVideo(fresh, true, 3, byteArrayOf(3))
        out.open()

        assertEquals(listOf(3L), frames(settled(2)).map { it.ptsUs })
    }

    @Test
    fun audioBacklogIsCappedAndNeverReordered() {
        jamWriter()
        repeat(30) { writer.sendAudio(Msg.audioData(it.toLong(), ByteArray(16))) }
        assertEquals(30 - PacketWriter.MAX_AUDIO_QUEUE, writer.audioDropped.get())
        out.open()

        val pts = settled(1 + PacketWriter.MAX_AUDIO_QUEUE)
            .filter { it.type == Proto.T_AUDIO_DATA }.map { Msg.parseAudioData(it.payload).ptsUs }
        assertEquals((5L until 30L).toList(), pts) // oldest 5 dropped, rest in order
    }

    @Test
    fun reportsHowLongTheWorstFrameWaitedAndThenResets() {
        jamWriter()
        val g = writer.newGeneration()
        now = 1_000
        writer.sendVideo(g, true, 1, byteArrayOf(1))
        now = 1_120 // it sits in the queue for 120 ms before the network takes it
        out.open()
        settled(2)
        assertEquals(120, writer.takeLatencyMax())
        assertEquals(-1, writer.takeLatencyMax())
    }

    @Test
    fun countsEveryByteItWrites() {
        out.open()
        writer.sendControl(Proto.T_HEARTBEAT) // 5 header bytes
        writer.sendAudio(Msg.audioData(1, ByteArray(100))) // 5 + 8 + 100
        settled(2)
        assertEquals(5L + 113L, writer.bytesSent.get())
    }

    @Test
    fun closeStillDeliversTheGoodbye() {
        out.open()
        writer.sendControl(Proto.T_HEARTBEAT)
        writer.close(Msg.bye("all done"), 2000)

        val last = out.packets().last()
        assertEquals(Proto.T_BYE, last.type)
        assertEquals("all done", Msg.parseBye(last.payload))
    }
}
