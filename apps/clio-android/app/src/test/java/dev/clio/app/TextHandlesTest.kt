package dev.clio.app

import dev.clio.app.engine.Engine
import dev.clio.app.ui.screens.TextHandles
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.clio_ffi.CaptionWord

class TextHandlesTest {
    @Test fun trimKeepsOppositeEdgeAndSnapsToFrames() {
        val (s, l) = TextHandles.trim(1f, 2f, left = true, deltaS = 0.51f)
        assertEquals(1.5f, s, 1e-3f); assertEquals(1.5f, l, 1e-3f) // 1.51 -> 45/30 frames
        val (s2, l2) = TextHandles.trim(1f, 2f, left = false, deltaS = -0.49f)
        assertEquals(1f, s2, 0f); assertEquals(1.5f, l2, 1e-3f)
    }

    @Test fun trimClampsToMinimumAndZero() {
        assertEquals(0f, TextHandles.trim(0.5f, 1f, true, -3f).first, 0f)
        val (s, l) = TextHandles.trim(1f, 1f, true, 5f)
        assertEquals(TextHandles.MIN_S, l, 1e-3f); assertEquals(1.9f, s, 1e-3f)
        assertEquals(TextHandles.MIN_S, TextHandles.trim(1f, 1f, false, -5f).second, 1e-3f)
    }

    @Test fun magnetsToPlayheadAndEdges() {
        assertEquals(3f, TextHandles.trim(1f, 1.95f, false, 0f, listOf(3f)).let { it.first + it.second }, 1e-3f)
        // move: the end edge lands near the anchor at 5 s
        assertEquals(3f, TextHandles.move(1f, 2f, 1.93f, listOf(5f)), 1e-3f)
        assertEquals(0f, TextHandles.move(1f, 2f, -4f), 0f)
    }

    @Test fun retimeCaptionShiftsOrClipsWords() {
        val c = Engine.Cap(1000, 2000, "oi mundo", listOf(CaptionWord(1000u, 1400u, "oi"), CaptionWord(1500u, 2000u, "mundo")))
        val moved = TextHandles.retimeCaption(listOf(c), 0, 3000, 4000)[0]
        assertEquals(listOf(3000uL, 3500uL), moved.words.map { it.startMs })
        val trimmed = TextHandles.retimeCaption(listOf(c), 0, 1450, 2000)[0]
        assertEquals(listOf("mundo"), trimmed.words.map { it.text })
        assertEquals(1500uL, trimmed.words[0].startMs)
    }
}
