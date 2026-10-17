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
import dev.clio.app.engine.QaHooks
import dev.clio.app.ui.components.ClioCharacterConfig
import dev.clio.app.ui.screens.EditorUi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Keyframes through the real UI, checked on the exported MP4 against the values the engine's
 * shared evaluation (`clip_look_keyed`) gives at those times:
 *  - a magenta image layer over the solid red fixture, keyed with the diamond button at two
 *    playhead positions (second key set by moving the layer sliders): position, scale and opacity at
 *    both keys and halfway (Suave ease = halfway values);
 *  - Zoom/Pan on a still (green left half, blue right half) via the "Para a direita" preset: the
 *    green/blue edge moves from right of centre to left of centre.
 */
@RunWith(AndroidJUnit4::class)
class KeyframesE2ETest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()

    @Before fun setUp() {
        ClioCharacterConfig.enabled = false
        AppStore(ctx).onboarded = true
        QaHooks.ui = null; QaHooks.export = null
    }

    @After fun tearDown() { shot("keys-end"); monitors.forEach(inst::removeMonitor); scenario?.close() }

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes().decodeToString() } }
    private fun shot(name: String) { shell("mkdir -p /sdcard/clio-qa"); shell("screencap -p /sdcard/clio-qa/$name.png") }
    private fun log(s: String) { println(s); android.util.Log.i("ClioQA", s) }

    private fun importFile(name: String, write: (File) -> Unit): Uri {
        val f = File(File(ctx.cacheDir, "import").apply { mkdirs() }, name); write(f)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }
    private fun fixture(name: String) = importFile(name) { dst ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/$name")).use { i -> dst.outputStream().use { i.copyTo(it) } }
        assertTrue("fixture $name missing (run-device-qa.sh generates it)", dst.length() > 1_000)
    }
    private fun png(name: String, w: Int, h: Int, draw: (Bitmap) -> Unit) = importFile(name) { f ->
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also(draw)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

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

    /** LayersPanel slider by label (Tamanho, Opacidade, Horizontal, ...), set through its SetProgress semantics. */
    private fun slider(label: String, v: Float) {
        val n = compose.onNodeWithTag("layer-slider-$label", useUnmergedTree = true).performScrollTo()
        n.performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        compose.waitForIdle()
    }

    private fun export720(): Uri {
        compose.onNodeWithText("Exportar").performClick()
        compose.onNodeWithText("720p").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Exportar vídeo").performClick()
        compose.waitUntil(300_000) { QaHooks.export is ExportState.Done || QaHooks.export is ExportState.Failed }
        val st = QaHooks.export
        assertTrue("export failed: ${(st as? ExportState.Failed)?.message}", st is ExportState.Done)
        return (st as ExportState.Done).uri
    }

    private fun rgb(b: Bitmap, x: Int, y: Int): Triple<Int, Int, Int> = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1)).let { Triple(Color.red(it), Color.green(it), Color.blue(it)) }
    private fun near(c: Triple<Int, Int, Int>, r: Int, g: Int, b: Int, tol: Int = 48) = abs(c.first - r) <= tol && abs(c.second - g) <= tol && abs(c.third - b) <= tol
    /** Blend of the magenta layer at [a] over the red (255,64,64) fixture. */
    private fun over(a: Float) = Triple(255, (64 * (1 - a)).toInt(), (64 * (1 - a) + 255 * a).toInt())

    @Test fun layerKeyframesExportMatchEngine() {
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, fixture("clio-red.mp4")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        waitUi("video imported") { it.hasMedia }

        tool("Camadas")
        answerPicker(png("magenta.png", 256, 256) { it.eraseColor(Color.MAGENTA) })
        compose.onNodeWithText("Adicionar").performClick()
        waitUi("layer added") { it.layers.size == 1 }
        tool("Camadas")
        compose.onNodeWithTag("clip-overlay-0", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        // Key A: the default placement, at the current playhead (inside the layer).
        scrubForward(20f)
        compose.onNodeWithTag("keyframe-toggle", useUnmergedTree = true).assertIsEnabled().performClick()
        waitUi("key A") { u -> u.tracks.flatMap { it.clips }.any { it.keys.size == 1 } }
        // Key B: move the playhead, then move the layer (sets/updates the key at the playhead).
        scrubForward(70f)
        tool("Camadas")
        slider("Tamanho", 0.3f); slider("Opacidade", 0.5f); slider("Horizontal", -0.5f)
        waitUi("key B") { u -> u.tracks.flatMap { it.clips }.any { it.keys.size == 2 } }
        tool("Camadas")
        shot("keys-01-two-keys")
        // The ease picker is in the Animar panel; keep the default (Suave) and check it is shown.
        tool("Animar")
        compose.onNodeWithTag("animate-panel", useUnmergedTree = true).assertExists()
        tool("Animar")

        val u = QaHooks.ui!!
        val layer = u.layers.single()
        val clip = u.tracks.flatMap { it.clips }.first { it.id == layer.id }
        val (ka, kb) = clip.keys
        val uri = export720()
        val info = ExportProbe.info(ctx, uri)
        val W = info.width; val H = info.height
        fun cx(x: Float) = ((x + 1) / 2 * W).toInt()
        val cy = ((1 - 0.5f) / 2 * H).toInt()
        val tA = layer.startMs + (ka.atS * 1000).toLong() + 20
        val tB = layer.startMs + (kb.atS * 1000).toLong() + 20
        val tM = (tA + tB) / 2
        val fA = ExportProbe.frame(ctx, uri, tA); val fB = ExportProbe.frame(ctx, uri, tB); val fM = ExportProbe.frame(ctx, uri, tM)
        listOf("A" to fA, "B" to fB, "M" to fM).forEach { (n, f) -> File(ctx.getExternalFilesDir(null), "keys-$n.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val a = rgb(fA, cx(0.45f), cy); val b = rgb(fB, cx(-0.5f), cy); val m = rgb(fM, cx(-0.025f), cy)
        val bAtA = rgb(fB, cx(0.45f), cy)
        log("CLIO-QA keys layer: keys ${ka.atS}s/${kb.atS}s; A@${tA}ms $a, B@${tB}ms $b (want ${over(0.5f)}), mid@${tM}ms $m (want ${over(0.75f)}), B frame at A's spot $bAtA")
        val fails = mutableListOf<String>()
        if (!near(a, 255, 0, 255)) fails += "key A: layer (opacity 1) not at x=0.45: $a"
        val ob = over(0.5f); if (!near(b, ob.first, ob.second, ob.third)) fails += "key B: layer at x=-0.5 opacity 0.5 should read $ob, got $b"
        val om = over(0.75f); if (!near(m, om.first, om.second, om.third)) fails += "halfway (Suave): x=-0.025 opacity 0.75 should read $om, got $m"
        if (!near(bAtA, 255, 64, 64)) fails += "key B: layer still at A's spot: $bAtA"
        // Scale: at B the layer is 0.3 W wide; 0.2 W right of its centre must be background.
        val edge = rgb(fB, cx(-0.5f) + (0.2f * W).toInt(), cy)
        if (!near(edge, 255, 64, 64)) fails += "key B: scale 0.3 but layer reaches 0.2 W from its centre: $edge"
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun zoomPanOnStillMovesTheFrame() {
        val still = png("halves.png", 1280, 720) { b ->
            val c = android.graphics.Canvas(b); val p = android.graphics.Paint()
            p.color = Color.rgb(0, 200, 0); c.drawRect(0f, 0f, 640f, 720f, p)
            p.color = Color.rgb(0, 0, 220); c.drawRect(640f, 0f, 1280f, 720f, p)
        }
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, still).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        waitUi("still imported") { it.hasMedia }
        tool("Animar")
        compose.onNodeWithTag("animate-mode-zoompan", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("zoompan-Para a direita", useUnmergedTree = true).performScrollTo().performClick()
        waitUi("zoom/pan set") { u -> u.tracks.first { it.kind == "video" }.clips.first().zoomPan != null }
        shot("keys-02-zoompan")
        tool("Animar")
        val clip = QaHooks.ui!!.tracks.first { it.kind == "video" }.clips.first()
        val uri = export720()
        val info = ExportProbe.info(ctx, uri)
        val W = info.width; val H = info.height
        fun px(u: Float) = ((u + 1) / 2 * W).toInt()
        val y = H / 2
        val green = Triple(0, 200, 0); val blue = Triple(0, 0, 220)
        fun isG(c: Triple<Int, Int, Int>) = near(c, green.first, green.second, green.third, 60)
        fun isB(c: Triple<Int, Int, Int>) = near(c, blue.first, blue.second, blue.third, 60)
        val endMs = (clip.lenS * 1000).toLong()
        val f0 = ExportProbe.frame(ctx, uri, 0); val fm = ExportProbe.frame(ctx, uri, endMs / 2); val f1 = ExportProbe.frame(ctx, uri, endMs - 60)
        // Edge (source u = 0) lands at canvas u = x: +0.15 at the start, 0 halfway (Suave), -0.15 at the end.
        val s = listOf(rgb(f0, px(0.05f), y), rgb(f0, px(0.25f), y), rgb(fm, px(-0.06f), y), rgb(fm, px(0.06f), y), rgb(f1, px(-0.25f), y), rgb(f1, px(-0.05f), y))
        log("CLIO-QA keys zoompan: ${clip.lenS}s, samples $s")
        val fails = mutableListOf<String>()
        if (!isG(s[0]) || !isB(s[1])) fails += "start: edge should be at u=+0.15 (green at +0.05, blue at +0.25): ${s[0]} ${s[1]}"
        if (!isG(s[2]) || !isB(s[3])) fails += "halfway: edge should be at u=0: ${s[2]} ${s[3]}"
        if (!isG(s[4]) || !isB(s[5])) fails += "end: edge should be at u=-0.15 (green at -0.25, blue at -0.05): ${s[4]} ${s[5]}"
        // Zoomed in 1.25x everywhere: no letterbox at the corners.
        if (listOf(f0, f1).any { f -> rgb(f, 2, 2).let { it.first + it.second + it.third < 60 } }) fails += "black corner: zoom should cover the canvas"
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
