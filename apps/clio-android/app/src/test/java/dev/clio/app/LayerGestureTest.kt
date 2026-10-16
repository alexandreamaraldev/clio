package dev.clio.app

import dev.clio.app.ui.screens.PtBr
import dev.clio.app.ui.screens.UiLayer
import dev.clio.app.ui.screens.UiTransform
import dev.clio.app.ui.screens.layersInWindow
import dev.clio.app.ui.screens.panToCanvas
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerGestureTest {
    @Test fun panUnrotatedMapsToCanvasAxes() {
        val (dx, dy) = panToCanvas(50f, 0f, 0f, 100f, 200f)
        assertEquals(1f, dx, 1e-5f); assertEquals(0f, dy, 1e-5f)
        assertEquals(-0.5f, panToCanvas(0f, 50f, 0f, 100f, 200f).second, 1e-5f) // screen down = canvas -y
    }

    @Test fun panOnRotatedLayerFollowsTheFinger() {
        // Layer rotated 90° CCW: its local +x axis points up on screen, so a local +x drag moves it up.
        val (dx, dy) = panToCanvas(100f, 0f, 90f, 200f, 200f)
        assertEquals(0f, dx, 1e-4f); assertEquals(1f, dy, 1e-4f)
    }

    @Test fun windowPreloadsHalfASecond() {
        val l = UiLayer(1, "a", "s", 2000, 4000, 0, 1f, false, 0, UiTransform())
        assertEquals(0, layersInWindow(listOf(l), 1400).size)
        assertEquals(1, layersInWindow(listOf(l), 1500).size)
        assertEquals(0, layersInWindow(listOf(l), 4000).size)
    }

    @Test fun ptBrFormatting() {
        assertEquals("45%", PtBr.pct(0.45f))
        assertEquals("0,5", PtBr.dec(0.5f))
        assertEquals("-120%", PtBr.pct(-1.2f))
    }
}
