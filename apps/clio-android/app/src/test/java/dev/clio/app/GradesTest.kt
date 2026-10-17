package dev.clio.app

import dev.clio.app.engine.Grades
import dev.clio.app.ui.screens.signedPct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GradesTest {
    private fun lut(id: String): Grades.Cube = Grades.Cube.parse(
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/luts/$id.cube") }
            .firstOrNull { it.isFile }?.readText() ?: File("src/main/assets/luts/$id.cube").readText())

    private fun graded(argb: Int, g: uniffi.clio_ffi.ColorGrade, cube: Grades.Cube? = null): Int {
        val px = intArrayOf(argb); Grades.applyTo(px, Grades.bake(g, cube)); return px[0]
    }
    private fun ch(p: Int, s: Int) = (p shr s) and 0xFF

    @Test fun neutralIsIdentity() {
        val plane = Grades.bake(Grades.NEUTRAL, null)
        val px = IntArray(4096) { (0xFF shl 24) or ((it * 4099) and 0xFFFFFF) }
        val before = px.copyOf()
        Grades.applyTo(px, plane)
        for (i in px.indices) for (s in intArrayOf(16, 8, 0)) assertTrue("px $i", abs(ch(px[i], s) - ch(before[i], s)) <= 1)
    }

    @Test fun planeLayoutMatchesMedia3() {
        // Media3 SingleColorLut: index = b + N·(g + N·r). Pure red input → red output at r = N-1.
        val n = Grades.N; val plane = Grades.bake(Grades.NEUTRAL, null)
        assertEquals(0xFFFF0000.toInt(), plane[0 + n * (0 + n * (n - 1))])
        assertEquals(0xFF0000FF.toInt(), plane[(n - 1) + n * (0 + n * 0)])
    }

    @Test fun everyPresetParsesAndPbIsGrey() {
        for ((id, _) in dev.clio.app.engine.LookPresets.all) assertEquals(17, lut(id).n)
        val p = graded(0xFFD04020.toInt(), Grades.NEUTRAL.copy(filter = "pb"), lut("pb"))
        assertTrue(abs(ch(p, 16) - ch(p, 8)) <= 2 && abs(ch(p, 8) - ch(p, 0)) <= 2)
    }

    @Test fun intensityMixesTowardOriginal() {
        val src = 0xFFD04020.toInt()
        val half = graded(src, Grades.NEUTRAL.copy(filter = "pb", intensity = 0.5f), lut("pb"))
        val full = graded(src, Grades.NEUTRAL.copy(filter = "pb", intensity = 1f), lut("pb"))
        assertTrue(ch(half, 16) in (ch(full, 16) + 1) until ch(src, 16))
    }

    @Test fun adjustmentsMoveTheRightWay() {
        val mid = 0xFF808080.toInt(); val n = Grades.NEUTRAL
        assertTrue(ch(graded(mid, n.copy(brightness = 0.5f)), 8) > 0x90)
        assertTrue(ch(graded(mid, n.copy(brightness = -0.5f)), 8) < 0x70)
        val warm = graded(mid, n.copy(temperature = 1f)); assertTrue(ch(warm, 16) > ch(warm, 0) + 30)
        val cool = graded(mid, n.copy(temperature = -1f)); assertTrue(ch(cool, 0) > ch(cool, 16) + 30)
        val c = 0xFFC08040.toInt()
        val desat = graded(c, n.copy(saturation = -1f)); assertTrue(abs(ch(desat, 16) - ch(desat, 0)) <= 2)
        val con = graded(0xFFC0C0C0.toInt(), n.copy(contrast = 1f)); assertTrue(ch(con, 8) > 0xD0)
        assertTrue(ch(graded(0xFFF0F0F0.toInt(), n.copy(highlights = -1f)), 8) < 0xD8)
        assertTrue(ch(graded(0xFF181818.toInt(), n.copy(shadows = 1f)), 8) > 0x30)
        // Highlights barely touch shadows and vice versa.
        assertTrue(abs(ch(graded(0xFF181818.toInt(), n.copy(highlights = -1f)), 8) - 0x18) <= 2)
    }

    @Test fun ptBrSignedPercent() {
        assertEquals("+25%", signedPct(0.25f))
        assertEquals("−40%", signedPct(-0.4f))
        assertEquals("0%", signedPct(0f))
    }

    private fun abs(x: Int) = kotlin.math.abs(x)
}
