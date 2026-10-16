package dev.clio.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.Exporter
import dev.clio.app.ui.screens.CaptionStyle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.LayerTransform
import uniffi.clio_ffi.TrackKind
import uniffi.clio_ffi.TransitionKind
import java.io.File
import kotlin.math.abs

/** Export frame rate on the real MP4 (sample count / time), with and without layers/transitions. */
@RunWith(AndroidJUnit4::class)
class ExportFpsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun video(): String {
        val dst = File(ctx.cacheDir, "fps.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(dst).toString()
    }

    private fun png(): String {
        val f = File(ctx.cacheDir, "fps-layer.png")
        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }.let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return Uri.fromFile(f).toString()
    }

    @Test fun requestedFrameRateIsHonoured() {
        val src = video(); val img = png()
        val cases: List<Triple<String, Int, ClioProject.(ULong, ULong) -> Unit>> = listOf(
            Triple("plain", 24) { _, _ -> },
            Triple("fade", 24) { a, _ -> setTransition(a, TransitionKind.FADE, 600u) },
            Triple("image-layer", 24) { _, _ -> val o = addOverlay("l", img, 1000u, 3000u); setClipTransform(o, LayerTransform(0.5f, 0f, 0.3f, 0f, 1f)) },
            Triple("60-capped", 60) { _, _ -> },
        )
        val out = mutableListOf<String>()
        for ((name, fps, edit) in cases) {
            val p = ClioProject("fps", 1080u, 1920u)
            val v = p.addTrack(TrackKind.VIDEO, "V")
            val a = p.appendClip(v, "a", src, 3000u); p.setClipSourceInfo(a, 6000u, 1280u, 720u)
            val b = p.appendClip(v, "b", src, 3000u); p.setClipSourceInfo(b, 6000u, 1280u, 720u)
            p.edit(a, b)
            val uri = runBlocking { withTimeout(180_000) { Exporter(ctx).export(p.snapshot(), ExportSpec("fps-$name", 720, 1280, fps), CaptionStyle.Clean) {} } }
            val got = ExportProbe.info(ctx, uri).fps
            val want = minOf(fps, 30).toFloat() // capped by the 30 fps source
            val line = "CLIO-QA fps $name: requested $fps, got ${"%.2f".format(got)} -> " + if (abs(got - want) <= 1.5f) "PASS" else "FAIL"
            println(line); android.util.Log.i("ClioQA", line); out += line
        }
        assertTrue(out.joinToString("\n"), out.none { it.endsWith("FAIL") })
    }
}
