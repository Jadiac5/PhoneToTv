package com.phonestream.app.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.phonestream.app.core.Planner
import com.phonestream.app.core.Proto
import com.phonestream.app.core.StreamPlan

/** What this device's hardware encoders / decoders can really do. */
object Codecs {
    private const val KEY_FRAME_INTERVAL_S = 4

    private fun allInfos(): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()

    private fun supportsMime(info: MediaCodecInfo, mime: String) =
        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))

    /**
     * Software codecs are tolerated only for small/slow streams (they can't keep up with phone-screen
     * resolutions in real time); hardware codecs may go as far as they report.
     */
    private fun usable(info: MediaCodecInfo, w: Int, h: Int, fps: Int) =
        isHardware(info) || (w.toLong() * h <= 1280L * 720 && fps <= 30)

    private fun videoCaps(info: MediaCodecInfo, mime: String): MediaCodecInfo.VideoCapabilities? =
        try {
            info.getCapabilitiesForType(mime).videoCapabilities
        } catch (e: Exception) {
            null
        }

    private fun sizeAndRateOk(vc: MediaCodecInfo.VideoCapabilities, w: Int, h: Int, fps: Int) =
        try {
            vc.isSizeSupported(w, h) && vc.areSizeAndRateSupported(w, h, fps.toDouble())
        } catch (e: Exception) {
            false
        }

    /** Name of the best encoder for this stream: hardware first. */
    fun pickEncoder(mime: String, w: Int, h: Int, fps: Int): String? {
        var software: String? = null
        for (info in allInfos()) {
            if (!info.isEncoder || !supportsMime(info, mime) || !usable(info, w, h, fps)) continue
            val vc = videoCaps(info, mime) ?: continue
            if (!sizeAndRateOk(vc, w, h, fps)) continue
            if (isHardware(info)) return info.name
            if (software == null) software = info.name
        }
        return software
    }

    fun encoderSupports(mime: String, w: Int, h: Int, fps: Int) = pickEncoder(mime, w, h, fps) != null

    /** Receiver side: can a decoder on THIS device play the stream? */
    fun decoderSupports(mime: String, w: Int, h: Int, fps: Int): Boolean {
        for (info in allInfos()) {
            if (info.isEncoder || !supportsMime(info, mime) || !usable(info, w, h, fps)) continue
            val vc = videoCaps(info, mime) ?: continue
            if (sizeAndRateOk(vc, w, h, fps)) return true
        }
        return false
    }

    /** An unstarted surface-input encoder, with the bitrate range it may be steered within at run time. */
    class Encoder(val codec: MediaCodec, val ceilingBps: Int, val startBps: Int)

    /** Creates and configures an (unstarted) surface-input encoder for [plan]. */
    fun createEncoder(plan: StreamPlan): Encoder {
        val name = pickEncoder(plan.mime, plan.width, plan.height, plan.fps)

        var ceiling = Planner.bitrate(plan)
        var range: android.util.Range<Int>? = null
        var cbr = false
        if (name != null) {
            try {
                val caps = allInfos().first { it.name == name }.getCapabilitiesForType(plan.mime)
                range = caps.videoCapabilities?.bitrateRange
                range?.let { ceiling = it.clamp(ceiling) }
                cbr = caps.encoderCapabilities?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) == true
            } catch (_: Exception) {
            }
        }
        var bitrate = Planner.startBitrate(ceiling)
        range?.let { bitrate = it.clamp(bitrate) }

        fun newCodec(): MediaCodec =
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(plan.mime)

        fun format(lowLatencyExtras: Boolean): MediaFormat {
            val f = MediaFormat.createVideoFormat(plan.mime, plan.width, plan.height)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
            // A key frame is several times the size of a normal one: every second that was a burst the link
            // had to swallow in one go. Joiners and recoveries ask for one explicitly, so seldom is fine.
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)
            if (lowLatencyExtras) {
                // Unknown keys are ignored by codecs; the string literals avoid compile-time API-level coupling.
                f.setInteger("priority", 0)                       // real-time
                f.setInteger("latency", 1)                        // lowest latency (API 30+)
                f.setInteger("max-bframes", 0)                    // never reorder frames
                f.setInteger("max-fps-to-encoder", plan.fps)      // don't encode 120 Hz screens at 120 fps
                f.setInteger("prepend-sps-pps-to-idr-frames", 1)  // every key frame is self-contained
                f.setLong("repeat-previous-frame-after", 100_000L) // static screen -> keep frames flowing
                if (cbr) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                f.setInteger(
                    MediaFormat.KEY_PROFILE,
                    if (plan.mime == Proto.MIME_HEVC) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                )
            }
            return f
        }

        // First try with all the low-latency extras; if this encoder rejects any of them, retry plain.
        for (extras in booleanArrayOf(true, false)) {
            val codec = newCodec()
            try {
                codec.configure(format(extras), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return Encoder(codec, ceiling, bitrate)
            } catch (e: Exception) {
                codec.release()
                if (!extras) throw e
            }
        }
        throw IllegalStateException("unreachable")
    }

    /** Creates, configures (onto [surface]) and starts a decoder for the incoming stream. */
    fun createDecoder(mime: String, w: Int, h: Int, surface: android.view.Surface): MediaCodec {
        fun format(extras: Boolean): MediaFormat {
            val f = MediaFormat.createVideoFormat(mime, w, h)
            // High-bitrate 4K key frames can be several MB: make sure one fits in a single input buffer.
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(1 shl 20, w * h * 3 / 2))
            if (extras) {
                f.setInteger("low-latency", 1)                  // API 30+
                f.setInteger("priority", 0)
                f.setInteger("vendor.low-latency.enable", 1)    // Qualcomm
                f.setInteger("vdec-lowlatency", 1)              // MediaTek
                f.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            }
            return f
        }

        val name = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format(false))
        } catch (_: Exception) {
            null
        }
        for (extras in booleanArrayOf(true, false)) {
            val codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format(extras), surface, null, 0)
                codec.start()
                return codec
            } catch (e: Exception) {
                try { codec.release() } catch (_: Exception) {}
                if (!extras) throw e
            }
        }
        throw IllegalStateException("unreachable")
    }
}
