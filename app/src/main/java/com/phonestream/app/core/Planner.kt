package com.phonestream.app.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Resolution presets offered on the sending side. Sizes are defined by the SHORT side of the screen. */
enum class Preset(val label: String, val shortSide: Int) {
    NATIVE("Native", 0),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480);

    companion object {
        fun fromName(n: String?): Preset = values().firstOrNull { it.name == n } ?: NATIVE
    }
}

/** What to do when the phone is in landscape and the TV is 16:9. In portrait the picture is always kept as it is. */
enum class AspectMode(val label: String, val hint: String) {
    ORIGINAL("Original", "Exactly what the phone shows. Black bars on the TV where the shapes differ."),
    CROP("Fill 16:9", "Fills the TV by trimming the edges. Nothing is stretched. Ideal for fullscreen video."),
    STRETCH("Stretch 16:9", "Fills the TV by squeezing the picture sideways. Everything stays, but looks slightly narrow.");

    companion object {
        fun fromName(n: String?): AspectMode = values().firstOrNull { it.name == n } ?: ORIGINAL
    }
}

/** "2400×1080": always the longer side first, whichever way the phone is held. */
fun resolutionLabel(w: Int, h: Int): String = "${max(w, h)}×${min(w, h)}"

/** How the screen picture ([StreamPlan.srcWidth] x [StreamPlan.srcHeight]) becomes the encoded picture. */
enum class Fit { SCALE, CROP, STRETCH }

/**
 * [width] x [height] is what gets encoded and sent. The phone's screen is captured at [srcWidth] x [srcHeight]
 * (a different shape from the output only for [Fit.CROP] / [Fit.STRETCH]).
 */
data class StreamPlan(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val srcWidth: Int = width,
    val srcHeight: Int = height,
    val fit: Fit = Fit.SCALE,
) {
    val codecLabel: String get() = if (mime == Proto.MIME_HEVC) "H.265" else "H.264"
}

object Planner {
    /** Fractions of the requested size we are willing to fall back to if a codec can't do the full size. */
    private val scales = doubleArrayOf(1.0, 0.92, 0.85, 0.78, 0.70, 0.62, 0.55, 0.48, 0.42, 0.36)
    private val aligns = intArrayOf(2, 4, 8, 16)

    private fun even(v: Double): Int = max(2, (v / 2.0).roundToInt() * 2)
    private fun roundTo(v: Int, a: Int): Int = max(a, ((v + a / 2) / a) * a)

    /** The size a preset asks for on a screen of [nativeW] x [nativeH]. Never upscales. */
    fun presetSize(nativeW: Int, nativeH: Int, preset: Preset): Pair<Int, Int> {
        val shortSide = min(nativeW, nativeH)
        if (preset.shortSide == 0 || shortSide <= preset.shortSide) {
            return even(nativeW.toDouble()) to even(nativeH.toDouble())
        }
        val s = preset.shortSide.toDouble() / shortSide
        return even(nativeW * s) to even(nativeH * s)
    }

    private const val WIDE = 16.0 / 9.0

    /** Does [mode] change anything for a screen of this shape? Only landscape screens that aren't 16:9 already. */
    fun reshapes(nativeW: Int, nativeH: Int, mode: AspectMode): Boolean =
        mode != AspectMode.ORIGINAL && nativeW > nativeH && kotlin.math.abs(nativeW.toDouble() / nativeH - WIDE) > 0.02

    /** The biggest 16:9 rectangle that fits in the screen's own pixel count, e.g. 2400x1080 -> 1920x1080. */
    fun wideSize(nativeW: Int, nativeH: Int): Pair<Int, Int> =
        if (nativeW.toDouble() / nativeH > WIDE) even(nativeH * WIDE) to even(nativeH.toDouble())
        else even(nativeW.toDouble()) to even(nativeW / WIDE)

    /** The size the encoder is asked for (before any codec fallback): the preset applied to the screen, or to its 16:9 version. */
    fun outputSize(nativeW: Int, nativeH: Int, preset: Preset, aspect: AspectMode = AspectMode.ORIGINAL): Pair<Int, Int> =
        if (reshapes(nativeW, nativeH, aspect)) {
            val (ww, wh) = wideSize(nativeW, nativeH)
            presetSize(ww, wh, preset)
        } else presetSize(nativeW, nativeH, preset)

    /**
     * Picks codec / size / frame rate for a requested preset.
     * [supports] must answer "can BOTH the phone's encoder and the TV's decoder handle this?".
     *
     * Resolution wins over frame rate: full size at 30 fps beats a reduced size at 60 fps.
     * At a given size, 60 fps beats 30 and H.264 (most compatible) beats H.265.
     */
    fun plan(
        nativeW: Int,
        nativeH: Int,
        preset: Preset,
        aspect: AspectMode = AspectMode.ORIGINAL,
        supports: (mime: String, w: Int, h: Int, fps: Int) -> Boolean,
    ): StreamPlan? {
        val reshape = reshapes(nativeW, nativeH, aspect)
        val (ow, oh) = outputSize(nativeW, nativeH, preset, aspect)
        // Source: the whole screen, scaled by the same factor the output was (so crop keeps 1:1 pixels).
        val (sw, sh) = if (reshape) {
            val k = oh.toDouble() / wideSize(nativeW, nativeH).second
            even(nativeW * k) to even(nativeH * k)
        } else ow to oh
        val fit = if (!reshape) Fit.SCALE else if (aspect == AspectMode.CROP) Fit.CROP else Fit.STRETCH

        for (s in scales) {
            val bw = (ow * s).roundToInt()
            val bh = (oh * s).roundToInt()
            val sizes = LinkedHashSet<Pair<Int, Int>>()
            for (a in aligns) sizes += roundTo(bw, a) to roundTo(bh, a)
            for (fps in intArrayOf(60, 30)) {
                for (mime in arrayOf(Proto.MIME_AVC, Proto.MIME_HEVC)) {
                    for ((w, h) in sizes) {
                        if (supports(mime, w, h, fps)) {
                            return if (reshape) {
                                StreamPlan(mime, w, h, fps, even(sw * s), even(sh * s), fit)
                            } else StreamPlan(mime, w, h, fps)
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Highest video bitrate for a plan. Bandwidth is not a concern on a good LAN, so this is very generous
     * (about 39 Mbit/s for 1080x2400 @ 60 fps) -- screen content stays crisp with near-zero artefacts.
     * What is really used is adapted to the link at run time ([Tuner]), up to this ceiling.
     */
    fun bitrate(plan: StreamPlan): Int {
        val bitsPerPixel = if (plan.mime == Proto.MIME_HEVC) 0.17 else 0.25
        val bits = plan.width.toDouble() * plan.height * plan.fps * bitsPerPixel
        return bits.coerceIn(6_000_000.0, 120_000_000.0).toInt()
    }

    /** Bitrate to start a stream at: well under the ceiling, the [Tuner] feels its way up from here. */
    fun startBitrate(ceiling: Int): Int = (ceiling * 0.45).toInt().coerceAtLeast(minOf(ceiling, 6_000_000))

    /** The [Tuner] never goes below this. */
    fun floorBitrate(ceiling: Int): Int = (ceiling / 10).coerceAtLeast(minOf(ceiling, 1_500_000))

    /**
     * The part of the captured picture ([srcW] x [srcH]) that ends up in the [outW] x [outH] output, as
     * [u0, v0, u1, v1] fractions of the picture. Only [Fit.CROP] shows less than all of it (centered).
     */
    fun cropRect(srcW: Int, srcH: Int, outW: Int, outH: Int, fit: Fit): DoubleArray {
        if (fit != Fit.CROP) return doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        val srcAspect = srcW.toDouble() / srcH
        val outAspect = outW.toDouble() / outH
        return if (srcAspect > outAspect) { // too wide: trim left and right
            val m = (1 - outAspect / srcAspect) / 2
            doubleArrayOf(m, 0.0, 1 - m, 1.0)
        } else { // too tall: trim top and bottom
            val m = (1 - srcAspect / outAspect) / 2
            doubleArrayOf(0.0, m, 1.0, 1 - m)
        }
    }
}
