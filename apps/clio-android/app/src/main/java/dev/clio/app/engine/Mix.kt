package dev.clio.app.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure math shared by preview and export (unit-tested, no Android types). */
object Mix {
    /** Clip gain at [tMs] into a clip of [durMs]: volume × linear fade-in/out envelope. */
    fun gain(tMs: Long, durMs: Long, volume: Float, fadeInMs: Long, fadeOutMs: Long, muted: Boolean): Float {
        if (muted || durMs <= 0) return 0f
        val t = tMs.coerceIn(0, durMs)
        var g = volume
        if (fadeInMs > 0 && t < fadeInMs) g *= t.toFloat() / fadeInMs
        val left = durMs - t
        if (fadeOutMs > 0 && left < fadeOutMs) g *= left.toFloat() / fadeOutMs
        return g.coerceIn(0f, 2f)
    }

    /** Source length consumed by a clip of timeline length [durMs] at [speed]. */
    fun spanMs(durMs: Long, speed: Float): Long = (durMs * speed.toDouble()).roundToInt64()

    /** Export canvas for an aspect ratio: the short side is [shortSide], both sides even. */
    fun canvas(aspectW: Int, aspectH: Int, shortSide: Int): Pair<Int, Int> {
        fun even(x: Double) = (x / 2).roundToInt() * 2
        return if (aspectW <= aspectH) shortSide to even(shortSide.toDouble() * aspectH / aspectW)
        else even(shortSide.toDouble() * aspectW / aspectH) to shortSide
    }

    /** Fraction of the canvas covered by a source fitted inside it (for the blurred-background overlay). */
    fun fitScale(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Pair<Float, Float> {
        if (srcW <= 0 || srcH <= 0) return 1f to 1f
        val s = min(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        return min(1f, srcW * s / dstW) to min(1f, srcH * s / dstH)
    }

    /** Snap seconds to a frame grid (default 30 fps). */
    fun snap(s: Float, fps: Int = 30): Float = (s * fps).roundToInt().toFloat() / fps

    /** New (in, length) for a trim-handle drag of [deltaS] seconds, clamped to the source. */
    fun trim(inS: Float, lenS: Float, speed: Float, sourceS: Float, left: Boolean, deltaS: Float, minS: Float = 0.1f): Pair<Float, Float> =
        if (left) {
            val minD = -inS / speed
            val d = deltaS.coerceIn(minD, max(minD, lenS - minS))
            (inS + d * speed) to (lenS - d)
        } else {
            val maxLen = if (sourceS > 0f) (sourceS - inS) / speed else Float.MAX_VALUE
            inS to (lenS + deltaS).coerceIn(min(minS, maxLen), max(minS, maxLen))
        }

    private fun Double.roundToInt64(): Long = Math.round(this)
}

/** Export resolution choices (short side) and fps. */
enum class ExportRes(val label: String, val shortSide: Int) { P720("720p", 720), P1080("1080p", 1080), K4("4K", 2160) }

/** Canvas presets offered in Formato. */
enum class Aspect(val label: String, val w: Int, val h: Int) {
    Portrait("9:16", 9, 16), Square("1:1", 1, 1), Landscape("16:9", 16, 9), Feed("4:5", 4, 5);
    fun canvas() = Mix.canvas(w, h, 1080)
    companion object { fun of(width: Int, height: Int): Aspect? = entries.firstOrNull { it.w * height == it.h * width } }
}
