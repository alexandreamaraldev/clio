package dev.clio.app

import dev.clio.app.engine.LayerLimits
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Kotlin LayerLimits must match the Rust constants (the engine is the source of truth). */
class LayerLimitsTest {
    private val rust: String by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "crates/ffi-mobile/src/layers.rs") }.first { it.isFile }.readText()
    }

    private fun const(name: String): Double =
        Regex("""pub const $name: \w+ = ([0-9.]+);""").find(rust)?.groupValues?.get(1)?.toDouble()
            ?: error("$name not found in layers.rs")

    @Test fun mirrorsEngine() {
        assertEquals(const("MIN_LAYER_SCALE"), LayerLimits.MIN_LAYER_SCALE.toDouble(), 1e-6)
        assertEquals(const("MAX_LAYER_SCALE"), LayerLimits.MAX_LAYER_SCALE.toDouble(), 1e-6)
        assertEquals(const("MAX_LAYER_OFFSET"), LayerLimits.MAX_LAYER_OFFSET.toDouble(), 1e-6)
        assertEquals(const("MIN_TRANSITION_MS"), LayerLimits.MIN_TRANSITION_MS.toDouble(), 0.0)
        assertEquals(const("MAX_TRANSITION_MS"), LayerLimits.MAX_TRANSITION_MS.toDouble(), 0.0)
    }
}

class LayerAnchorTest {
    private fun centre(pos: Float, half: Float): Float {
        val (bg, a, _) = dev.clio.app.engine.Layers.anchor(pos, half)
        return bg - a * half
    }

    @Test fun insideCanvasUsesBackgroundAnchorOnly() {
        val (bg, a, on) = dev.clio.app.engine.Layers.anchor(0.4f, 0.2f)
        assertEquals(0.4f, bg, 1e-6f); assertEquals(0f, a, 1e-6f); assertEquals(true, on)
    }

    @Test fun beyondEdgeShiftsWithOverlayAnchor() {
        assertEquals(1.5f, centre(1.5f, 0.8f), 1e-5f)
        assertEquals(-1.3f, centre(-1.3f, 0.5f), 1e-5f)
        // centre more than one half-size past the edge = fully off canvas
        assertEquals(false, dev.clio.app.engine.Layers.anchor(2f, 0.4f).third)
        assertEquals(true, dev.clio.app.engine.Layers.anchor(1.2f, 0.4f).third)
    }
}
