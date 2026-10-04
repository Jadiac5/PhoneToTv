package com.phonestream.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

class ProtoTest {
    @Test
    fun packetsRoundTripOverAStream() {
        val bo = ByteArrayOutputStream()
        val writer = PacketStream(ByteArrayInputStream(ByteArray(0)), bo)
        writer.write(Proto.T_HELLO, Msg.hello("Living room TV"))
        writer.write(Proto.T_HEARTBEAT)
        writer.write(Proto.T_VIDEO_FRAME, Msg.videoFrame(true, 123456789L, byteArrayOf(1, 2, 3, 4, 5)))

        val reader = PacketStream(ByteArrayInputStream(bo.toByteArray()), ByteArrayOutputStream())
        val p1 = reader.read()
        assertEquals(Proto.T_HELLO, p1.type)
        val hello = Msg.parseHello(p1.payload)
        assertEquals("Living room TV", hello.name)
        assertEquals(Proto.VERSION, hello.version)

        val p2 = reader.read()
        assertEquals(Proto.T_HEARTBEAT, p2.type)
        assertEquals(0, p2.payload.size)

        val p3 = reader.read()
        val f = Msg.parseVideoFrame(p3.payload)
        assertTrue(f.key)
        assertEquals(123456789L, f.ptsUs)
        assertEquals(5, f.length)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), f.data.copyOfRange(f.offset, f.data.size))
    }

    @Test
    fun largeFrameSurvivesPipe() {
        val pin = PipedInputStream(1 shl 16)
        val pout = PipedOutputStream(pin)
        val big = ByteArray(3_000_000) { (it % 251).toByte() }
        val t = Thread { PacketStream(ByteArrayInputStream(ByteArray(0)), pout).write(Proto.T_VIDEO_FRAME, Msg.videoFrame(false, 7, big)) }
        t.start()
        val p = PacketStream(pin, ByteArrayOutputStream()).read()
        t.join()
        val f = Msg.parseVideoFrame(p.payload)
        assertFalse(f.key)
        assertEquals(big.size, f.length)
        assertEquals(big[2_999_999], f.data[f.offset + 2_999_999])
    }

    @Test
    fun controlMessagesRoundTrip() {
        val ack = Msg.parseHelloAck(Msg.helloAck(false, "Receiver is busy", "TV"))
        assertFalse(ack.accepted); assertEquals("Receiver is busy", ack.reason); assertEquals("TV", ack.name)
        assertEquals(Proto.VERSION, ack.version)

        val vc = Msg.parseVideoConfig(Msg.videoConfig(VideoConfig(Proto.MIME_HEVC, 1440, 3200, 60)))
        assertEquals(VideoConfig(Proto.MIME_HEVC, 1440, 3200, 60), vc)

        assertEquals(AudioConfig(48000, 2), Msg.parseAudioConfig(Msg.audioConfig(AudioConfig(48000, 2))))

        val q = Query(42, Proto.MIME_AVC, 1080, 2400, 60)
        assertEquals(q, Msg.parseQuery(Msg.query(q)))
        assertEquals(42 to true, Msg.parseQueryReply(Msg.queryReply(42, true)))

        assertEquals("bye", Msg.parseBye(Msg.bye("bye")))

        val a = Msg.parseAudioData(Msg.audioData(99L, byteArrayOf(9, 8, 7, 6), 3))
        assertEquals(99L, a.ptsUs)
        assertEquals(3, a.length)
    }

    @Test
    fun statsRoundTripAndAreClamped() {
        assertEquals(Stats(58, 2, 0), Msg.parseStats(Msg.stats(Stats(58, 2, 0))))
        assertEquals(Stats(65535, 65535, 0), Msg.parseStats(Msg.stats(Stats(1_000_000, 70_000, -5))))
    }

    @Test
    fun aVersion1ReceiverAckHasNoVersionAndIsReadAsVersion1() {
        val v1 = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeBoolean(true); writeUTF(""); writeUTF("Old TV") }
        }.toByteArray()
        val ack = Msg.parseHelloAck(v1)
        assertTrue(ack.accepted)
        assertEquals("Old TV", ack.name)
        assertEquals(1, ack.version)
        assertTrue("sender must refuse a receiver older than itself", ack.version < Proto.VERSION)
    }

    @Test
    fun rejectsForeignPeersButReportsTheirVersion() {
        try {
            Msg.parseHello(ByteArray(20))
            fail("expected IOException")
        } catch (e: IOException) {
            // not a PhoneStream peer
        }
        val other = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(Proto.MAGIC); writeShort(99); writeUTF("x") }
        }.toByteArray()
        // The receiver, not the parser, decides what to do about another version (it answers with an explanation).
        assertEquals(Hello("x", 99), Msg.parseHello(other))
    }

    @Test
    fun rejectsInsaneLengths() {
        val bad = byteArrayOf(4, 0x7F, 0x7F, 0x7F, 0x7F)
        try {
            PacketStream(ByteArrayInputStream(bad), ByteArrayOutputStream()).read()
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun thirdPartyDataNeverEscapesTheNameLimit() {
        val long = "x".repeat(500)
        assertEquals(80, Msg.parseHello(Msg.hello(long)).name.length)
        assertEquals(80, Msg.parseBye(Msg.bye(long)).length)
    }
}

class FrameAssemblerTest {
    private fun slices(key: Boolean, pts: Long, data: ByteArray, size: Int = Proto.VIDEO_SLICE): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var off = 0
        do {
            val len = minOf(size, data.size - off)
            out += Msg.videoPart(key, pts, data, off, len)
            off += len
        } while (off < data.size)
        return out
    }

    private fun assemble(a: FrameAssembler, parts: List<ByteArray>): VideoFrame? {
        var done: VideoFrame? = null
        for (p in parts) a.add(p)?.let { done = it }
        return done
    }

    @Test
    fun aFrameInSeveralSlicesComesOutIdentical() {
        val data = ByteArray(100_000) { (it * 31).toByte() }
        val parts = slices(true, 4_000_000_000L, data)
        assertEquals(7, parts.size) // 100000 / 16384 -> 7 slices
        val f = assemble(FrameAssembler(), parts)!!
        assertTrue(f.key)
        assertEquals(4_000_000_000L, f.ptsUs)
        assertEquals(0, f.offset)
        assertArrayEquals(data, f.data)
    }

    @Test
    fun noFrameIsReportedBeforeItsLastSlice() {
        val a = FrameAssembler()
        val parts = slices(false, 5, ByteArray(40_000) { 1 })
        for (p in parts.dropLast(1)) assertNull(a.add(p))
        assertNotNull(a.add(parts.last()))
    }

    @Test
    fun smallAndEmptyFramesAreASingleSlice() {
        val a = FrameAssembler()
        val small = a.add(Msg.videoPart(false, 9, byteArrayOf(1, 2, 3), 0, 3))!!
        assertFalse(small.key)
        assertEquals(9L, small.ptsUs)
        assertArrayEquals(byteArrayOf(1, 2, 3), small.data)

        val empty = a.add(Msg.videoPart(true, 10, ByteArray(0), 0, 0))!!
        assertEquals(0, empty.length)
    }

    @Test
    fun framesFollowEachOtherWithoutMixingUp() {
        val a = FrameAssembler()
        val one = ByteArray(30_000) { 1 }
        val two = ByteArray(20_000) { 2 }
        val f1 = assemble(a, slices(true, 1, one))!!
        val f2 = assemble(a, slices(false, 2, two))!!
        assertArrayEquals(one, f1.data)
        assertArrayEquals(two, f2.data)
        assertFalse(f2.key)
    }

    @Test
    fun joiningMidFrameIsIgnoredUntilTheNextFirstSlice() {
        val a = FrameAssembler()
        val parts = slices(true, 1, ByteArray(50_000) { 3 })
        assertNull(a.add(parts[1]))
        assertNull(a.add(parts.last()))
        val fresh = assemble(a, slices(true, 2, ByteArray(10_000) { 4 }))
        assertEquals(2L, fresh!!.ptsUs)
    }

    @Test
    fun aLostSliceDropsTheFrameAndTheNextOneIsFine() {
        val a = FrameAssembler()
        val parts = slices(false, 1, ByteArray(50_000) { 3 })
        assertNull(a.add(parts[0]))
        assertNull(a.add(parts.last())) // middle slices never arrived: total does not add up
        val good = assemble(a, slices(true, 2, ByteArray(5_000) { 5 }))
        assertNotNull(good)
        assertEquals(2L, good!!.ptsUs)
    }

    @Test
    fun resetAbandonsAHalfReceivedFrame() {
        val a = FrameAssembler()
        val parts = slices(true, 1, ByteArray(50_000))
        a.add(parts[0])
        a.reset()
        assertNull(a.add(parts[1]))
        assertNull(a.add(parts.last()))
    }

    @Test
    fun brokenSlicesAreProtocolErrors() {
        val a = FrameAssembler()
        try {
            a.add(ByteArray(0)); fail("empty")
        } catch (_: IOException) {
        }
        try {
            a.add(byteArrayOf(Msg.PART_FIRST.toByte(), 0, 0)); fail("short first slice")
        } catch (_: IOException) {
        }
        // announces 10 bytes, delivers 20
        val liar = Msg.videoPart(true, 1, ByteArray(10), 0, 10).also {
            it[1] = 0; it[2] = 0; it[3] = 0; it[4] = 10
        } + ByteArray(10)
        try {
            a.add(liar); fail("overflow")
        } catch (_: IOException) {
        }
    }
}

class PlannerTest {
    private val allSupported = { _: String, _: Int, _: Int, _: Int -> true }

    @Test
    fun nativeKeepsScreenSize() {
        assertEquals(1080 to 2400, Planner.presetSize(1080, 2400, Preset.NATIVE))
        assertEquals(1440 to 3200, Planner.presetSize(1440, 3200, Preset.NATIVE))
    }

    @Test
    fun presetsUseShortSideAndNeverUpscale() {
        // 1440x3200 -> short side 1440 -> 1080p means short side 1080
        assertEquals(1080 to 2400, Planner.presetSize(1440, 3200, Preset.P1080))
        assertEquals(720 to 1600, Planner.presetSize(1440, 3200, Preset.P720))
        assertEquals(480 to 1066, Planner.presetSize(1440, 3200, Preset.P480))
        // phone that is already 1080 wide: 1080p == native, 720p scales down
        assertEquals(1080 to 2400, Planner.presetSize(1080, 2400, Preset.P1080))
        assertEquals(720 to 1600, Planner.presetSize(1080, 2400, Preset.P720))
        // landscape
        assertEquals(1600 to 720, Planner.presetSize(2400, 1080, Preset.P720))
        // small phone is never upscaled
        assertEquals(720 to 1280, Planner.presetSize(720, 1280, Preset.P1080))
    }

    @Test
    fun picksFullSizeSixtyFpsAvcWhenEverythingWorks() {
        val p = Planner.plan(1080, 2400, Preset.NATIVE, supports = allSupported)
        assertEquals(StreamPlan(Proto.MIME_AVC, 1080, 2400, 60), p)
    }

    @Test
    fun prefersFullSizeAt30FpsOverSmallerAt60() {
        val p = Planner.plan(1440, 3200, Preset.NATIVE) { _, w, h, fps -> fps == 30 || w * h <= 1920 * 1080 }
        assertEquals(StreamPlan(Proto.MIME_AVC, 1440, 3200, 30), p)
    }

    @Test
    fun usesHevcWhenOnlyHevcCanDoTheSize() {
        val p = Planner.plan(2160, 3840, Preset.NATIVE) { mime, w, h, _ -> mime == Proto.MIME_HEVC || w * h <= 1920 * 1080 }
        assertEquals(Proto.MIME_HEVC, p!!.mime)
        assertEquals(2160, p.width)
        assertEquals(3840, p.height)
        assertEquals(60, p.fps)
    }

    @Test
    fun shrinksUntilSomethingIsSupported() {
        val p = Planner.plan(1440, 3200, Preset.NATIVE) { _, w, h, _ -> w * h <= 1920 * 1080 }
        assertNotNull(p)
        assertTrue(p!!.width * p.height <= 1920 * 1080)
        assertTrue("should not shrink more than necessary: ${p.width}x${p.height}", p.width * p.height > 1100 * 1900 / 2)
        // aspect ratio preserved within rounding
        assertEquals(1440.0 / 3200.0, p.width.toDouble() / p.height, 0.02)
    }

    @Test
    fun usesAlignedSizeWhenExactSizeIsNotSupported() {
        // encoder insists on multiples of 16
        val p = Planner.plan(1080, 2400, Preset.NATIVE) { _, w, h, _ -> w % 16 == 0 && h % 16 == 0 }
        assertEquals(1088, p!!.width)
        assertEquals(2400, p.height)
    }

    @Test
    fun returnsNullWhenNothingWorks() {
        assertNull(Planner.plan(1080, 2400, Preset.NATIVE) { _, _, _, _ -> false })
    }

    @Test
    fun bitrateIsGenerousButBounded() {
        val mbps = Planner.bitrate(StreamPlan(Proto.MIME_AVC, 1080, 2400, 60)) / 1_000_000.0
        assertEquals(38.9, mbps, 0.5)
        assertEquals(6.0, Planner.bitrate(StreamPlan(Proto.MIME_AVC, 160, 90, 30)) / 1_000_000.0, 0.01)
        assertEquals(120.0, Planner.bitrate(StreamPlan(Proto.MIME_AVC, 3840, 2160, 120)) / 1_000_000.0, 0.01)
    }

    @Test
    fun tunerRangeIsSensibleForBigAndTinyCeilings() {
        assertEquals(17_550_000, Planner.startBitrate(39_000_000))
        assertEquals(3_900_000, Planner.floorBitrate(39_000_000))
        // never above the ceiling, even when the minimums say otherwise
        assertEquals(4_000_000, Planner.startBitrate(4_000_000))
        assertEquals(1_500_000, Planner.floorBitrate(4_000_000))
        assertEquals(1_000_000, Planner.floorBitrate(1_000_000))
        assertEquals(1_000_000, Planner.startBitrate(1_000_000))
    }

    // ---- 16:9 modes ------------------------------------------------------------------------------

    @Test
    fun resolutionIsAlwaysLongSideFirst() {
        assertEquals("2400×1080", resolutionLabel(1080, 2400))
        assertEquals("2400×1080", resolutionLabel(2400, 1080))
        assertEquals("1920×1080", resolutionLabel(1920, 1080))
    }

    @Test
    fun onlyLandscapeScreensThatAreNot16by9AreReshaped() {
        assertTrue(Planner.reshapes(2400, 1080, AspectMode.CROP))
        assertTrue(Planner.reshapes(2400, 1080, AspectMode.STRETCH))
        assertFalse(Planner.reshapes(2400, 1080, AspectMode.ORIGINAL))
        assertFalse("already 16:9", Planner.reshapes(1920, 1080, AspectMode.CROP))
        assertFalse("already 16:9 (rounded)", Planner.reshapes(2560, 1440, AspectMode.STRETCH))
        assertFalse("upright phones keep their shape", Planner.reshapes(1080, 2400, AspectMode.STRETCH))
        assertFalse("square-ish is not landscape", Planner.reshapes(1000, 1000, AspectMode.CROP))
    }

    @Test
    fun wideSizeIsTheBiggest16by9InsideTheScreen() {
        assertEquals(1920 to 1080, Planner.wideSize(2400, 1080)) // too wide: trim sides
        assertEquals(1600 to 900, Planner.wideSize(1600, 1000)) // too tall: trim top/bottom
    }

    @Test
    fun outputSizeFollowsTheModeAndThePreset() {
        assertEquals(2400 to 1080, Planner.outputSize(2400, 1080, Preset.NATIVE, AspectMode.ORIGINAL))
        assertEquals(1920 to 1080, Planner.outputSize(2400, 1080, Preset.NATIVE, AspectMode.CROP))
        assertEquals(1920 to 1080, Planner.outputSize(2400, 1080, Preset.NATIVE, AspectMode.STRETCH))
        assertEquals(1280 to 720, Planner.outputSize(2400, 1080, Preset.P720, AspectMode.CROP))
        assertEquals(1600 to 720, Planner.outputSize(2400, 1080, Preset.P720, AspectMode.ORIGINAL))
        // upright phone: modes change nothing
        assertEquals(1080 to 2400, Planner.outputSize(1080, 2400, Preset.NATIVE, AspectMode.STRETCH))
    }

    @Test
    fun cropPlanCapturesTheWholeScreenAndEncodesTheWidePart() {
        val p = Planner.plan(2400, 1080, Preset.NATIVE, AspectMode.CROP, allSupported)!!
        assertEquals(Fit.CROP, p.fit)
        assertEquals(1920, p.width); assertEquals(1080, p.height)
        assertEquals(2400, p.srcWidth); assertEquals(1080, p.srcHeight)
    }

    @Test
    fun stretchPlanKeepsTheWholeScreenToo() {
        val p = Planner.plan(2400, 1080, Preset.P720, AspectMode.STRETCH, allSupported)!!
        assertEquals(Fit.STRETCH, p.fit)
        assertEquals(1280, p.width); assertEquals(720, p.height)
        assertEquals(1600, p.srcWidth); assertEquals(720, p.srcHeight)
    }

    @Test
    fun originalAndUprightPlansAreSimpleScaling() {
        val a = Planner.plan(2400, 1080, Preset.NATIVE, AspectMode.ORIGINAL, allSupported)!!
        assertEquals(Fit.SCALE, a.fit)
        assertEquals(a.width, a.srcWidth); assertEquals(a.height, a.srcHeight)
        val b = Planner.plan(1080, 2400, Preset.NATIVE, AspectMode.CROP, allSupported)!!
        assertEquals(Fit.SCALE, b.fit)
    }

    @Test
    fun reshapedPlanThatHasToShrinkKeepsBothShapes() {
        val p = Planner.plan(2400, 1080, Preset.NATIVE, AspectMode.CROP) { _, w, h, _ -> w * h <= 1280 * 720 }!!
        assertTrue(p.width * p.height <= 1280 * 720)
        assertEquals(16.0 / 9.0, p.width.toDouble() / p.height, 0.05)
        assertEquals(2400.0 / 1080.0, p.srcWidth.toDouble() / p.srcHeight, 0.03)
    }

    @Test
    fun cropRectTrimsTheEdgesEvenlyAndOnlyForCrop() {
        val c = Planner.cropRect(2400, 1080, 1920, 1080, Fit.CROP)
        assertArrayEquals(doubleArrayOf(0.1, 0.0, 0.9, 1.0), c, 1e-9)
        val tall = Planner.cropRect(1000, 1000, 1600, 900, Fit.CROP)
        assertEquals(0.0, tall[0], 1e-9); assertEquals(1.0, tall[2], 1e-9)
        assertEquals(0.21875, tall[1], 1e-9); assertEquals(1 - 0.21875, tall[3], 1e-9)
        val all = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        assertArrayEquals(all, Planner.cropRect(2400, 1080, 1920, 1080, Fit.STRETCH), 0.0)
        assertArrayEquals(all, Planner.cropRect(2400, 1080, 2400, 1080, Fit.SCALE), 0.0)
    }
}
