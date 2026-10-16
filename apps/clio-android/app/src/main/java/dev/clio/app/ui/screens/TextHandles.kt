package dev.clio.app.ui.screens

import dev.clio.app.engine.Mix
import kotlin.math.abs

/**
 * Timing edits for text and caption clips on the timeline (trim either edge, or move the whole
 * clip). Same 30 fps grid as the main-track handles, plus a magnet to the playhead and to other
 * clip edges so text lines up with cuts.
 */
internal object TextHandles {
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
            val s = snapTo(startS + deltaS, anchors)
            s to (end - s)
        } else {
            val e = snapTo(end + deltaS, anchors)
            startS to (e - startS)
        }
    }

    /** New start for a whole-clip move; whichever edge lands nearer an anchor wins. */
    fun move(startS: Float, lenS: Float, deltaS: Float, anchors: List<Float> = emptyList()): Float {
        val raw = startS + deltaS
        val dStart = anchors.minByOrNull { abs(it - raw) }?.let { it - raw }
        val dEnd = anchors.minByOrNull { abs(it - (raw + lenS)) }?.let { it - (raw + lenS) }
        val pull = listOfNotNull(dStart, dEnd).filter { abs(it) <= MAGNET_S }.minByOrNull { abs(it) }
        return if (pull != null) raw + pull else Mix.snap(raw)
    }
}
