package dev.clio.app

import dev.clio.app.engine.FpsCap
import org.junit.Assert.assertEquals
import org.junit.Test

class FpsCapTest {
    private fun kept(fps: Double, target: Float, seconds: Int, jitterUs: Long = 0): Int {
        val step = (1_000_000.0 / target).toLong()
        var next = Long.MIN_VALUE; var n = 0
        for (i in 0 until (fps * seconds).toInt()) {
            val pts = (i * 1_000_000.0 / fps).toLong() + if (i % 2 == 0) jitterUs else -jitterUs
            val (k, nx) = FpsCap.Decimator.keep(pts, next, step)
            if (k) { n++; next = nx }
        }
        return n
    }

    @Test fun thirtyToTwentyFour() = assertEquals(24.0, kept(30.0, 24f, 6) / 6.0, 0.5)
    @Test fun sixtyToThirty() = assertEquals(30.0, kept(60.0, 30f, 6, jitterUs = 300) / 6.0, 0.5)
    /** Phone-style VFR (intervals 25..42 ms, seeded): ~29.9 fps average. */
    private fun vfr(n: Int) = java.util.Random(7).let { r -> var p = 0L; List(n) { p.also { p += 25_000 + r.nextInt(17_000) } } }

    @Test fun vfrThirtyAtThirtyIsNotCapped() {
        val pts = vfr(600)
        val avg = (pts.size - 1) * 1e6f / (pts.last() - pts.first())
        assertEquals(false, FpsCap.needed(avg, 30f))
        assertEquals(false, FpsCap.needed(31f, 30f))
        assertEquals(true, FpsCap.needed(avg, 24f))
        assertEquals(true, FpsCap.needed(null, 30f))
    }

    @Test fun vfrThirtyToTwentyFour() {
        val pts = vfr(600); var next = Long.MIN_VALUE; var kept = 0
        for (p in pts) { val (k, nx) = FpsCap.Decimator.keep(p, next, 41_666); if (k) { kept++; next = nx } }
        assertEquals(24.0, kept * 1e6 / (pts.last() - pts.first()), 1.0)
    }

    @Test fun belowTargetKeepsAll() = assertEquals(180, kept(30.0, 60f, 6))
}
