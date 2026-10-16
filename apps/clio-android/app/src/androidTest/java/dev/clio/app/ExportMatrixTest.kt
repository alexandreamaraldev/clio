package dev.clio.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.Exporter
import dev.clio.app.ui.screens.CaptionStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.TrackKind
import java.io.File
import kotlin.math.abs

/** Exports one feature at a time straight through the engine + Exporter, to pinpoint export bugs. */
@RunWith(AndroidJUnit4::class)
class ExportMatrixTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun video(): Uri {
        val dst = File(ctx.cacheDir, "matrix.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(dst)
    }

    @Test fun matrix() {
        val src = video().toString()
        val variants: List<Pair<String, ClioProject.(ULong) -> Unit>> = listOf(
            "plain" to { _ -> },
            "speed2x" to { c -> setClipSpeed(c, 2f) },
            "fade" to { c -> setClipAudio(c, 0.8f, 1000u, 1000u, false) },
            "blur" to { _ -> setFraming(false, true) },
            "fill" to { _ -> setFraming(true, false) },
            "split" to { c -> splitClip(c, 2500u) },
            "text" to { _ -> val t = addTrack(TrackKind.TEXT, "T"); insertClip(t, "Oi", "", 500u, 2000u) },
            // Combinations: processors on only some items of a multi-clip sequence.
            "split+speedA" to { c -> splitClip(c, 2500u); setClipSpeed(c, 2f) },
            "split+speedB" to { c -> val r = splitClip(c, 2500u); setClipSpeed(r, 2f) },
            "split+fadeA" to { c -> splitClip(c, 2500u); setClipAudio(c, 1f, 500u, 0u, false) },
            "split+fadeB" to { c -> val r = splitClip(c, 2500u); setClipAudio(r, 1f, 500u, 0u, false) },
            "dup+speed+fade" to { c -> val d = duplicateClip(c); setClipSpeed(d, 2f); setClipAudio(c, 1f, 1000u, 0u, false) },
            "e2e-like" to { c ->
                trimClip(c, 0u, 4500u); val r = splitClip(c, 1500u); val d = duplicateClip(c)
                moveClip(r, 0u); setClipSpeed(r, 2f); setClipAudio(r, 1f, 400u, 0u, false); setFraming(false, true)
                val t = addTrack(TrackKind.TEXT, "T"); insertClip(t, "Oi", "", 500u, 2000u); check(d > 0u)
            },
        )
        val results = mutableListOf<String>()
        for (drop in listOf(true)) for ((name, edit) in variants) {
            val label = "$name${if (drop) "+framedrop" else ""}"
            val p = ClioProject("m", 1080u, 1920u)
            val v = p.addTrack(TrackKind.VIDEO, "V")
            val c = p.appendClip(v, "c", src, 6000u)
            p.setClipSourceInfo(c, 6000u, 1280u, 720u)
            p.edit(c)
            val snap = p.snapshot()
            val ex = Exporter(ctx).apply { frameDrop = drop }
            val r = runCatching {
                val uri = runBlocking { kotlinx.coroutines.withTimeout(180_000) { ex.export(snap, ExportSpec(label, 720, 1280, 30), CaptionStyle.Clean) {} } }
                val m = MediaMetadataRetriever(); m.setDataSource(ctx, uri)
                val d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                val a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                val size = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) + "x" + m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT); m.release()
                check(size == "720x1280") { "size $size" }
                check(abs(d - snap.summary.durationMs.toLong()) <= 350) { "duration $d vs ${snap.summary.durationMs}" }
                check(a == "yes") { "no audio" }
                "${d}ms"
            }
            val line = "CLIO-QA matrix $label: " + r.fold({ "PASS $it" }, { e -> "FAIL " + generateSequence(e) { it.cause }.take(4).joinToString(" <- ") { it.toString() } })
            println(line); android.util.Log.i("ClioQA", line); results += line
        }
        assertTrue(results.joinToString("\n"), results.none { "FAIL" in it })
    }
}
