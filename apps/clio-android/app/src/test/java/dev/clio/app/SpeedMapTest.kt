package dev.clio.app

import dev.clio.app.engine.SpeedMap
import dev.clio.app.ui.screens.speedToY
import dev.clio.app.ui.screens.yToSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMapTest {
    // 4 s of source: 2 s at 2x then 2 s at 0.5x -> 1 s + 4 s = 5 s of timeline.
    private val m = SpeedMap(listOf(0L, 2000L), listOf(2f, 0.5f), 4000)

    @Test fun durationIsTheIntegral() = assertEquals(5000.0, m.durationMs, 1e-6)

    @Test fun mapsBothWays() {
        assertEquals(500.0, m.timelineAt(1000.0), 1e-6)
        assertEquals(3000.0, m.timelineAt(3000.0), 1e-6) // 1000 + 1000/0.5
        for (t in listOf(0.0, 700.0, 1000.0, 2500.0, 4999.0)) assertEquals(t, m.timelineAt(m.sourceAt(t)), 1e-6)
    }

    @Test fun segmentSpeedAndNextChange() {
        assertEquals(2f, m.speedAtSource(10.0)); assertEquals(0.5f, m.speedAtSource(2000.0))
        assertEquals(2000L, m.nextChangeSrc(10.0)); assertEquals(null, m.nextChangeSrc(2500.0))
        assertEquals(2_000_000L, m.provider().getNextSpeedChangeTimeUs(0))
    }

    @Test fun curveAxisIsLogAndInvertible() {
        assertEquals(0.5f, speedToY(1f), 1e-6f)
        for (s in listOf(0.1f, 0.3f, 1f, 4f, 10f)) assertEquals(s, yToSpeed(speedToY(s)), 1e-3f * s)
    }
}

class SmoothDecimatorTest {
    @Test fun rampAcrossTargetHasNoGapsAndCapsFastParts() {
        val step = 33_333L
        val d = dev.clio.app.engine.FpsCap.Decimator.Smooth(step)
        // Output-time frames of a 30 fps source on a 5x -> 1x ramp.
        var t = 0.0; var last = -1L; var worst = 0L; var kept = 0; var firstSecond = 0
        for (i in 0 until 300) {
            val speed = 5.0 - 4.0 * (i / 300.0)
            t += 33_333 / speed
            val pts = t.toLong()
            if (d.keep(pts)) { if (last >= 0) worst = maxOf(worst, pts - last); last = pts; kept++; if (pts < 1_000_000) firstSecond++ }
        }
        org.junit.Assert.assertTrue("gap $worst", worst <= step * 3 / 2)
        org.junit.Assert.assertTrue("fast part capped: $firstSecond", firstSecond <= 40)
    }
}
