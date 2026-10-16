package dev.clio.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.AppStore
import dev.clio.app.engine.ExportState
import dev.clio.app.engine.LayerLimits
import dev.clio.app.engine.QaHooks
import dev.clio.app.ui.components.ClioCharacterConfig
import dev.clio.app.ui.screens.EditorUi
import dev.clio.app.ui.screens.TransitionStyle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Layers/PIP through the real UI: split the CI test video, add an image layer (picker answered by an
 * ActivityMonitor with a synthetic PNG generated here), push it to the ±2 / 400% limits with the
 * panel sliders, add a fade on the cut, export at 720p/24 fps and check the real MP4.
 */
@RunWith(AndroidJUnit4::class)
class LayersE2ETest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()
    private val MAGENTA = 0xFF00FF

    @Before fun setUp() {
        ClioCharacterConfig.enabled = false
        AppStore(ctx).onboarded = true
        QaHooks.ui = null; QaHooks.export = null
    }

    @After fun tearDown() { shot("layers-end"); monitors.forEach(inst::removeMonitor); scenario?.close() }

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes().decodeToString() } }
    private fun shot(name: String) { shell("mkdir -p /sdcard/clio-qa"); shell("screencap -p /sdcard/clio-qa/$name.png") }
    private fun log(s: String) { println(s); android.util.Log.i("ClioQA", s) }

    private fun importFile(name: String, write: (File) -> Unit): Uri {
        val f = File(File(ctx.cacheDir, "import").apply { mkdirs() }, name); write(f)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    private fun testVideo() = importFile("clio-test.mp4") { dst ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4")).use { i -> dst.outputStream().use { i.copyTo(it) } }
        assertTrue("test video missing", dst.length() > 10_000)
    }

    /** Solid magenta square, generated at test time. */
    private fun overlayPng() = importFile("layer.png") { f ->
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Answers the next media picker launch with [uri] (photo picker or document fallback). */
    private fun answerPicker(uri: Uri) {
        val data = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (a in listOf(MediaStore.ACTION_PICK_IMAGES, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)) {
            monitors += inst.addMonitor(IntentFilter(a).apply { addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") },
                Instrumentation.ActivityResult(Activity.RESULT_OK, data), true)
            monitors += inst.addMonitor(IntentFilter(a), Instrumentation.ActivityResult(Activity.RESULT_OK, data), true)
        }
    }

    private fun waitUi(what: String, timeoutMs: Long = 30_000, cond: (EditorUi) -> Boolean) {
        try { compose.waitUntil(timeoutMs) { QaHooks.ui?.let(cond) == true } }
        catch (e: Throwable) { shot("fail-$what"); throw AssertionError("timed out waiting for: $what; ui=${QaHooks.ui}", e) }
        compose.waitForIdle()
    }

    private fun videoClips(u: EditorUi = QaHooks.ui!!) = u.tracks.first { it.kind == "video" }.clips

    private fun tool(label: String) {
        compose.onNodeWithTag("tools", useUnmergedTree = true).performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("tools")), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun scrubForward(dp: Float) {
        compose.onNodeWithTag("timeline", useUnmergedTree = true).performTouchInput {
            val px = dp * density; down(Offset(centerX + px / 2, centerY)); repeat(10) { moveBy(Offset(-px / 10, 0f)) }; up()
        }
        compose.waitForIdle()
    }

    /** Slider order in LayersPanel: size, rotation, opacity, horizontal, vertical. */
    private fun slider(i: Int, v: Float) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress), useUnmergedTree = true)[i]
            .performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        compose.waitForIdle()
    }

    @Test fun layerTransformTransitionExport() {
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, testVideo()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        waitUi("video imported") { it.hasMedia && videoClips(it).size == 1 }

        // Two main clips so there is a cut for the transition.
        scrubForward(96f)
        tool("Dividir")
        waitUi("split") { videoClips(it).size == 2 }
        val cutS = videoClips().first().lenS

        // Add an image layer through the real "Adicionar" button + picker.
        tool("Camadas")
        answerPicker(overlayPng())
        compose.onNodeWithText("Adicionar").performClick()
        waitUi("layer added") { it.layers.size == 1 }
        val layer = QaHooks.ui!!.layers.single()
        assertTrue("layer is an image", layer.isImage)
        shot("layers-01-added")

        // Select it on the timeline, reopen the panel, push to the limits.
        tool("Camadas")
        compose.onNodeWithTag("clip-overlay-0", useUnmergedTree = true).performClick()
        tool("Camadas")
        compose.onNodeWithText("Redefinir").assertExists()
        slider(3, LayerLimits.MAX_LAYER_OFFSET)
        slider(4, -LayerLimits.MAX_LAYER_OFFSET)
        waitUi("x/y at limits") { u -> u.layers.single().transform.let { abs(it.x - 2f) < 1e-3 && abs(it.y + 2f) < 1e-3 } }
        // Back to an export-checkable placement: covers exactly the right half of the canvas.
        slider(0, 2f); slider(3, LayerLimits.MAX_LAYER_OFFSET); slider(4, 0f)
        waitUi("x=2, y=0, scale=2") { u -> u.layers.single().transform.let { abs(it.scale - 2f) < 1e-3 && abs(it.x - 2f) < 1e-3 && abs(it.y) < 1e-3 } }
        shot("layers-02-transformed")
        tool("Camadas")

        // Fade on the cut.
        tool("Transição")
        compose.onNodeWithText(TransitionStyle.Fade.label).performClick()
        waitUi("fade") { it.cuts.firstOrNull()?.style == TransitionStyle.Fade }
        val fade = QaHooks.ui!!.cuts.first()
        shot("layers-03-transition")
        tool("Transição")

        // Export 720p @ 24 fps and probe the real file.
        val ui = QaHooks.ui!!
        compose.onNodeWithText("Exportar").performClick()
        compose.onNodeWithText("720p").performClick()
        compose.onNodeWithText("24").performClick()
        val t0 = android.os.SystemClock.elapsedRealtime()
        compose.onNodeWithText("Exportar vídeo").performClick()
        compose.waitUntil(300_000) { QaHooks.export is ExportState.Done || QaHooks.export is ExportState.Failed }
        val st = QaHooks.export
        assertTrue("export failed: ${(st as? ExportState.Failed)?.message}", st is ExportState.Done)
        val uri = (st as ExportState.Done).uri
        val wall = android.os.SystemClock.elapsedRealtime() - t0
        val info = ExportProbe.info(ctx, uri)
        val short = minOf(ui.canvasW, ui.canvasH)
        val (ew, eh) = if (ui.canvasW <= ui.canvasH) 720 to 720 * ui.canvasH / short else 720 * ui.canvasW / short to 720
        log("CLIO-QA layers-e2e: $info in ${wall}ms (expected ${ew}x$eh ${(ui.durationS * 1000).toLong()}ms 24fps)")
        assertEquals("size", ew to eh, info.width to info.height)
        assertTrue("duration ${info.durationMs} vs ${ui.durationS}", abs(info.durationMs - (ui.durationS * 1000).toLong()) <= 350)
        assertEquals("fps ${info.fps}", 24f, info.fps, 1.5f)
        assertTrue("audio track", info.hasAudio)
        assertTrue("audio duration ${info.audioDurationMs}", abs(info.audioDurationMs - info.durationMs) <= 400)

        // A frame where the layer is visible and away from the fade window.
        val layerStartMs = layer.startMs
        val at = maxOf(layerStartMs + 500, ((cutS + fade.durS) * 1000).toLong() + 300).coerceAtMost(layer.endMs - 300)
        val f = ExportProbe.frame(ctx, uri, at)
        val y = f.height / 2
        File(ctx.getExternalFilesDir(null), "layers-frame.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("layer missing on the right half at ${at}ms", ExportProbe.near(f, f.width * 3 / 4, y, MAGENTA) && ExportProbe.near(f, f.width - 4, y, MAGENTA))
        assertFalse("layer leaked onto the left half at ${at}ms", ExportProbe.near(f, f.width / 4, y, MAGENTA))
    }
}
