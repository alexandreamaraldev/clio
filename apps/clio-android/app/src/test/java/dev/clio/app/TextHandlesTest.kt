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

    @Test fun dragPastZeroKeepsTiming() {
        // Move: start stops at 0, length kept.
        assertEquals(0f, TextHandles.move(1f, 2f, -4f), 0f)
        // Left trim: start stops at 0, end kept.
        val (s, l) = TextHandles.trim(0.5f, 1f, true, -3f)
        assertEquals(0f, s, 0f); assertEquals(1.5f, l, 1e-4f)
        // Controller guard: an unclamped window is shifted, never collapsed.
        assertEquals(0L to 2000L, dev.clio.app.engine.TextRetime.window(-3000, -1000))
        assertEquals(500L to 1500L, dev.clio.app.engine.TextRetime.window(500, 1500))
    }

    @Test fun snapsBackToOwnEdges() {
        val a = TextHandles.ownEdges(1.234f, 1f)
        assertEquals(1.234f, TextHandles.move(1.234f, 1f, 0.05f, a), 0f)
        assertEquals(2.234f, TextHandles.trim(1.234f, 1f, false, -0.04f, a).let { it.first + it.second }, 1e-4f)
    }

    @Test fun gridFollowsFps() {
        TextHandles.fps = 24
        try { assertEquals(1f / 24 * 25, TextHandles.snapTo(1.04f, emptyList()), 1e-4f) } finally { TextHandles.fps = 30 }
    }

    @Test fun magnetsToPlayheadAndEdges() {
        assertEquals(3f, TextHandles.trim(1f, 1.95f, false, 0f, listOf(3f)).let { it.first + it.second }, 1e-3f)
        // move: the end edge lands near the anchor at 5 s
        assertEquals(3f, TextHandles.move(1f, 2f, 1.93f, listOf(5f)), 1e-3f)
    }

}
