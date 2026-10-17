package dev.clio.app

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.AppStore
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.ExportState
import dev.clio.app.engine.Exporter
import dev.clio.app.engine.QaHooks
import dev.clio.app.ui.components.ClioCharacterConfig
import dev.clio.app.ui.screens.CaptionStyle
import dev.clio.app.ui.screens.EditorUi
import dev.clio.app.ui.screens.speedToY
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.TrackKind
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

/**
 * QA for speed curves on a device. Expected durations come from an independent integral
 * ∫ span / speed(x) dx (Simpson, 4000 panels) over our own Fritsch–Carlson log2 interpolation
 * ([refSpeed]), never from the app's duration code. Tolerance is ±50 ms (one frame + padding).
 */
@RunWith(AndroidJUnit4::class)
class SpeedCurveQaE2ETest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val TOL_MS = 50L

    @Before fun setUp() { ClioCharacterConfig.enabled = false; AppStore(ctx).onboarded = true; QaHooks.ui = null; QaHooks.export = null }
    @After fun tearDown() { shot("speedqa-end"); scenario?.close() }

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes().decodeToString() } }
    private fun shot(name: String) { shell("mkdir -p /sdcard/clio-qa"); shell("screencap -p /sdcard/clio-qa/$name.png") }
    private fun log(s: String) { android.util.Log.i("ClioQA", "CLIO-QA speed-qa $s"); println("CLIO-QA speed-qa $s") }

    private fun fixture(name: String): File {
        val dst = File(File(ctx.cacheDir, "import").apply { mkdirs() }, name)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/$name")).use { i -> dst.outputStream().use { i.copyTo(it) } }
        assertTrue("fixture missing: /data/local/tmp/$name (qa/run-device-qa.sh)", dst.length() > 1_000)
        return dst
    }

    // ---- independent reference ----
    private val PRESETS = mapOf(
        "montagem" to listOf(0f to 2.5f, 0.35f to 0.5f, 0.65f to 0.5f, 1f to 2.5f),
        "heroi" to listOf(0f to 1.5f, 0.4f to 0.3f, 0.6f to 0.3f, 1f to 1.5f),
        "bala" to listOf(0f to 2f, 0.45f to 0.15f, 0.55f to 0.15f, 1f to 2f),
        "flash_in" to listOf(0f to 5f, 0.5f to 1f, 1f to 1f),
        "flash_out" to listOf(0f to 1f, 0.5f to 1f, 1f to 5f),
    )
    private val LABELS = mapOf("montagem" to "Montagem", "heroi" to "Herói", "bala" to "Bala", "flash_in" to "Flash de entrada", "flash_out" to "Flash de saída")

    private fun refSpeed(p: List<Pair<Float, Float>>, x0: Double): Double {
        val n = p.size; val x = x0.coerceIn(0.0, 1.0)
        val i = (0 until n - 1).firstOrNull { x <= p[it + 1].first } ?: (n - 2)
        val y = p.map { Math.log(it.second.toDouble()) / Math.log(2.0) }
        val d = (0 until n - 1).map { (y[it + 1] - y[it]) / (p[it + 1].first - p[it].first).toDouble() }
        fun m(k: Int) = when { k == 0 -> d[0]; k == n - 1 -> d[n - 2]; d[k - 1] * d[k] <= 0 -> 0.0; else -> 2 / (1 / d[k - 1] + 1 / d[k]) }
        val h = (p[i + 1].first - p[i].first).toDouble(); val t = (x - p[i].first) / h
        val v = (2 * t.pow(3) - 3 * t * t + 1) * y[i] + (t.pow(3) - 2 * t * t + t) * h * m(i) + (-2 * t.pow(3) + 3 * t * t) * y[i + 1] + (t.pow(3) - t * t) * h * m(i + 1)
        return 2.0.pow(v).coerceIn(0.1, 10.0)
    }
    private fun integralMs(p: List<Pair<Float, Float>>, spanMs: Double): Double {
        val n = 4000; val h = 1.0 / n
        var s = spanMs / refSpeed(p, 0.0) + spanMs / refSpeed(p, 1.0)
        for (k in 1 until n) s += spanMs / refSpeed(p, k * h) * (if (k % 2 == 1) 4 else 2)
        return s * h / 3
    }
    /** Our reference must agree with the engine's drawn/played interpolation, or the oracle is moot. */
    private fun assertRefMatchesEngine(p: List<Pair<Float, Float>>, what: String) {
        val c = uniffi.clio_ffi.SpeedCurve("personalizado", p.map { uniffi.clio_ffi.SpeedPoint(it.first, it.second) })
        for (k in 0..100) { val x = k / 100.0; val e = uniffi.clio_ffi.curveSpeedAt(c, x.toFloat()).toDouble(); val r = refSpeed(p, x)
            assertTrue("$what: engine speed $e vs reference $r at $x", abs(e - r) <= 2e-3 * r) }
    }

    // ---- UI helpers ----
    private fun clips(u: EditorUi = QaHooks.ui!!) = u.tracks.first { it.kind == "video" }.clips
    private fun waitUi(what: String, timeoutMs: Long = 30_000, cond: (EditorUi) -> Boolean) {
        try { compose.waitUntil(timeoutMs) { QaHooks.ui?.let(cond) == true } }
        catch (e: Throwable) { shot("fail-$what"); throw AssertionError("timed out waiting for: $what; ui=${QaHooks.ui}", e) }
        compose.waitForIdle()
    }
    private fun tool(label: String) {
        compose.onNodeWithTag("tools", useUnmergedTree = true).performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("tools")), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }
    private fun openSpeedCurve() {
        compose.onNodeWithTag("clip-video-0", useUnmergedTree = true).performClick()
        tool("Velocidade")
        compose.onNodeWithTag("speed-mode-curve").performClick(); compose.waitForIdle()
    }
    /** Drag on the curve box from a control point (x, speed) to a new speed, in small steps. */
    private fun dragPoint(x: Float, fromSpeed: Float, toSpeed: Float, toX: Float = x) {
        compose.onNodeWithTag("speed-curve").performTouchInput {
            val a = Offset(x * width, speedToY(fromSpeed) * height); val b = Offset(toX * width, speedToY(toSpeed) * height)
            down(a); repeat(12) { moveTo(a + (b - a) * ((it + 1) / 12f)) }; up()
        }
        compose.waitForIdle()
    }

    @Test fun presetsCustomUndoRedoDraftAndExport() {
        val src = fixture("clio-test.mp4")
        val spanMs = ExportProbe.info(ctx, Uri.fromFile(src)).durationMs.toDouble()
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, ctx.packageName + ".files", src)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        waitUi("imported") { it.hasMedia && clips(it).size == 1 }
        val clipSpan = clips().first().lenS * 1000.0 * clips().first().speed
        log("source ${spanMs}ms, clip span ${clipSpan}ms")
        openSpeedCurve()

        // 1) Every preset through its chip: exact points and timeline length = independent integral.
        for ((id, pts) in PRESETS) {
            compose.onNodeWithTag("speed-preset-$id").performScrollTo().performClick()
            waitUi("preset $id") { clips(it).first().speedPreset == id }
            val c = clips().first()
            assertEquals("$id points", pts, c.speedPoints)
            assertRefMatchesEngine(pts, id)
            val want = integralMs(pts, clipSpan)
            log("preset $id: timeline ${c.lenS * 1000}ms vs ∫ ${"%.1f".format(want)}ms (${LABELS[id]})")
            assertEquals("$id timeline length vs ∫", want, c.lenS * 1000.0, 5.0)
        }

        // 2) Custom: from Bala, drag the left slow point up to ~1x, then add a point near x=0.8 at ~4x.
        compose.onNodeWithTag("speed-preset-bala").performScrollTo().performClick()
        waitUi("bala") { clips(it).first().speedPreset == "bala" }
        val bala = clips().first().speedPoints
        dragPoint(0.45f, 0.15f, 1f)
        waitUi("custom drag") { clips(it).first().speedPreset == "personalizado" }
        val dragged = clips().first().speedPoints
        assertEquals("drag keeps the point count", 4, dragged.size)
        assertEquals("dragged point x", 0.45f, dragged[1].first, 0.03f)
        assertEquals("dragged point speed ~1x", 1.0f, dragged[1].second, 0.2f)
        dragPoint(0.8f, refSpeed(dragged, 0.8).toFloat() * 0.5f, 4f) // empty space -> adds a point
        waitUi("point added") { clips(it).first().speedPoints.size == 5 }
        val custom = clips().first().speedPoints
        assertEquals("custom preset", "personalizado", clips().first().speedPreset)
        log("custom points: $custom")
        shot("speedqa-custom")

        // 3) Undo/redo: exact points at each step.
        compose.onNodeWithContentDescription("Desfazer").performClick()
        waitUi("undo add") { clips(it).first().speedPoints == dragged }
        compose.onNodeWithContentDescription("Desfazer").performClick()
        waitUi("undo drag -> bala") { clips(it).first().speedPoints == bala && clips(it).first().speedPreset == "bala" }
        compose.onNodeWithContentDescription("Refazer").performClick()
        waitUi("redo drag") { clips(it).first().speedPoints == dragged }
        compose.onNodeWithContentDescription("Refazer").performClick()
        waitUi("redo add") { clips(it).first().speedPoints == custom }
        val lenBefore = clips().first().lenS

        // 4) Draft: back to home, cold relaunch, reopen; points must be exactly equal.
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        scenario?.close(); QaHooks.ui = null
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("draft-0"))
        compose.onNodeWithTag("draft-0").performClick()
        waitUi("draft reopened", 60_000) { it.hasMedia && clips(it).size == 1 }
        val reopened = clips().first()
        assertEquals("draft preset", "personalizado", reopened.speedPreset)
        assertEquals("draft points (exact)", custom, reopened.speedPoints)
        assertEquals("draft length", lenBefore, reopened.lenS, 0.0005f)

        // 5) Export from the UI: MP4 duration = independent integral of the custom curve.
        val want = integralMs(custom, clipSpan)
        assertRefMatchesEngine(custom, "custom")
        compose.onNodeWithText("Exportar").performClick()
        compose.onNodeWithText("720p").performClick()
        compose.onNodeWithText("Exportar vídeo").performClick()
        compose.waitUntil(300_000) { QaHooks.export is ExportState.Done || QaHooks.export is ExportState.Failed }
        val st = QaHooks.export
        assertTrue("export failed: ${(st as? ExportState.Failed)?.message}", st is ExportState.Done)
        val info = ExportProbe.info(ctx, (st as ExportState.Done).uri)
        log("custom export: video ${info.durationMs}ms audio ${info.audioDurationMs}ms vs ∫ ${"%.1f".format(want)}ms, timeline ${reopened.lenS * 1000}ms")
        assertEquals("export duration vs ∫", want, info.durationMs.toDouble(), TOL_MS.toDouble())
        assertTrue("audio kept", info.hasAudio)
        assertEquals("audio duration vs ∫", want, info.audioDurationMs.toDouble(), TOL_MS.toDouble())
    }

    // ---- frame scan: pts + centre Y/Cb/Cr of every video frame ----
    private data class F(val ptsUs: Long, val y: Int, val cb: Int, val cr: Int)
    private fun scan(uri: Uri): List<F> {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(t); val fmt = ex.getTrackFormat(t)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); codec.configure(fmt, null, null, 0); codec.start()
        val out = mutableListOf<F>(); val info = MediaCodec.BufferInfo(); var eos = false
        try {
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                if (!eos) { val i = codec.dequeueInputBuffer(10_000); if (i >= 0) { val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() } } }
                val o = codec.dequeueOutputBuffer(info, 10_000); if (o < 0) continue
                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.getOutputImage(o)?.use { img ->
                    if (info.size > 0 || !last) {
                        val cx = img.width / 2; val cy = img.height / 2
                        fun at(k: Int, x: Int, y: Int) = img.planes[k].let { p -> p.buffer.get(y * p.rowStride + x * p.pixelStride).toInt() and 255 }
                        var ys = 0; for (dy in -8..8) for (dx in -8..8) ys += at(0, cx + dx, cy + dy)
                        out += F(info.presentationTimeUs, ys / 289, at(1, cx / 2, cy / 2), at(2, cx / 2, cy / 2))
                    }
                }
                codec.releaseOutputBuffer(o, false); if (last) break
            }
        } finally { codec.stop(); codec.release(); ex.release() }
        return out.sortedBy { it.ptsUs }
    }

    /**
     * Timeline's case: flash_in on the FIRST of two solid clips (red, then blue; 4 s each, made by
     * qa/run-device-qa.sh). Export length must equal the engine timeline AND the independent ∫; the
     * cut must land at ∫(clip A) within one frame, with no black frame and no timestamp gap.
     */
    @Test fun flashInOnFirstClipCutIsSeamless() {
        val red = Uri.fromFile(fixture("clio-red.mp4")); val blue = Uri.fromFile(fixture("clio-blue.mp4"))
        val spanA = ExportProbe.info(ctx, red).durationMs; val spanB = ExportProbe.info(ctx, blue).durationMs
        val p = ClioProject("speed-flash-cut", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        val a = p.appendClip(v, "red", red.toString(), spanA.toULong()); p.setClipSourceInfo(a, spanA.toULong(), 1280u, 720u)
        val b = p.appendClip(v, "blue", blue.toString(), spanB.toULong()); p.setClipSourceInfo(b, spanB.toULong(), 1280u, 720u)
        p.setSpeedPreset(a, "flash_in")
        val pts = PRESETS.getValue("flash_in")
        val cutWant = integralMs(pts, spanA.toDouble()); val totalWant = cutWant + spanB
        val engineTotal = p.summary().durationMs.toLong()
        val engineCut = p.tracks()[0].clips[0].durationMs.toLong()
        val uri = runBlocking { withTimeout(240_000) { Exporter(ctx).export(p.snapshot(), ExportSpec("speed-flash-cut", 720, 1280, 30), CaptionStyle.Clean) {} } }
        val info = ExportProbe.info(ctx, uri)
        val frames = scan(uri)
        val isBlue = { f: F -> f.cb > 150 && f.cr < 140 }; val isRed = { f: F -> f.cr > 170 }
        val firstBlue = frames.firstOrNull(isBlue)
        val maxGap = frames.zipWithNext { x, y -> y.ptsUs - x.ptsUs }.maxOrNull() ?: 0
        val frameUs = 1_000_000.0 / 30
        log("flash-cut: engine cut ${engineCut}ms total ${engineTotal}ms; ∫ cut ${"%.1f".format(cutWant)} total ${"%.1f".format(totalWant)}; " +
            "export ${info.durationMs}ms audio ${info.audioDurationMs}ms; frames ${frames.size}, first blue at ${firstBlue?.ptsUs?.div(1000)}ms, max pts gap ${maxGap / 1000}ms")
        val fails = mutableListOf<String>()
        if (abs(engineTotal - totalWant) > 5) fails += "engine timeline $engineTotal vs ∫ ${"%.1f".format(totalWant)}"
        if (abs(info.durationMs - engineTotal) > TOL_MS) fails += "export ${info.durationMs} vs engine timeline $engineTotal"
        if (abs(info.durationMs - totalWant) > TOL_MS) fails += "export ${info.durationMs} vs ∫ ${"%.1f".format(totalWant)}"
        if (!info.hasAudio || abs(info.audioDurationMs - totalWant) > TOL_MS) fails += "audio ${info.audioDurationMs} (has=${info.hasAudio}) vs ∫ ${"%.1f".format(totalWant)}"
        if (firstBlue == null) fails += "no blue frame (clip B missing)"
        else if (abs(firstBlue.ptsUs / 1000.0 - cutWant) > frameUs / 1000) fails += "cut at ${firstBlue.ptsUs / 1000}ms vs ∫ ${"%.1f".format(cutWant)}ms (> 1 frame)"
        val win = frames.filter { abs(it.ptsUs / 1000.0 - cutWant) <= 500 }
        val dark = win.filter { it.y < 40 || !(isRed(it) || isBlue(it)) }
        if (win.size < 25) fails += "only ${win.size} frames within ±500 ms of the cut"
        if (dark.isNotEmpty()) fails += "black/foreign frames near the cut: " + dark.joinToString { "${it.ptsUs / 1000}ms Y${it.y} Cb${it.cb} Cr${it.cr}" }
        if (maxGap > 1.5 * frameUs) fails += "pts gap ${maxGap / 1000.0}ms > 1.5 frames at " + frames.zipWithNext().filter { (x, y) -> y.ptsUs - x.ptsUs > 1.5 * frameUs }.joinToString { "${it.first.ptsUs / 1000}->${it.second.ptsUs / 1000}" }
        if (frames.firstOrNull()?.let { isRed(it) } != true) fails += "first frame not red: ${frames.firstOrNull()}"
        assertTrue("flash_in on clip 1 of 2:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    /**
     * Three curved clips (Montagem red, Herói green, Flash de entrada blue; muted) over a sine music
     * lane. Checks, all within one frame (33.3 ms at 30 fps): engine clip durations vs independent
     * Simpson ∫ (so frame-grid snapping can't drift from the true curve), video/audio ends vs the
     * engine timeline, each cut (colour change) vs the engine clip start; no pts gap > 1.5 frames;
     * music audible (RMS) in every 100 ms window across the export.
     */
    @Test fun threeCurvedClipsWithMusicStayOnTheFrameGrid() {
        val frameMs = 1000.0 / 30
        val plan = listOf(Triple("clio-red.mp4", "montagem", "red"), Triple("clio-green.mp4", "heroi", "green"), Triple("clio-blue.mp4", "flash_in", "blue"))
        val p = ClioProject("speed-three", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        val spans = mutableListOf<Long>(); val ids = mutableListOf<ULong>()
        for ((file, preset, _) in plan) {
            val u = Uri.fromFile(fixture(file)); val span = ExportProbe.info(ctx, u).durationMs
            val id = p.appendClip(v, file, u.toString(), span.toULong()); p.setClipSourceInfo(id, span.toULong(), 1280u, 720u)
            p.setSpeedPreset(id, preset); p.setClipAudio(id, 1f, 0u, 0u, true)
            spans += span; ids += id
        }
        val clips = p.tracks()[0].clips
        val total = p.summary().durationMs.toLong()
        val music = Uri.fromFile(fixture("clio-music.m4a")).toString()
        p.setLaneClip(dev.clio.app.engine.AudioLanes.MUSIC, "música", music, 0u, total.toULong())
        val uri = runBlocking { withTimeout(300_000) { Exporter(ctx).export(p.snapshot(), ExportSpec("speed-three", 720, 1280, 30), CaptionStyle.Clean) {} } }
        val info = ExportProbe.info(ctx, uri)
        val frames = scan(uri)
        val colour = { f: F -> when { f.cr > 170 -> "red"; f.cb > 150 && f.cr < 140 -> "blue"; f.cb < 110 && f.cr < 110 && f.y > 100 -> "green"; else -> "?" } }
        val fails = mutableListOf<String>()
        val lines = mutableListOf<String>()
        plan.forEachIndexed { i, (_, preset, name) ->
            val c = clips[i]; val want = integralMs(PRESETS.getValue(preset), spans[i].toDouble())
            val err = c.durationMs.toDouble() - want
            lines += "$name/$preset: engine start ${c.startMs} dur ${c.durationMs} vs ∫ ${"%.1f".format(want)} (err ${"%.1f".format(err)})"
            if (abs(err) > frameMs) fails += "$name engine duration ${c.durationMs} vs ∫ ${"%.1f".format(want)} (> 1 frame)"
            val first = frames.firstOrNull { colour(it) == name }
            if (first == null) fails += "$name never appears"
            else {
                val e = first.ptsUs / 1000.0 - c.startMs.toDouble()
                lines += "$name first frame ${first.ptsUs / 1000}ms vs start ${c.startMs} (err ${"%.1f".format(e)})"
                if (abs(e) > frameMs) fails += "$name cut at ${first.ptsUs / 1000}ms vs engine start ${c.startMs} (> 1 frame)"
            }
        }
        val sumInt = plan.indices.sumOf { integralMs(PRESETS.getValue(plan[it].second), spans[it].toDouble()) }
        lines += "total engine $total, ∑∫ ${"%.1f".format(sumInt)}, video ${info.durationMs}, audio ${info.audioDurationMs}"
        if (abs(info.durationMs - total) > frameMs) fails += "video end ${info.durationMs} vs engine $total (> 1 frame)"
        if (!info.hasAudio || abs(info.audioDurationMs - total) > frameMs) fails += "audio end ${info.audioDurationMs} (has=${info.hasAudio}) vs engine $total (> 1 frame)"
        val unknown = frames.filter { colour(it) == "?" }
        if (unknown.isNotEmpty()) fails += "${unknown.size} black/foreign frames, e.g. " + unknown.take(5).joinToString { "${it.ptsUs / 1000}ms Y${it.y} Cb${it.cb} Cr${it.cr}" }
        val gaps = frames.zipWithNext().filter { (x, y) -> y.ptsUs - x.ptsUs > 1.5 * frameMs * 1000 }
        lines += "frames ${frames.size}, max pts gap ${(frames.zipWithNext { x, y -> y.ptsUs - x.ptsUs }.maxOrNull() ?: 0) / 1000.0}ms"
        if (gaps.isNotEmpty()) fails += "pts gaps > 1.5 frames: " + gaps.joinToString { "${it.first.ptsUs / 1000}->${it.second.ptsUs / 1000}" }
        val rms = ExportProbe.audioRms(ctx, uri, 100)
        val quiet = rms.withIndex().filter { (k, r) -> (k + 1) * 100 <= total - 50 && r < 0.01f }
        lines += "music rms windows ${rms.size}, min ${"%.4f".format(rms.dropLast(1).minOrNull() ?: 0f)}"
        if (rms.size < total / 100 - 1) fails += "audio covers only ${rms.size * 100}ms of $total"
        if (quiet.isNotEmpty()) fails += "silent music windows at " + quiet.take(10).joinToString { "${it.index * 100}ms" }
        lines.forEach { log("three: $it") }
        assertTrue("three curved clips + music:\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
