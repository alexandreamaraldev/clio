package dev.clio.app

import dev.clio.app.engine.FrameSelector
import dev.clio.app.engine.LayerMemory
import dev.clio.app.engine.displaySize
import dev.clio.app.engine.layerOnCanvas
import dev.clio.app.engine.rotatedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerDecoderTest {
    /** Drives FrameSelector like the decoder does over a VFR pts list; returns the pts shown for [t]. */
    private fun pick(sel: FrameSelector, pts: List<Long>, cursor: IntArray, t: Long): Long {
        if (sel.plan(t) == FrameSelector.Action.SHOW_CURRENT) return sel.shownPts
        var held = Long.MIN_VALUE
        if (sel.pendingPts != Long.MIN_VALUE && sel.pendingPts <= t) { held = sel.pendingPts; sel.consumePending() }
        while (sel.pendingPts == Long.MIN_VALUE && cursor[0] < pts.size) {
            val p = pts[cursor[0]++]
            if (sel.offer(p, t) == FrameSelector.Offer.CANDIDATE) held = p
        }
        if (held != Long.MIN_VALUE) sel.shown(held)
        return sel.shownPts
    }

    @Test fun vfrFramesAreChosenByRealTimestamps() {
        // Phone-style VFR: 33 ms, then a 100 ms gap, then 16 ms frames.
        val pts = listOf(0L, 33_000, 66_000, 166_000, 182_000, 198_000, 214_000)
        val sel = FrameSelector(); val cur = intArrayOf(0)
        assertEquals(0L, pick(sel, pts, cur, 10_000))
        assertEquals(66_000L, pick(sel, pts, cur, 120_000))   // CFR math (idx = t*fps) would land on a later frame
        assertEquals(66_000L, pick(sel, pts, cur, 150_000))   // still inside the gap: no new decode
        assertEquals(182_000L, pick(sel, pts, cur, 190_000))
        assertEquals(214_000L, pick(sel, pts, cur, 500_000))  // past the end: last frame
    }

    @Test fun goingBackwardsAsksForASeek() {
        val sel = FrameSelector(); val cur = intArrayOf(0)
        pick(sel, listOf(0L, 33_000, 66_000, 99_000), cur, 70_000)
        assertEquals(FrameSelector.Action.SEEK, sel.plan(10_000))
    }

    @Test fun portraitRotationMapsCorners() {
        // A 1280x720 stream flagged rotation=90 displays as 720x1280.
        assertEquals(720 to 1280, displaySize(1280, 720, 90))
        assertEquals(1280 to 720, displaySize(1280, 720, 180))
        // Display top-left comes from the source bottom-left for a 90° clockwise rotation.
        assertEquals(0 to 35, rotatedSource(0, 0, 72, 128, 1280, 720, 90).let { (x, y) -> x / 20 to y / 20 })
        // Display top-right comes from the source top-left.
        assertEquals(0 to 0, rotatedSource(71, 0, 72, 128, 1280, 720, 90).let { (x, y) -> x / 20 to y / 20 })
        assertEquals(63 to 35, rotatedSource(0, 0, 128, 72, 1280, 720, 180).let { (x, y) -> x / 20 to y / 20 })
        assertEquals(5 to 7, rotatedSource(5, 7, 1280, 720, 1280, 720, 0))
    }

    @Test fun offCanvasCheckAccountsForRotation() {
        // 400x100 layer whose centre is 220 px past the right edge of a 1000 px canvas: hidden flat…
        assertFalse(layerOnCanvas(720f, 0f, 400f, 100f, 0f, 1000, 1000))
        // …but rotated 90° its 400 px side is vertical, still hidden; at 45° its corner reaches in.
        assertFalse(layerOnCanvas(720f, 0f, 400f, 100f, 90f, 1000, 1000))
        assertTrue(layerOnCanvas(650f, 0f, 400f, 100f, 45f, 1000, 1000))
        assertFalse(layerOnCanvas(650f, 0f, 400f, 100f, 90f, 1000, 1000))
    }

    @Test fun memoryBudgetScalesAllLayers() {
        assertEquals(1f, LayerMemory.fit(listOf(432 to 768), listOf(2)), 0f)
        val many = List(6) { 1920 to 1080 }
        val k = LayerMemory.fit(many, List(6) { 2 })
        assertTrue(k < 1f)
        val bytes = many.sumOf { (w, h) -> (w * k).toLong() * (h * k).toLong() * 6 }
        assertTrue("fits budget: $bytes", bytes <= LayerMemory.BUDGET_BYTES * 1.02)
    }
}
