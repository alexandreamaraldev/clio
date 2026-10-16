package dev.clio.app.engine

import androidx.annotation.OptIn
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TimestampAdjustment
import uniffi.clio_ffi.SpeedCurve
import uniffi.clio_ffi.curveSegments

/**
 * A clip's speed curve as a piecewise-constant plan (from the engine's `curve_segments`), with
 * source <-> timeline mapping. Times are clip-relative ms: source from the in-point, timeline from
 * the clip start. Preview (per-segment playback speed, playhead) and export (frame retiming,
 * audio speed) both go through this, so they match the timeline.
 */
class SpeedMap(srcStartsMs: List<Long>, speeds: List<Float>, private val spanMs: Long) {
    private val src = srcStartsMs.toLongArray()
    private val speed = speeds.toFloatArray()
    /** Timeline start of each segment (double, to avoid accumulated rounding). */
    private val tl = DoubleArray(src.size).also { t ->
        for (i in 1 until src.size) t[i] = t[i - 1] + (src[i] - src[i - 1]) / speed[i - 1].toDouble()
    }
    val durationMs: Double = if (src.isEmpty()) 0.0 else tl.last() + (spanMs - src.last()) / speed.last().toDouble()

    private fun segAtSrc(s: Double): Int { var i = src.indexOfLast { it <= s }; if (i < 0) i = 0; return i }
    private fun segAtTl(t: Double): Int { var i = tl.indexOfLast { it <= t }; if (i < 0) i = 0; return i }

    fun speedAtSource(srcMs: Double): Float = speed[segAtSrc(srcMs)]
    fun timelineAt(srcMs: Double): Double { val i = segAtSrc(srcMs); return tl[i] + (srcMs - src[i]) / speed[i] }
    fun sourceAt(tlMs: Double): Double { val i = segAtTl(tlMs); return (src[i] + (tlMs - tl[i]) * speed[i]).coerceIn(0.0, spanMs.toDouble()) }
    /** Source ms where the next speed change happens after [srcMs], or null at the last segment. */
    fun nextChangeSrc(srcMs: Double): Long? = src.firstOrNull { it > srcMs }
    val maxSpeed: Float get() = speed.maxOrNull() ?: 1f

    /** Media3 speed provider in clip-relative source µs (the audio processor's clock). */
    fun provider(): SpeedProvider = object : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speedAtSource(timeUs / 1000.0)
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = nextChangeSrc(timeUs / 1000.0)?.times(1000) ?: androidx.media3.common.C.TIME_UNSET
    }

    /**
     * Export: retimes video frames by the curve. Frame timestamps may carry the sequence offset of
     * earlier items, so the first frame defines the clip origin; the offset is kept on output.
     */
    @OptIn(UnstableApi::class)
    fun videoEffect(): TimestampAdjustment {
        var origin = Long.MIN_VALUE
        return TimestampAdjustment { inUs, out ->
            if (origin == Long.MIN_VALUE) origin = inUs
            out.onTimestamp(origin + (timelineAt((inUs - origin) / 1000.0) * 1000).toLong())
        }
    }

    /** Export audio: Media3 1.4's only variable-speed processor; pitch follows speed (see docs). */
    @OptIn(UnstableApi::class)
    fun audioProcessor() = SpeedChangingAudioProcessor(provider())

    companion object {
        fun of(curve: SpeedCurve, spanMs: Long, durationMs: Long): SpeedMap {
            val segs = curveSegments(curve, spanMs.toULong(), durationMs.toULong())
            return SpeedMap(segs.map { it.srcMs.toLong() }, segs.map { it.speed }, spanMs)
        }
    }
}
