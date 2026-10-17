package dev.clio.app

import dev.clio.app.engine.CurveRetime
import dev.clio.app.engine.SpeedMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveRetimePlannerTest {
    /** 30 fps source: 2 s at 4x then 2 s at 0.25x (span 4000 ms -> 500 + 8000 ms). */
    private val m = SpeedMap(listOf(0L, 2000L), listOf(4f, 0.25f), 4000)

    private fun run(lastSrcMs: Long, fps: Float = 30f): List<Long> {
        val p = CurveRetime.Planner(1e6 / fps, (m.durationMs * 1000).toLong()) { m.timelineAt(it / 1000.0) * 1000 }
        val base = 5_000_000L // global sequence offset
        val out = ArrayList<Long>()
        var s = 0L
        while (s <= lastSrcMs * 1000) { out += p.onFrame(base + s); s += 33_333 }
        out += p.onEnd()
        return out.map { it - base }
    }

    @Test fun everySlotIsFilledToTheEngineEnd() {
        val out = run(3967)
        val step = 1e6 / 30
        assertTrue("slots ${out.size}", kotlin.math.abs(out.size - 255) <= 1)
        out.zipWithNext().forEach { (a, b) -> assertTrue("gap $a->$b", b - a <= step * 1.01 + 1) }
        assertEquals(0L, out.first())
        assertTrue("ends within 1 frame of 8500 ms: ${out.last()}", 8_500_000 - out.last() in 0..34_000)
    }

    @Test fun shortVideoTrackStillFillsTheClip() {
        // Video track ends 23 ms before the probed span: the last frame is held to the end.
        val out = run(3933)
        assertTrue(8_500_000 - out.last() in 0..34_000)
    }

    @Test fun neverEmitsAtOrPastTheNextClipStart() {
        // Source frames running long past the clip (and an accelerating map) must not spill into the next clip.
        val p = CurveRetime.Planner(1e6 / 30, 1_000_000) { it * 3.0 }
        val out = ArrayList<Long>()
        for (i in 0..120) out += p.onFrame(5_000_000L + i * 33_333)
        out += p.onEnd()
        assertTrue(out.isNotEmpty())
        assertTrue("past end: ${out.last()}", out.all { it < 6_000_000 })
    }
}
