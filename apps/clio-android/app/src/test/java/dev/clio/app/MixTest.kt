package dev.clio.app

import dev.clio.app.engine.Aspect
import dev.clio.app.engine.Mix
import org.junit.Assert.assertEquals
import org.junit.Test

class MixTest {
    @Test fun gainEnvelope() {
        assertEquals(0f, Mix.gain(0, 1000, 1f, 200, 0, false), 1e-4f)
        assertEquals(0.5f, Mix.gain(100, 1000, 1f, 200, 0, false), 1e-4f)
        assertEquals(1.5f, Mix.gain(500, 1000, 1.5f, 200, 200, false), 1e-4f)
        assertEquals(0.25f, Mix.gain(950, 1000, 0.5f, 0, 100, false), 1e-4f)
        assertEquals(0f, Mix.gain(500, 1000, 1f, 0, 0, true), 0f)
    }

    @Test fun canvasSizes() {
        assertEquals(1080 to 1920, Mix.canvas(9, 16, 1080))
        assertEquals(3840 to 2160, Mix.canvas(16, 9, 2160))
        assertEquals(720 to 720, Mix.canvas(1, 1, 720))
        assertEquals(1080 to 1350, Mix.canvas(4, 5, 1080))
        assertEquals(Aspect.Feed, Aspect.of(1080, 1350))
    }

    @Test fun fitScaleForBlurredBackground() {
        val (sx, sy) = Mix.fitScale(1920, 1080, 1080, 1920)
        assertEquals(1f, sx, 1e-4f)
        assertEquals(0.3164f, sy, 1e-3f)
    }

    @Test fun trimClamps() {
        // Right handle cannot pass the end of a 4 s source.
        assertEquals(0f to 4f, Mix.trim(0f, 3f, 1f, 4f, left = false, deltaS = 5f))
        // Left handle at 2x consumes source twice as fast.
        val (i, l) = Mix.trim(0f, 2f, 2f, 4f, left = true, deltaS = 0.5f)
        assertEquals(1f, i, 1e-4f); assertEquals(1.5f, l, 1e-4f)
        // Can't drag left of the source start, or below the minimum length.
        assertEquals(0f to 2f, Mix.trim(0f, 2f, 1f, 4f, left = true, deltaS = -1f))
        assertEquals(0.1f, Mix.trim(0f, 2f, 1f, 4f, left = true, deltaS = 9f).second, 1e-4f)
        assertEquals(0.5f, Mix.snap(0.51f), 1e-4f)
    }
}
