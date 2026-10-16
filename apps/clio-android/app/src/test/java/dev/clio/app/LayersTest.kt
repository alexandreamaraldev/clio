package dev.clio.app

import dev.clio.app.engine.Layers
import dev.clio.app.engine.LayersMapping
import dev.clio.app.ui.screens.TransitionStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clio_ffi.Clip
import uniffi.clio_ffi.LayerTransform
import uniffi.clio_ffi.ProjectSummary
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.Track
import uniffi.clio_ffi.TrackKind
import uniffi.clio_ffi.Transition
import uniffi.clio_ffi.TransitionKind

/** JVM tests for the layer/transition glue the exporter relies on (no native engine needed). */
class LayersTest {
    private val id = LayerTransform(0f, 0f, 1f, 0f, 1f)
    private fun clip(id: Long, start: Long, dur: Long, tr: Transition = Layers.NO_TRANSITION, t: LayerTransform = this.id) =
        Clip(id.toULong(), "c$id", "c$id.mp4", start.toULong(), dur.toULong(), 0uL, 0uL, 0u, 0u, 1f, 1f, 0uL, 0uL, false, t, tr)

    private fun snap(main: List<Clip>, vararg rows: List<Clip>) = Snapshot(
        ProjectSummary("p", 1080u, 1920u, main.sumOf { it.durationMs.toLong() }.toULong(), (1 + rows.size).toUInt(), 0u),
        listOf(Track(1uL, TrackKind.VIDEO, "V", main)) + rows.mapIndexed { i, r -> Track((10 + i).toULong(), TrackKind.OVERLAY, "Camada ${i + 1}", r) },
        emptyList(), emptyList(), null, false, false, false, false,
    )

    @Test fun incomingTransitionComesFromPreviousClip() {
        val fade = Transition(TransitionKind.FADE, 500uL)
        val main = listOf(clip(1, 0, 2000, fade), clip(2, 2000, 3000))
        assertEquals(Layers.NO_TRANSITION, Layers.incoming(main, 0))
        assertEquals(fade, Layers.incoming(main, 1))
        assertTrue(Layers.hasTransition(main, 0))
        assertTrue(Layers.hasTransition(main, 1))
        assertFalse(Layers.hasTransition(listOf(clip(1, 0, 1000), clip(2, 1000, 1000)), 1))
    }

    @Test fun noExportEffectsWithoutTransitions() {
        assertTrue(Layers.exportEffects(clip(1, 0, 1000), Layers.NO_TRANSITION).isEmpty())
        assertEquals(2, Layers.exportEffects(clip(1, 0, 1000, Transition(TransitionKind.SLIDE, 400uL)), Layers.NO_TRANSITION).size)
    }

    @Test fun localClockRestartsAtFirstFramePerItem() {
        val c = Layers.LocalClock()
        assertEquals(0L, c.ms(7_000_000))
        assertEquals(33L, c.ms(7_033_366))
        assertEquals(1000L, c.ms(8_000_000))
    }

    @Test fun brightnessMatrixScalesRgbOnly() {
        val m = Layers.brightnessMatrix(0.25f)
        assertArrayEquals(floatArrayOf(0.25f, 0f, 0f, 0f, 0f, 0.25f, 0f, 0f, 0f, 0f, 0.25f, 0f, 0f, 0f, 0f, 1f), m, 0f)
    }

    @Test fun overlayClipsBottomRowFirst() {
        val s = snap(listOf(clip(1, 0, 5000)), listOf(clip(5, 0, 1000), clip(6, 2000, 500)), listOf(clip(7, 500, 1000)))
        assertEquals(listOf(5L, 6L, 7L), Layers.overlayClips(s).map { it.id.toLong() })
        assertEquals(listOf(1L), Layers.mainClips(s).map { it.id.toLong() })
    }

    @Test fun cutsSitAtClipEndsWithEngineMaximum() {
        val main = listOf(clip(1, 0, 2000, Transition(TransitionKind.ZOOM, 800uL)), clip(2, 2000, 5000), clip(3, 7000, 4000))
        val cuts = LayersMapping.cuts(snap(main))
        assertEquals(2, cuts.size)
        assertEquals(2f, cuts[0].atS, 1e-6f)
        assertEquals(TransitionStyle.Zoom, cuts[0].style)
        assertEquals(0.8f, cuts[0].durS, 1e-6f)
        assertEquals(2f, cuts[0].maxS, 1e-6f) // shorter neighbour
        assertEquals(7f, cuts[1].atS, 1e-6f)
        assertEquals(3f, cuts[1].maxS, 1e-6f) // MAX_TRANSITION_MS
        assertEquals(TransitionStyle.None, cuts[1].style)
    }

    @Test fun styleKindRoundTrip() {
        TransitionStyle.entries.forEach { assertEquals(it, LayersMapping.style(LayersMapping.kind(it))) }
        val t = LayersMapping.transform(dev.clio.app.ui.screens.UiTransform(0.1f, -0.2f, 0.4f, 30f, 0.5f))
        assertEquals(LayerTransform(0.1f, -0.2f, 0.4f, 30f, 0.5f), t)
    }
}
