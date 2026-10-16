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
    @Test fun belowTargetKeepsAll() = assertEquals(180, kept(30.0, 60f, 6))
}
