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
