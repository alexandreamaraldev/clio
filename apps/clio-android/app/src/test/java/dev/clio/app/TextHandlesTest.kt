package dev.clio.app

import dev.clio.app.ui.screens.TextHandles
import org.junit.Assert.assertEquals
import org.junit.Test

class TextHandlesTest {
    @Test fun trimKeepsOppositeEdgeAndSnapsToFrames() {
        val (s, l) = TextHandles.trim(1f, 2f, left = true, deltaS = 0.51f)
        assertEquals(1.5f, s, 1e-3f); assertEquals(1.5f, l, 1e-3f) // 1.51 -> 45/30 frames
        val (s2, l2) = TextHandles.trim(1f, 2f, left = false, deltaS = -0.49f)
        assertEquals(1f, s2, 0f); assertEquals(1.5f, l2, 1e-3f)
    }

    @Test fun noUiClampingTheEngineOwnsLimits() {
        // Past zero / past the other edge is passed through; retime_text/retime_caption clamp it.
        assertEquals(-2.5f, TextHandles.trim(0.5f, 1f, true, -3f).first, 1e-3f)
        assertEquals(-3f, TextHandles.move(1f, 2f, -4f), 1e-3f)
    }

    @Test fun magnetsToPlayheadAndEdges() {
        assertEquals(3f, TextHandles.trim(1f, 1.95f, false, 0f, listOf(3f)).let { it.first + it.second }, 1e-3f)
        // move: the end edge lands near the anchor at 5 s
        assertEquals(3f, TextHandles.move(1f, 2f, 1.93f, listOf(5f)), 1e-3f)
    }

}
