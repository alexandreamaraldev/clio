package dev.clio.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.Exporter
import dev.clio.app.engine.Grades
import dev.clio.app.ui.screens.CaptionStyle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.TrackKind
import java.io.File

/**
 * Filters and colour adjustments through the real export: P&B on clip A must come out grey,
 * +60% brightness on clip B brighter, and the ungraded export keeps its colour. Pixels are
 * read back from the MP4.
 */
@RunWith(AndroidJUnit4::class)
class ExportGradeE2ETest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun video(): String {
        val dst = File(ctx.cacheDir, "grade.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(dst).toString()
    }

    /** (mean chroma spread max-min, mean luma) over the centre of the frame at [ms]. */
    private fun stats(uri: Uri, ms: Long): Pair<Double, Double> {
        val m = MediaMetadataRetriever(); m.setDataSource(ctx, uri)
        val f = m.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false); m.release()
        var spread = 0.0; var luma = 0.0; var n = 0
        for (y in f.height / 4 until f.height * 3 / 4 step 4) for (x in f.width / 4 until f.width * 3 / 4 step 4) {
            val p = f.getPixel(x, y); val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            spread += maxOf(r, g, b) - minOf(r, g, b); luma += 0.2126 * r + 0.7152 * g + 0.0722 * b; n++
        }
        return spread / n to luma / n
    }

    private fun export(src: String, name: String, edit: ClioProject.(ULong, ULong) -> Unit): Uri {
        val p = ClioProject("grade", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        val a = p.appendClip(v, "a", src, 3000u); p.setClipSourceInfo(a, 6000u, 1280u, 720u)
        val b = p.appendClip(v, "b", src, 3000u); p.setClipSourceInfo(b, 6000u, 1280u, 720u)
        p.edit(a, b)
        return runBlocking { withTimeout(180_000) { Exporter(ctx).export(p.snapshot(), ExportSpec("grade-$name", 720, 1280, 30), CaptionStyle.Clean) {} } }
    }

    @Test fun filterAndAdjustmentsArePainted() {
        val src = video()
        val plain = export(src, "plain") { _, _ -> }
        val graded = export(src, "graded") { a, b ->
            setClipGrade(a, Grades.NEUTRAL.copy(filter = "pb"))
            setClipGrade(b, Grades.NEUTRAL.copy(brightness = 0.6f))
        }
        val (plainSpreadA, _) = stats(plain, 1500); val (_, plainLumaB) = stats(plain, 4500)
        val (pbSpread, _) = stats(graded, 1500); val (_, brightLuma) = stats(graded, 4500)
        val line = "CLIO-QA grade: spread plain ${"%.1f".format(plainSpreadA)} vs P&B ${"%.1f".format(pbSpread)}; " +
            "luma plain ${"%.1f".format(plainLumaB)} vs +60% ${"%.1f".format(brightLuma)}"
        println(line); android.util.Log.i("ClioQA", line)
        assertTrue("P&B not grey: $line", pbSpread < 6.0)
        assertTrue("P&B should differ from plain: $line", plainSpreadA < 6.0 || pbSpread < plainSpreadA * 0.5)
        assertTrue("brightness not applied: $line", brightLuma > plainLumaB + 12)
    }

    // --- every preset and every slider against the CPU reference (Grades.grade) ---

    private val NEUTRAL_TOL = 3

    /** Swatch bands of the synthetic still: grey, red, skin, blue (top to bottom). */
    private val swatches = intArrayOf(0x808080, 0xC8503C, 0xDCAA8C, 0x325AB4)

    private fun swatchStill(): String {
        val w = 720; val h = 1280
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b); val paint = android.graphics.Paint()
        swatches.forEachIndexed { i, rgb -> paint.color = 0xFF000000.toInt() or rgb; c.drawRect(0f, (i * h / 4).toFloat(), w.toFloat(), ((i + 1) * h / 4).toFloat(), paint) }
        val f = File(ctx.cacheDir, "swatches.png"); f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f).toString()
    }

    /** Mean RGB of a 48x48 patch at the centre of swatch band [i]. */
    private fun patch(f: Bitmap, i: Int): IntArray {
        val cx = f.width / 2; val cy = f.height * (2 * i + 1) / 8; var r = 0; var g = 0; var b = 0; var n = 0
        for (y in cy - 24 until cy + 24) for (x in cx - 24 until cx + 24) { val p = f.getPixel(x, y); r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++ }
        return intArrayOf(r / n, g / n, b / n)
    }

    private fun reference(rgb: Int, g: uniffi.clio_ffi.ColorGrade, lib: Grades.Library): IntArray {
        val c = floatArrayOf(((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f)
        Grades.grade(c, g, lib.cube(g.filter))
        return IntArray(3) { (c[it] * 255f + 0.5f).toInt() }
    }

    /**
     * KNOWN BUG (PR #5, colour range): an ungraded still exports darker (grey 128 -> 115, blue
     * 50,90,180 -> 33,73,173), i.e. a full/limited range mismatch in the export path. This test is
     * strict on purpose and fails until the root cause is fixed; do not loosen it.
     */
    @Test fun everyPresetAndSliderMatchesReference() {
        val n = Grades.NEUTRAL
        val cases = listOf("neutral" to n) +
            dev.clio.app.engine.LookPresets.all.map { (id, _) -> id to n.copy(filter = id) } +
            listOf(
                "vivido@50%" to n.copy(filter = "vivido", intensity = 0.5f),
                "brightness+0.6" to n.copy(brightness = 0.6f), "brightness-0.6" to n.copy(brightness = -0.6f),
                "contrast+0.8" to n.copy(contrast = 0.8f), "saturation-1" to n.copy(saturation = -1f),
                "saturation+0.8" to n.copy(saturation = 0.8f), "temperature+1" to n.copy(temperature = 1f),
                "temperature-1" to n.copy(temperature = -1f), "highlights-1" to n.copy(highlights = -1f),
                "shadows+1" to n.copy(shadows = 1f),
            )
        // One export: one 1 s still clip per case, each with its own grade.
        val still = swatchStill()
        val p = ClioProject("grade-ref", 720u, 1280u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        cases.forEach { (name, g) -> val id = p.appendClip(v, name, still, 1000u); p.setClipSourceInfo(id, 0u, 720u, 1280u); p.setClipGrade(id, g) }
        val uri = runBlocking { withTimeout(240_000) { Exporter(ctx).export(p.snapshot(), ExportSpec("grade-ref", 720, 1280, 30), CaptionStyle.Clean) {} } }

        val lib = Grades.Library(ctx); val tol = 6
        val m = MediaMetadataRetriever(); m.setDataSource(ctx, uri)
        val fails = mutableListOf<String>()
        try {
            cases.forEachIndexed { k, (name, g) ->
                val f = m.getFrameAtTime((k * 1000L + 500) * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
                swatches.forEachIndexed { i, rgb ->
                    val got = patch(f, i); val want = reference(rgb, g, lib)
                    val err = (0..2).maxOf { kotlin.math.abs(got[it] - want[it]) }
                    val line = "CLIO-QA grade-ref $name swatch$i: got ${got.joinToString(",")} want ${want.joinToString(",")} err $err"
                    android.util.Log.i("ClioQA", line)
                    // Ungraded output must match its input closely; graded output must match the reference.
                    if (err > (if (name == "neutral") NEUTRAL_TOL else tol)) fails += line
                }
            }
        } finally { m.release() }
        assertTrue("${fails.size} patches off by > $tol:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    // --- HDR (HLG) sources: CI pushes /data/local/tmp/clio-hlg.mp4 (HEVC Main10, BT.2020, arib-std-b67) ---

    private fun hlg(): String {
        val dst = File(ctx.cacheDir, "hlg.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-hlg.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        assertTrue("HLG fixture missing: qa/run-device-qa.sh pushes /data/local/tmp/clio-hlg.mp4", dst.length() > 10_000)
        return Uri.fromFile(dst).toString()
    }

    /** Valid MP4: duration, 720x1280 (rotation-aware), and a video frame that decodes. */
    private fun assertValid(uri: Uri, expectedMs: Long, label: String) {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(ctx, uri)
            val d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val vw = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val vh = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val size = if (rot % 180 != 0) vh to vw else vw to vh
            val frames = listOf(200L, expectedMs / 2, expectedMs - 300).map { m.getFrameAtTime(it * 1000, MediaMetadataRetriever.OPTION_CLOSEST) }
            val line = "CLIO-QA grade-hdr $label: ${size.first}x${size.second} ${d}ms frames=${frames.map { it != null }}"
            android.util.Log.i("ClioQA", line)
            assertEquals("$label size", 720 to 1280, size)
            assertTrue("$label duration $d vs $expectedMs", kotlin.math.abs(d - expectedMs) <= 350)
            assertTrue("$label video frames not decodable: $line", frames.all { it != null && it.width > 0 })
        } finally { m.release() }
    }

    @Test fun hdrSourcesExportWithGrades() {
        val hdr = hlg(); val sdr = video()
        // 1) One graded HLG clip.
        val p1 = ClioProject("hdr-graded", 1080u, 1920u)
        val v1 = p1.addTrack(TrackKind.VIDEO, "V")
        val h = p1.appendClip(v1, "hlg", hdr, 3000u); p1.setClipSourceInfo(h, 3000u, 1280u, 720u)
        p1.setClipGrade(h, Grades.NEUTRAL.copy(filter = "cinema", brightness = 0.2f))
        val u1 = runBlocking { withTimeout(240_000) { Exporter(ctx).export(p1.snapshot(), ExportSpec("hdr-graded", 720, 1280, 30), CaptionStyle.Clean) {} } }
        assertValid(u1, 3000, "graded-hlg")
        // 2) Graded SDR clip followed by an ungraded HLG clip on the same timeline.
        val p2 = ClioProject("hdr-mixed", 1080u, 1920u)
        val v2 = p2.addTrack(TrackKind.VIDEO, "V")
        val s = p2.appendClip(v2, "sdr", sdr, 3000u); p2.setClipSourceInfo(s, 6000u, 1280u, 720u)
        val h2 = p2.appendClip(v2, "hlg", hdr, 3000u); p2.setClipSourceInfo(h2, 3000u, 1280u, 720u)
        p2.setClipGrade(s, Grades.NEUTRAL.copy(filter = "pb"))
        val u2 = runBlocking { withTimeout(240_000) { Exporter(ctx).export(p2.snapshot(), ExportSpec("hdr-mixed", 720, 1280, 30), CaptionStyle.Clean) {} } }
        assertValid(u2, 6000, "graded-sdr+hlg")
    }
}
