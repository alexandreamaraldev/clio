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
}
