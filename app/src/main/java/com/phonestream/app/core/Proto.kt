package com.phonestream.app.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * PhoneStream wire protocol. Everything (control, video, audio) travels over ONE TCP connection
 * as length-prefixed packets:  [type:1][payloadLength:4 big-endian][payload].
 *
 * Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the JVM.
 */
object Proto {
    const val MAGIC = 0x50535452 // "PSTR"
    const val VERSION = 2
    const val DEFAULT_PORT = 47800
    const val NSD_TYPE = "_phonestream._tcp"

    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"

    const val AUDIO_RATE = 48000
    const val AUDIO_CHANNELS = 2 // 16-bit little-endian PCM, uncompressed

    // sender -> receiver
    const val T_HELLO = 1
    const val T_VIDEO_CONFIG = 3
    const val T_VIDEO_FRAME = 4 // whole frame in one packet (still understood, no longer sent)
    const val T_VIDEO_PART = 12 // one slice of a frame, so audio/control can slip in between slices
    const val T_AUDIO_CONFIG = 5
    const val T_AUDIO_DATA = 6
    const val T_QUERY = 10

    // receiver -> sender
    const val T_HELLO_ACK = 2
    const val T_REQUEST_KEYFRAME = 8
    const val T_QUERY_REPLY = 11
    const val T_STATS = 13 // how the receiver's decoder is coping, once a second

    // both directions
    const val T_HEARTBEAT = 7
    const val T_BYE = 9

    const val MAX_PAYLOAD = 48 * 1024 * 1024

    /** A video frame is cut into slices of this size. 16 KB is ~1 ms on a fast link, ~6 ms on a slow one. */
    const val VIDEO_SLICE = 16 * 1024

    // UDP discovery (fallback for networks where mDNS is filtered)
    const val UDP_PROBE = "PSTREAM?1"
    const val UDP_REPLY = "PSTREAM!1"

    /** A phone that cannot reach a receiver asks it, by broadcast, to refresh its own network connection. */
    const val UDP_HELP = "PSTREAM#1"
}

class Packet(val type: Int, val payload: ByteArray)

/** Reads packets from [input] (single reader thread) and writes to [output] (thread-safe). */
class PacketStream(input: InputStream, private val output: OutputStream) {
    private val din = DataInputStream(input.buffered(128 * 1024))
    private val header = ByteArray(5)

    @Throws(IOException::class)
    fun read(): Packet {
        din.readFully(header)
        val bb = ByteBuffer.wrap(header)
        val type = bb.get().toInt() and 0xFF
        val len = bb.getInt()
        if (len < 0 || len > Proto.MAX_PAYLOAD) throw IOException("Bad packet length $len")
        val payload = ByteArray(len)
        din.readFully(payload)
        return Packet(type, payload)
    }

    @Throws(IOException::class)
    fun write(type: Int, payload: ByteArray = EMPTY) {
        // One contiguous array -> one write() -> header and payload leave in the same TCP segment(s).
        val frame = ByteArray(5 + payload.size)
        val bb = ByteBuffer.wrap(frame)
        bb.put(type.toByte())
        bb.putInt(payload.size)
        bb.put(payload)
        synchronized(output) {
            output.write(frame)
            output.flush()
        }
    }

    companion object {
        val EMPTY = ByteArray(0)
    }
}

data class Hello(val name: String, val version: Int = Proto.VERSION)
data class HelloAck(val accepted: Boolean, val reason: String, val name: String, val version: Int = 1)

/** What the receiver reports about itself once a second. */
data class Stats(val fps: Int, val queued: Int, val skipped: Int)
data class VideoConfig(val mime: String, val width: Int, val height: Int, val fps: Int)
data class AudioConfig(val sampleRate: Int, val channels: Int)
data class Query(val id: Int, val mime: String, val width: Int, val height: Int, val fps: Int)
class VideoFrame(val key: Boolean, val ptsUs: Long, val data: ByteArray, val offset: Int) {
    val length: Int get() = data.size - offset
}
class AudioData(val ptsUs: Long, val data: ByteArray, val offset: Int) {
    val length: Int get() = data.size - offset
}

/** Encoders/decoders for each packet payload. */
object Msg {
    private inline fun build(block: DataOutputStream.() -> Unit): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply(block).flush()
        return bo.toByteArray()
    }

    private fun clip(s: String) = if (s.length > 80) s.substring(0, 80) else s

    fun hello(name: String) = build {
        writeInt(Proto.MAGIC)
        writeShort(Proto.VERSION)
        writeUTF(clip(name))
    }

    fun parseHello(p: ByteArray): Hello {
        val d = DataInputStream(p.inputStream())
        if (d.readInt() != Proto.MAGIC) throw IOException("Not a PhoneStream peer")
        val version = d.readUnsignedShort()
        return Hello(d.readUTF(), version) // the receiver decides what to do about a version mismatch
    }

    fun helloAck(accepted: Boolean, reason: String, name: String) = build {
        writeBoolean(accepted)
        writeUTF(clip(reason))
        writeUTF(clip(name))
        writeShort(Proto.VERSION)
    }

    fun parseHelloAck(p: ByteArray): HelloAck {
        val d = DataInputStream(p.inputStream())
        val accepted = d.readBoolean()
        val reason = d.readUTF()
        val name = d.readUTF()
        val version = if (d.available() >= 2) d.readUnsignedShort() else 1 // version 1 receivers don't say
        return HelloAck(accepted, reason, name, version)
    }

    fun stats(s: Stats) = build {
        writeShort(s.fps.coerceIn(0, 0xFFFF))
        writeShort(s.queued.coerceIn(0, 0xFFFF))
        writeShort(s.skipped.coerceIn(0, 0xFFFF))
    }

    fun parseStats(p: ByteArray): Stats {
        val d = DataInputStream(p.inputStream())
        return Stats(d.readUnsignedShort(), d.readUnsignedShort(), d.readUnsignedShort())
    }

    fun videoConfig(c: VideoConfig) = build {
        writeUTF(c.mime)
        writeInt(c.width)
        writeInt(c.height)
        writeInt(c.fps)
    }

    fun parseVideoConfig(p: ByteArray): VideoConfig {
        val d = DataInputStream(p.inputStream())
        return VideoConfig(d.readUTF(), d.readInt(), d.readInt(), d.readInt())
    }

    fun audioConfig(c: AudioConfig) = build {
        writeInt(c.sampleRate)
        writeInt(c.channels)
    }

    fun parseAudioConfig(p: ByteArray): AudioConfig {
        val d = DataInputStream(p.inputStream())
        return AudioConfig(d.readInt(), d.readInt())
    }

    fun query(q: Query) = build {
        writeInt(q.id)
        writeUTF(q.mime)
        writeInt(q.width)
        writeInt(q.height)
        writeInt(q.fps)
    }

    fun parseQuery(p: ByteArray): Query {
        val d = DataInputStream(p.inputStream())
        return Query(d.readInt(), d.readUTF(), d.readInt(), d.readInt(), d.readInt())
    }

    fun queryReply(id: Int, ok: Boolean) = build {
        writeInt(id)
        writeBoolean(ok)
    }

    /** Returns (id, supported). */
    fun parseQueryReply(p: ByteArray): Pair<Int, Boolean> {
        val d = DataInputStream(p.inputStream())
        return d.readInt() to d.readBoolean()
    }

    fun bye(reason: String) = build { writeUTF(clip(reason)) }

    fun parseBye(p: ByteArray): String = try {
        DataInputStream(p.inputStream()).readUTF()
    } catch (e: Exception) {
        ""
    }

    // ---- bulk packets: fixed binary layout, payload data stays at the end ----

    fun videoFrame(key: Boolean, ptsUs: Long, data: ByteArray): ByteArray {
        val out = ByteArray(9 + data.size)
        val bb = ByteBuffer.wrap(out)
        bb.put(if (key) 1 else 0)
        bb.putLong(ptsUs)
        bb.put(data)
        return out
    }

    fun parseVideoFrame(p: ByteArray): VideoFrame {
        if (p.size < 9) throw IOException("Short video packet")
        val bb = ByteBuffer.wrap(p)
        val key = bb.get().toInt() and 1 != 0
        return VideoFrame(key, bb.getLong(), p, 9)
    }

    // A frame travels as slices. The first slice carries [flags][total:4][pts:8], the others just [flags].
    // Slices of one frame are always contiguous among the video packets (only audio/control may sit between).
    const val PART_KEY = 1
    const val PART_FIRST = 2
    const val PART_LAST = 4

    /** Slice [off, off+len) of a frame of [data].size bytes. */
    fun videoPart(key: Boolean, ptsUs: Long, data: ByteArray, off: Int, len: Int): ByteArray {
        val first = off == 0
        val last = off + len >= data.size
        val flags = (if (key) PART_KEY else 0) or (if (first) PART_FIRST else 0) or (if (last) PART_LAST else 0)
        val head = if (first) 13 else 1
        val out = ByteArray(head + len)
        val bb = ByteBuffer.wrap(out)
        bb.put(flags.toByte())
        if (first) {
            bb.putInt(data.size)
            bb.putLong(ptsUs)
        }
        bb.put(data, off, len)
        return out
    }

    fun audioData(ptsUs: Long, pcm: ByteArray, length: Int = pcm.size): ByteArray {
        val out = ByteArray(8 + length)
        val bb = ByteBuffer.wrap(out)
        bb.putLong(ptsUs)
        bb.put(pcm, 0, length)
        return out
    }

    fun parseAudioData(p: ByteArray): AudioData {
        if (p.size < 8) throw IOException("Short audio packet")
        return AudioData(ByteBuffer.wrap(p).getLong(), p, 8)
    }
}

/**
 * Puts the slices of [Msg.videoPart] back together. Receiver side, single thread.
 * Anything that arrives out of the expected order (lost start, reconfiguration in between) is dropped quietly:
 * the decoder then simply waits for the next key frame.
 */
class FrameAssembler {
    private var buf: ByteArray? = null
    private var filled = 0
    private var key = false
    private var pts = 0L

    fun reset() {
        buf = null
        filled = 0
    }

    /** Returns the finished frame when [payload] was its last slice. */
    fun add(payload: ByteArray): VideoFrame? {
        if (payload.isEmpty()) throw IOException("Empty video slice")
        val flags = payload[0].toInt() and 0xFF
        var pos = 1
        if (flags and Msg.PART_FIRST != 0) {
            if (payload.size < 13) throw IOException("Short video slice")
            val bb = ByteBuffer.wrap(payload, 1, 12)
            val total = bb.getInt()
            if (total < 0 || total > Proto.MAX_PAYLOAD) throw IOException("Bad frame size $total")
            pts = bb.getLong()
            key = flags and Msg.PART_KEY != 0
            buf = ByteArray(total)
            filled = 0
            pos = 13
        }
        val b = buf ?: return null // joined mid-frame: ignore until the next first slice
        val n = payload.size - pos
        if (filled + n > b.size) {
            reset()
            throw IOException("Video slice overflows its frame")
        }
        System.arraycopy(payload, pos, b, filled, n)
        filled += n
        if (flags and Msg.PART_LAST == 0) return null
        val done = if (filled == b.size) VideoFrame(key, pts, b, 0) else null
        reset()
        return done
    }
}
