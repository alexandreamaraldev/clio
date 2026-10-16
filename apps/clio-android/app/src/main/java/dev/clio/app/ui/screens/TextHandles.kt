package dev.clio.app.ui.screens

import dev.clio.app.engine.Engine
import dev.clio.app.engine.Mix
import kotlin.math.abs

/**
 * Timing edits for text and caption clips on the timeline (trim either edge, or move the whole
 * clip). Same 30 fps grid as the main-track handles, plus a magnet to the playhead and to other
 * clip edges so text lines up with cuts.
 */
internal object TextHandles {
    const val MIN_S = 0.1f
    /** Magnet range for playhead / neighbour edges. */
    const val MAGNET_S = 0.12f

    /** Snap [t] to the closest anchor within [MAGNET_S], else to the frame grid. */
    fun snapTo(t: Float, anchors: List<Float>): Float {
        val near = anchors.minByOrNull { abs(it - t) }
        return if (near != null && abs(near - t) <= MAGNET_S) near else Mix.snap(t)
    }

    /** New (start, length) for an edge drag. Left keeps the end; right keeps the start. */
    fun trim(startS: Float, lenS: Float, left: Boolean, deltaS: Float, anchors: List<Float> = emptyList()): Pair<Float, Float> {
        val end = startS + lenS
        return if (left) {
            val s = snapTo(startS + deltaS, anchors).coerceIn(0f, end - MIN_S)
            s to (end - s)
        } else {
            val e = snapTo(end + deltaS, anchors).coerceAtLeast(startS + MIN_S)
            startS to (e - startS)
        }
    }

    /** New start for a whole-clip move; whichever edge lands nearer an anchor wins. */
    fun move(startS: Float, lenS: Float, deltaS: Float, anchors: List<Float> = emptyList()): Float {
        val raw = startS + deltaS
        val dStart = anchors.minByOrNull { abs(it - raw) }?.let { it - raw }
        val dEnd = anchors.minByOrNull { abs(it - (raw + lenS)) }?.let { it - (raw + lenS) }
        val pull = listOfNotNull(dStart, dEnd).filter { abs(it) <= MAGNET_S }.minByOrNull { abs(it) }
        return (if (pull != null) raw + pull else Mix.snap(raw)).coerceAtLeast(0f)
    }

    /** Applies a retime to caption [index]; words are shifted with the cue and clipped to it. */
    fun retimeCaption(caps: List<Engine.Cap>, index: Int, startMs: Long, endMs: Long): List<Engine.Cap> {
        val c = caps.getOrNull(index) ?: return caps
        val shift = startMs - c.startMs
        val moved = (endMs - startMs) == (c.endMs - c.startMs)
        val words = c.words.mapNotNull { w ->
            val ws = if (moved) w.startMs.toLong() + shift else w.startMs.toLong().coerceIn(startMs, endMs)
            val we = if (moved) w.endMs.toLong() + shift else w.endMs.toLong().coerceIn(startMs, endMs)
            if (we > ws) w.copy(startMs = ws.coerceAtLeast(0).toULong(), endMs = we.toULong()) else null
        }
        return caps.toMutableList().also { it[index] = c.copy(startMs = startMs, endMs = endMs, words = words) }.sortedBy { it.startMs }
    }
}
