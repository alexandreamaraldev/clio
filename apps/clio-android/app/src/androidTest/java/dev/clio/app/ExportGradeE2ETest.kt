package dev.clio.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import dev.clio.app.engine.AppStore
import dev.clio.app.engine.ExportState
import dev.clio.app.engine.QaHooks
import dev.clio.app.ui.components.ClioCharacterConfig
import dev.clio.app.ui.screens.EditorUi
import org.junit.Rule
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
        val f = Bt709Frame.at(ctx, uri, ms * 1000)
        var spread = 0.0; var luma = 0.0; var n = 0
        for (y in f.height / 4 until f.height * 3 / 4 step 4) for (x in f.width / 4 until f.width * 3 / 4 step 4) {
            val (r, g, b) = f.rgb(x, y)
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
    private fun patch(f: Bt709Frame, i: Int): IntArray = f.patch(f.width / 2, f.height * (2 * i + 1) / 8, 24)

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
        // Raw Y'CbCr decoded with the stream's tagged matrix (Bt709Frame, validated by Bt709FrameGuardTest),
        // not MediaMetadataRetriever, whose conversion lifts grey on the emulator.
        val fails = mutableListOf<String>()
        run {
            cases.forEachIndexed { k, (name, g) ->
                val f = Bt709Frame.at(ctx, uri, k * 1_000_000L + 500_000)
                swatches.forEachIndexed { i, rgb ->
                    val got = patch(f, i); val want = reference(rgb, g, lib)
                    val err = (0..2).maxOf { kotlin.math.abs(got[it] - want[it]) }
                    val line = "CLIO-QA grade-ref $name swatch$i: got ${got.joinToString(",")} want ${want.joinToString(",")} err $err"
                    android.util.Log.i("ClioQA", line)
                    // Ungraded output must match its input closely; graded output must match the reference.
                    if (err > (if (name == "neutral") NEUTRAL_TOL else tol)) fails += line
                }
            }
        }
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

    /** Valid MP4: duration, size (rotation-aware), and video frames that decode. */
    private fun assertValid(uri: Uri, expectedMs: Long, label: String, expectedSize: Pair<Int, Int> = 720 to 1280) {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(ctx, uri)
            val d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val vw = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val vh = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val size = if (rot % 180 != 0) vh to vw else vw to vh
            val frames = listOf(200L, expectedMs / 2, expectedMs - 300).map { m.getFrameAtTime(it * 1000, MediaMetadataRetriever.OPTION_CLOSEST) }
            val line = "CLIO-QA grade-hdr $label: outcome=exported ${size.first}x${size.second} ${d}ms frames=${frames.map { it != null }}"
            android.util.Log.i("ClioQA", line)
            assertEquals("$label size", expectedSize, size)
            assertTrue("$label duration $d vs $expectedMs", kotlin.math.abs(d - expectedMs) <= 350)
            assertTrue("$label video frames not decodable: $line", frames.all { it != null && it.width > 0 })
        } finally { m.release() }
    }

    // ---- HDR through the real UI: each case passes on (a) a valid export or (b) the pt-BR HDR error. ----
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes().decodeToString() } }
    private fun shot(name: String) { shell("mkdir -p /sdcard/clio-qa"); shell("screencap -p /sdcard/clio-qa/$name.png") }

    private fun shared(name: String, devicePath: String): Uri {
        val dst = File(File(ctx.cacheDir, "import").apply { mkdirs() }, name)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat $devicePath")).use { i -> dst.outputStream().use { i.copyTo(it) } }
        assertTrue("fixture missing: $devicePath (qa/run-device-qa.sh pushes it)", dst.length() > 10_000)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dst)
    }

    private fun waitUi(what: String, timeoutMs: Long = 30_000, cond: (EditorUi) -> Boolean) {
        try { compose.waitUntil(timeoutMs) { QaHooks.ui?.let(cond) == true } }
        catch (e: Throwable) { shot("fail-$what"); throw AssertionError("timed out waiting for: $what; ui=${QaHooks.ui}", e) }
        compose.waitForIdle()
    }

    private fun clips(u: EditorUi = QaHooks.ui!!) = u.tracks.first { it.kind == "video" }.clips

    private fun tool(label: String) {
        compose.onNodeWithTag("tools", useUnmergedTree = true).performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("tools")), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun pick(label: String) {
        compose.onNodeWithTag("filter-strip", useUnmergedTree = true).performScrollToNode(hasContentDescription("Filtro $label"))
        compose.onNodeWithContentDescription("Filtro $label").performClick()
        compose.waitForIdle()
    }

    /**
     * Outcome (b) contract, from Timeline's d1a5faf: the export sheet shows exactly one pt-BR sentence
     * (UserFacingExportError.message) in the node tagged "export_error"; the cause chain only goes to Log.
     */
    private val HDR_ERROR = "Não foi possível exportar este vídeo HDR neste aparelho. Tente remover os filtros dos clipes."

    private fun assertPtBrHdrError(msg: String, label: String) {
        assertEquals("$label: ExportState.Failed message", HDR_ERROR, msg)
        val node = compose.onNodeWithTag("export_error", useUnmergedTree = true).assertExists().fetchSemanticsNode()
        val shown = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }
        assertEquals("$label: text on screen in export_error (no cause chain allowed)", HDR_ERROR, shown)
        compose.onNodeWithTag("export_error", useUnmergedTree = true).assertIsDisplayed()
        // Nothing else on screen may carry the raw chain either.
        for (raw in listOf("←", "Exception", "GL_EXT", "CodecInfo")) assertTrue("$label: raw error text \"$raw\" on screen",
            compose.onAllNodes(hasText(raw, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    private fun assertNoCrash(label: String) {
        assertTrue("$label: uncaught exception(s): ${uncaught.map { it.toString() }}", uncaught.isEmpty())
        val fatal = shell("logcat -d -b crash").lines().filter { it.contains(ctx.packageName) || it.contains("FATAL EXCEPTION") }
        assertTrue("$label: crash buffer: $fatal", fatal.isEmpty())
        assertEquals("$label: activity alive", androidx.lifecycle.Lifecycle.State.RESUMED, scenario!!.state)
    }

    /** Export 720p from the UI, then accept outcome (a) or (b); anything else fails. */
    private fun exportEither(label: String, filterLabel: String, filterId: String) {
        val ui = QaHooks.ui!!
        val short = minOf(ui.canvasW, ui.canvasH)
        val size = if (ui.canvasW <= ui.canvasH) 720 to 720 * ui.canvasH / short else 720 * ui.canvasW / short to 720
        compose.onNodeWithText("Exportar").performClick()
        compose.onNodeWithText("720p").performClick()
        compose.onNodeWithText("Exportar vídeo").performClick()
        compose.waitUntil(300_000) { QaHooks.export is ExportState.Done || QaHooks.export is ExportState.Failed }
        compose.waitForIdle(); shot("hdr-$label")
        assertNoCrash(label)
        when (val st = QaHooks.export) {
            is ExportState.Done -> assertValid(st.uri, (ui.durationS * 1000).toLong(), label, size)
            is ExportState.Failed -> {
                android.util.Log.i("ClioQA", "CLIO-QA grade-hdr $label: outcome=error \"${st.message}\"")
                assertPtBrHdrError(st.message, label)
                // The sheet can be taller than the screen when the raw cause chain is appended; click via semantics.
                compose.onNodeWithText("Fechar").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                compose.waitUntil(10_000) { QaHooks.export is ExportState.Closed }
                // Editor still usable: undo the grade, then redo it.
                compose.onNodeWithContentDescription("Desfazer").performClick()
                waitUi("$label undo after error") { u -> u.grades[clips(u).first().id]?.filter != filterId }
                compose.onNodeWithContentDescription("Refazer").performClick()
                waitUi("$label redo after error") { u -> u.grades[clips(u).first().id]?.filter == filterId }
                assertNoCrash("$label after error")
            }
            else -> error("unreachable")
        }
    }

    private fun gradeFirst(filterLabel: String, filterId: String) {
        compose.onNodeWithTag("clip-video-0", useUnmergedTree = true).performClick()
        tool("Filtros"); pick(filterLabel)
        waitUi("grade $filterId") { u -> u.grades[clips(u).first().id]?.filter == filterId }
        tool("Filtros")
    }

    @Test fun hdrSourcesExportWithGrades() {
        ClioCharacterConfig.enabled = false; AppStore(ctx).onboarded = true; QaHooks.ui = null; QaHooks.export = null
        shell("logcat -c -b crash")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += e; android.util.Log.e("ClioQA", "uncaught on ${t.name}", e) }
        try {
            val hlg = shared("clio-hlg.mp4", "/data/local/tmp/clio-hlg.mp4")
            // 1) One graded HLG clip (Cinema).
            scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, hlg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            waitUi("hlg imported", 60_000) { it.hasMedia && clips(it).size == 1 }
            gradeFirst("Cinema", "cinema")
            exportEither("graded-hlg", "Cinema", "cinema")
            scenario?.close(); QaHooks.ui = null; QaHooks.export = null

            // 2) Graded SDR clip (P&B) followed by an ungraded HLG clip.
            val sdr = shared("clio-test.mp4", "/data/local/tmp/clio-test.mp4")
            scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(sdr, hlg)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            waitUi("sdr+hlg imported", 60_000) { it.hasMedia && clips(it).size == 2 }
            gradeFirst("P&B", "pb")
            exportEither("graded-sdr+hlg", "P&B", "pb")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prev); scenario?.close()
        }
    }
}
