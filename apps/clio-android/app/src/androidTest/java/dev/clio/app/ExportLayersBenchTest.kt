package dev.clio.app

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.Exporter
import dev.clio.app.ui.screens.CaptionStyle
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.LayerTransform
import uniffi.clio_ffi.TrackKind
import uniffi.clio_ffi.TransitionKind
import java.io.File

/** Export timing with a PIP video layer spanning a cut (logs CLIO-QA bench lines; not a pass/fail gate). */
@RunWith(AndroidJUnit4::class)
class ExportLayersBenchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun video(): Uri {
        val dst = File(ctx.cacheDir, "bench.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(dst)
    }

    private fun run(label: String, layers: Int): Long {
        val src = video().toString()
        val p = ClioProject("b", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        val a = p.appendClip(v, "a", src, 3000u); p.setClipSourceInfo(a, 6000u, 1280u, 720u)
        val b = p.appendClip(v, "b", src, 3000u); p.setClipSourceInfo(b, 6000u, 1280u, 720u)
        p.setTransition(a, TransitionKind.FADE, 600u)
        repeat(layers) { i ->
            val o = p.addOverlay("pip$i", src, 0u, 6000u); p.setClipSourceInfo(o, 6000u, 1280u, 720u)
            p.setClipTransform(o, LayerTransform(0.4f - i * 0.8f, 0.5f, 0.4f, 10f, 0.9f))
        }
        val t0 = SystemClock.elapsedRealtime()
        runBlocking { Exporter(ctx).export(p.snapshot(), ExportSpec(label, 720, 1280, 30), CaptionStyle.Clean) {} }
        val ms = SystemClock.elapsedRealtime() - t0
        val line = "CLIO-QA bench $label: ${ms}ms"
        println(line); android.util.Log.i("ClioQA", line)
        return ms
    }

    @Test fun bench() {
        run("no-layers", 0)
        run("1-pip", 1)
        run("2-pip", 2)
    }
}
