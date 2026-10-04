package com.phonestream.app.receive

import com.phonestream.app.core.AudioConfig
import com.phonestream.app.core.AudioData
import com.phonestream.app.core.Msg
import com.phonestream.app.core.Packet
import com.phonestream.app.core.PacketStream
import com.phonestream.app.core.Proto
import com.phonestream.app.core.Query
import com.phonestream.app.core.Stats
import com.phonestream.app.core.VideoConfig
import com.phonestream.app.core.VideoFrame
import com.phonestream.app.send.PacketWriter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReceiverServerTest {
    private class FakeVideo : VideoSink {
        val configs = CopyOnWriteArrayList<VideoConfig?>()
        val frames = LinkedBlockingQueue<VideoFrame>()
        override fun configure(cfg: VideoConfig?) {
            configs.add(cfg)
        }

        override fun feed(f: VideoFrame) {
            frames.put(f)
        }

        /** What the "decoder" reports; the server forwards it to the sender once a second. */
        val stats = AtomicReference<Stats?>(null)
        override fun takeStats(): Stats? = stats.getAndSet(null)
    }

    private class FakeAudio : AudioSink {
        val started = CopyOnWriteArrayList<AudioConfig>()
        val chunks = LinkedBlockingQueue<AudioData>()

        @Volatile
        var stopCount = 0
        override fun start(cfg: AudioConfig) {
            started.add(cfg)
        }

        override fun feed(d: AudioData) {
            chunks.put(d)
        }

        override fun stop() {
            stopCount++
        }
    }

    private class Events : ReceiverServer.Listener {
        val q = LinkedBlockingQueue<String>()
        val listening = CountDownLatch(1)
        override fun onListening() {
            listening.countDown()
        }

        override fun onServerError(message: String) {
            q.put("error:$message")
        }

        override fun onSessionStarted(senderName: String) {
            q.put("started:$senderName")
        }

        override fun onVideoSize(width: Int, height: Int) {
            q.put("size:${width}x$height")
        }

        override fun onSessionEnded(reason: String?) {
            q.put("ended:$reason")
        }

        fun next(): String = q.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no event within 5 s")
    }

    /** A minimal sender: a socket with the PhoneStream framing on top. */
    private class Client(port: Int) {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000; tcpNoDelay = true }
        val ps = PacketStream(socket.getInputStream(), socket.getOutputStream())

        fun readUntil(type: Int): Packet {
            while (true) {
                val p = ps.read()
                if (p.type == type) return p
            }
        }

        fun handshake(name: String) = Msg.parseHelloAck(run {
            ps.write(Proto.T_HELLO, Msg.hello(name))
            readUntil(Proto.T_HELLO_ACK).payload
        })

        fun close() = try { socket.close() } catch (_: Exception) {}
    }

    private val video = FakeVideo()
    private val audio = FakeAudio()
    private val events = Events()
    private lateinit var server: ReceiverServer
    private val clients = ArrayList<Client>()

    @Before
    fun setUp() {
        server = ReceiverServer("Living room TV", video, audio, events, port = 0, canDecode = { q: Query -> q.width <= 3840 })
        server.start()
        assertTrue("server did not start", events.listening.await(5, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.stop()
    }

    private fun connect(): Client = Client(server.localPort).also { clients += it }

    @Test
    fun acceptsASenderAndDeliversVideoAudioAndAnswersQueries() {
        val c = connect()
        val ack = c.handshake("Pixel 9")
        assertTrue(ack.accepted)
        assertEquals("Living room TV", ack.name)
        assertEquals("started:Pixel 9", events.next())

        val cfg = VideoConfig(Proto.MIME_AVC, 1920, 1080, 60)
        c.ps.write(Proto.T_VIDEO_CONFIG, Msg.videoConfig(cfg))
        assertEquals("size:1920x1080", events.next())
        assertEquals(cfg, video.configs.last())

        val payload = ByteArray(5000) { (it * 7).toByte() }
        c.ps.write(Proto.T_VIDEO_FRAME, Msg.videoFrame(true, 42L, payload))
        val frame = video.frames.poll(5, TimeUnit.SECONDS)
        assertNotNull(frame)
        assertTrue(frame!!.key)
        assertEquals(42L, frame.ptsUs)
        assertArrayEquals(payload, frame.data.copyOfRange(frame.offset, frame.data.size))

        c.ps.write(Proto.T_AUDIO_CONFIG, Msg.audioConfig(AudioConfig(48000, 2)))
        c.ps.write(Proto.T_AUDIO_DATA, Msg.audioData(7L, ByteArray(3840) { 1 }))
        val chunk = audio.chunks.poll(5, TimeUnit.SECONDS)
        assertNotNull(chunk)
        assertEquals(3840, chunk!!.length)
        assertEquals(AudioConfig(48000, 2), audio.started.single())

        // capability questions
        c.ps.write(Proto.T_QUERY, Msg.query(Query(7, Proto.MIME_AVC, 3840, 2160, 60)))
        assertEquals(7 to true, Msg.parseQueryReply(c.readUntil(Proto.T_QUERY_REPLY).payload))
        c.ps.write(Proto.T_QUERY, Msg.query(Query(8, Proto.MIME_AVC, 7680, 4320, 60)))
        assertEquals(8 to false, Msg.parseQueryReply(c.readUntil(Proto.T_QUERY_REPLY).payload))

        // the receiver keeps the sender's read timeout alive
        c.readUntil(Proto.T_HEARTBEAT)

        c.ps.write(Proto.T_BYE, Msg.bye("done"))
        assertEquals("ended:done", events.next())
        assertNull(video.configs.last())
        assertEquals(1, audio.stopCount)
    }

    @Test
    fun aSecondSenderIsToldTheReceiverIsBusy() {
        val first = connect()
        assertTrue(first.handshake("Pixel").accepted)
        assertEquals("started:Pixel", events.next())

        val second = connect()
        val ack = second.handshake("Galaxy")
        assertFalse(ack.accepted)
        assertTrue(ack.reason, ack.reason.contains("Busy") && ack.reason.contains("Pixel"))

        // the first session is unaffected
        first.ps.write(Proto.T_QUERY, Msg.query(Query(1, Proto.MIME_AVC, 1920, 1080, 30)))
        assertEquals(1 to true, Msg.parseQueryReply(first.readUntil(Proto.T_QUERY_REPLY).payload))
    }

    @Test
    fun receiverCanHangUpAndIsFreeAgainAfterwards() {
        val c = connect()
        assertTrue(c.handshake("Pixel").accepted)
        assertEquals("started:Pixel", events.next())

        server.disconnect()
        val bye = c.readUntil(Proto.T_BYE)
        assertTrue(Msg.parseBye(bye.payload).isNotEmpty())
        assertEquals("ended:null", events.next()) // the user chose this: no error message

        val again = connect()
        assertTrue(again.handshake("Pixel").accepted)
        assertEquals("started:Pixel", events.next())
    }

    @Test
    fun aDroppedConnectionEndsTheSessionWithAnError() {
        val c = connect()
        assertTrue(c.handshake("Pixel").accepted)
        assertEquals("started:Pixel", events.next())
        c.close()
        val ended = events.next()
        assertTrue(ended, ended.startsWith("ended:") && ended != "ended:null")
        assertNull(video.configs.last())
    }

    @Test
    fun strangersAreIgnoredAndDoNotBlockRealSenders() {
        Socket("127.0.0.1", server.localPort).use {
            it.getOutputStream().write("GET / HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            it.soTimeout = 5000
            assertEquals(-1, it.getInputStream().read()) // closed on us
        }
        val c = connect()
        assertTrue(c.handshake("Pixel").accepted)
    }

    @Test
    fun theRealPacketWriterTalksToTheServer() {
        val c = connect()
        assertTrue(c.handshake("Pixel").accepted)
        assertEquals("started:Pixel", events.next())

        val w = PacketWriter(c.ps, onError = { throw AssertionError(it) }, onNeedKeyFrame = {}, clock = { System.nanoTime() / 1_000_000 })
        w.start()
        val gen = w.newGeneration()
        w.sendControl(Proto.T_VIDEO_CONFIG, Msg.videoConfig(VideoConfig(Proto.MIME_HEVC, 2560, 1440, 30)))
        for (i in 0 until 20) w.sendVideo(gen, i == 0, i * 33_000L, ByteArray(2000) { (i + it).toByte() })

        assertEquals("size:2560x1440", events.next())
        var expected = 0
        while (expected < 20) {
            val f = video.frames.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("frame $expected never arrived")
            assertEquals(expected == 0, f.key)
            assertEquals(expected * 33_000L, f.ptsUs)
            assertEquals(ByteArray(2000) { (expected + it).toByte() }.toList(), f.data.copyOfRange(f.offset, f.data.size).toList())
            expected++
        }
        w.close(Msg.bye("finished"), 1000)
        assertEquals("ended:finished", events.next())
    }

    @Test
    fun aSenderOnAnOlderProtocolIsTurnedAwayWithAHelpfulReason() {
        val c = connect()
        val hello = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(Proto.MAGIC); writeShort(1); writeUTF("Old phone") }
        }.toByteArray()
        c.ps.write(Proto.T_HELLO, hello)
        val ack = Msg.parseHelloAck(c.readUntil(Proto.T_HELLO_ACK).payload)
        assertFalse(ack.accepted)
        assertTrue(ack.reason, ack.reason.contains("Update"))
        assertEquals("Living room TV", ack.name)
        assertEquals(Proto.VERSION, ack.version) // so the phone can tell which side is the old one

        // the refusal does not keep the TV busy
        val c2 = connect()
        assertTrue(c2.handshake("New phone").accepted)
        assertEquals("started:New phone", events.next())
    }

    @Test
    fun theDecodersStatsReachTheSender() {
        val c = connect()
        assertTrue(c.handshake("Pixel").accepted)
        assertEquals("started:Pixel", events.next())

        video.stats.set(Stats(fps = 24, queued = 5, skipped = 2))
        val stats = Msg.parseStats(c.readUntil(Proto.T_STATS).payload)
        assertEquals(Stats(24, 5, 2), stats)
    }

    @Test
    fun framesSurviveAudioBeingInterleavedBetweenTheirSlices() {
        val c = connect()
        assertTrue(c.handshake("Pixel").accepted)
        assertEquals("started:Pixel", events.next())
        c.ps.write(Proto.T_VIDEO_CONFIG, Msg.videoConfig(VideoConfig(Proto.MIME_AVC, 1920, 1080, 60)))
        assertEquals("size:1920x1080", events.next())
        c.ps.write(Proto.T_AUDIO_CONFIG, Msg.audioConfig(AudioConfig(48000, 2)))

        val key = ByteArray(Proto.VIDEO_SLICE * 2 + 500) { (it * 3).toByte() }
        val delta = ByteArray(700) { (it + 1).toByte() }
        fun slice(data: ByteArray, isKey: Boolean, pts: Long, n: Int): ByteArray {
            val off = n * Proto.VIDEO_SLICE
            return Msg.videoPart(isKey, pts, data, off, minOf(Proto.VIDEO_SLICE, data.size - off))
        }
        c.ps.write(Proto.T_VIDEO_PART, slice(key, true, 1_000, 0))
        c.ps.write(Proto.T_AUDIO_DATA, Msg.audioData(1, ByteArray(3840) { 1 }))
        c.ps.write(Proto.T_VIDEO_PART, slice(key, true, 1_000, 1))
        c.ps.write(Proto.T_HEARTBEAT)
        c.ps.write(Proto.T_AUDIO_DATA, Msg.audioData(2, ByteArray(3840) { 2 }))
        c.ps.write(Proto.T_VIDEO_PART, slice(key, true, 1_000, 2))
        c.ps.write(Proto.T_VIDEO_PART, slice(delta, false, 34_000, 0))

        val first = video.frames.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("key frame never arrived")
        assertTrue(first.key)
        assertEquals(1_000L, first.ptsUs)
        assertArrayEquals(key, first.data.copyOfRange(first.offset, first.data.size))
        val second = video.frames.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("delta frame never arrived")
        assertFalse(second.key)
        assertEquals(34_000L, second.ptsUs)
        assertArrayEquals(delta, second.data.copyOfRange(second.offset, second.data.size))

        assertEquals(2, (0 until 2).count { audio.chunks.poll(5, TimeUnit.SECONDS) != null })
    }

    @Test
    fun aBrokenSliceEndsTheSessionInsteadOfFeedingGarbageToTheDecoder() {
        val c = connect()
        assertTrue(c.handshake("Pixel").accepted)
        assertEquals("started:Pixel", events.next())
        c.ps.write(Proto.T_VIDEO_PART, ByteArray(0)) // an empty slice cannot be valid
        val ended = events.next()
        assertTrue(ended, ended.startsWith("ended:") && ended != "ended:null")
        assertTrue(video.frames.isEmpty())
    }
}
