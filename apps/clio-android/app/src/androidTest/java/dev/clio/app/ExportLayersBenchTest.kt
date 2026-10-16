package dev.clio.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.Exporter
import dev.clio.app.engine.Layers
import dev.clio.app.ui.screens.CaptionStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.LayerTransform
import uniffi.clio_ffi.TrackKind
import uniffi.clio_ffi.TransitionKind
import java.io.File
import java.nio.ByteBuffer

/**
 * Export timing with PIP video layers over a cut. Logs `CLIO-QA bench` lines with wall time and
 * layer decodes per output frame (a sequential decoder should stay close to 1.0).
 */
@RunWith(AndroidJUnit4::class)
class ExportLayersBenchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun video(): File {
        val dst = File(ctx.cacheDir, "bench.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        return dst
    }

    private val CEILING_MS = 240_000L

    private fun log(line: String) { println(line); android.util.Log.i("ClioQA", line) }

    private fun project(src: String, layers: Int, w: UInt = 1280u, h: UInt = 720u): ClioProject {
        val p = ClioProject("b", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        val a = p.appendClip(v, "a", src, 3000u); p.setClipSourceInfo(a, 6000u, 1280u, 720u)
        val b = p.appendClip(v, "b", src, 3000u); p.setClipSourceInfo(b, 6000u, 1280u, 720u)
        p.setTransition(a, TransitionKind.FADE, 600u)
        repeat(layers) { i ->
            val o = p.addOverlay("pip$i", src, 0u, 6000u); p.setClipSourceInfo(o, 6000u, w, h)
            p.setClipTransform(o, LayerTransform(0.4f - i * 0.8f, 0.5f, 0.4f, 10f, 0.9f))
        }
        return p
    }

    private fun run(label: String, p: ClioProject): Exporter.LayerStats {
        val ex = Exporter(ctx)
        val t0 = SystemClock.elapsedRealtime()
        val snap = p.snapshot()
        val uri = runBlocking { ex.export(snap, ExportSpec(label, 720, 1280, 30), CaptionStyle.Clean) {} }
        val ms = SystemClock.elapsedRealtime() - t0
        // Real output: size, duration, fps, audio. Ceiling is generous (swiftshader emulator, ~10x headroom).
        val info = ExportProbe.info(ctx, uri)
        log("CLIO-QA bench $label output: $info")
        assertEquals("$label size", 720 to 1280, info.width to info.height)
        assertTrue("$label duration ${info.durationMs} vs ${snap.summary.durationMs}", kotlin.math.abs(info.durationMs - snap.summary.durationMs.toLong()) <= 350)
        assertEquals("$label fps", 30f, info.fps, 1.5f)
        assertTrue("$label audio track", info.hasAudio)
        assertTrue("$label export took ${ms}ms (ceiling $CEILING_MS)", ms < CEILING_MS)
        val st = ex.lastLayerStats
        val perFrame = if (st.requestedFrames > 0) st.decodedFrames.toFloat() / st.requestedFrames else 0f
        log("CLIO-QA bench $label: ${ms}ms, layer decodes ${st.decodedFrames} / output-frame requests ${st.requestedFrames} = ${"%.2f".format(perFrame)} per frame")
        return st
    }

    @Test fun bench() {
        val src = Uri.fromFile(video()).toString()
        run("no-layers", project(src, 0))
        val one = run("1-pip", project(src, 1))
        run("2-pip", project(src, 2))
        // Sequential decode: ~1 decoded frame per shown frame (plus a GOP after the one seek per item).
        assertTrue("decodes per frame ${one.decodedFrames}/${one.requestedFrames}", one.decodedFrames <= one.requestedFrames * 2 + 60)
    }

    /** Remux the test clip with a 90° rotation flag (how phones store portrait video). */
    private fun portrait(src: File): File {
        val out = File(ctx.cacheDir, "portrait.mp4")
        val ex = MediaExtractor(); ex.setDataSource(src.path)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(track)
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mux.setOrientationHint(90)
        val t = mux.addTrack(ex.getTrackFormat(track)); mux.start()
        val buf = ByteBuffer.allocate(4 shl 20); val info = MediaCodec.BufferInfo()
        while (true) {
            val n = ex.readSampleData(buf, 0); if (n < 0) break
            info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(t, buf, info); ex.advance()
        }
        mux.stop(); mux.release(); ex.release()
        return out
    }

    @Test fun portraitLayer() {
        val pf = portrait(video())
        val m = MediaMetadataRetriever(); m.setDataSource(pf.path)
        assertEquals("90", m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)); m.release()
        val src = Uri.fromFile(pf).toString()
        val p = project(Uri.fromFile(video()).toString(), 0)
        val o = p.addOverlay("portrait", src, 0u, 6000u); p.setClipSourceInfo(o, 6000u, 720u, 1280u)
        val clip = p.snapshot().tracks.first { it.kind == TrackKind.OVERLAY }.clips.first()
        // Frame source sees the rotation: portrait aspect and portrait frames.
        val fs = Layers.FrameSource.plan(ctx, listOf(clip), 720) { false }.values.single()
        try {
            assertTrue("aspect ${fs.aspect}", fs.aspect > 1.5f)
            val b = fs.frame(1000)
            assertTrue("frame ${b.width}x${b.height}", b.height > b.width)
            log("CLIO-QA bench portrait: frame ${b.width}x${b.height}, decodes ${fs.decodes} for ${fs.requests} request(s)")
        } finally { fs.close() }
        run("portrait-pip", p)
    }
}
