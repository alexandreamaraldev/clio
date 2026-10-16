package dev.clio.app

import dev.clio.app.engine.GradeSchedule
import org.junit.Assert.assertSame
import org.junit.Test

class PreviewGradesTest {
    private val id = IntArray(1); private val a = IntArray(1); private val b = IntArray(1)

    @Test fun cutSwitchesOnPtsNotTick() {
        var approx = 0L
        val s = GradeSchedule { approx }
        s.publish(longArrayOf(0, 2_000_000), arrayOf(a, b))
        s.seeked(1_900_000)
        val base = 1_000_000_000_000L
        assertSame(a, s.planeAt(base + 1_900_000, id))
        // The UI tick still says clip 0, but the frame after the cut gets clip 1's grade.
        assertSame(b, s.planeAt(base + 2_000_000, id))
    }

    @Test fun reanchorsOnDiscontinuity() {
        var approx = 500_000L
        val s = GradeSchedule { approx }
        s.publish(longArrayOf(0, 2_000_000), arrayOf(a, b))
        assertSame(a, s.planeAt(10_000_000, id))
        approx = 2_500_000
        assertSame(b, s.planeAt(90_000_000, id))
    }

    @Test fun emptyPlanFallsBack() {
        assertSame(id, GradeSchedule { 0 }.planeAt(5, id))
    }
}
