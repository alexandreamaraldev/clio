package dev.clio.app

import android.content.Context
import android.content.Intent
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
import dev.clio.app.engine.Exporter
import dev.clio.app.engine.QaHooks
import dev.clio.app.ui.components.ClioCharacterConfig
import dev.clio.app.ui.screens.CaptionStyle
import dev.clio.app.ui.screens.EditorUi
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

/** Speed curves: export length/audio follow the integrated curve; the Curva UI applies presets with undo. */
@RunWith(AndroidJUnit4::class)
class SpeedCurveE2ETest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() { ClioCharacterConfig.enabled = false; AppStore(ctx).onboarded = true; QaHooks.ui = null; QaHooks.export = null }
    @After fun tearDown() { scenario?.close() }

    private fun log(s: String) { android.util.Log.i("ClioQA", "CLIO-QA speed-curve $s"); println("CLIO-QA speed-curve $s") }

    private fun copyVideo(): File {
        val dst = File(File(ctx.cacheDir, "import").apply { mkdirs() }, "clio-test.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        assertTrue(dst.length() > 10_000)
        return dst
    }

    @Test fun exportFollowsTheCurve() {
        val src = Uri.fromFile(copyVideo()).toString()
        // Curve on the first and on the second item (the second checks the sequence timestamp origin).
        for ((name, which, preset) in listOf(Triple("bala-first", 0, "bala"), Triple("flash-second", 1, "flash_in"), Triple("heroi-both", 2, "heroi"))) {
            val p = ClioProject("speed-$name", 1080u, 1920u)
            val v = p.addTrack(TrackKind.VIDEO, "V")
            val a = p.appendClip(v, "a", src, 3000u); p.setClipSourceInfo(a, 6000u, 1280u, 720u)
            val b = p.appendClip(v, "b", src, 3000u); p.setClipSourceInfo(b, 6000u, 1280u, 720u)
            if (which != 1) p.setSpeedPreset(a, preset)
            if (which != 0) p.setSpeedPreset(b, preset)
            val total = p.summary().durationMs.toLong()
            val uri = runBlocking { withTimeout(240_000) { Exporter(ctx).export(p.snapshot(), ExportSpec("speed-$name", 720, 1280, 30), CaptionStyle.Clean) {} } }
            val info = ExportProbe.info(ctx, uri)
            log("$name: timeline ${total}ms, video ${info.durationMs}ms, audio ${info.audioDurationMs}ms, fps ${"%.1f".format(info.fps)}")
            assertTrue("$name video ${info.durationMs} vs $total", abs(info.durationMs - total) <= 200)
            assertTrue("$name has audio", info.hasAudio)
            assertTrue("$name audio ${info.audioDurationMs} vs $total", abs(info.audioDurationMs - total) <= 300)
        }
    }

    private fun clips(u: EditorUi = QaHooks.ui!!) = u.tracks.first { it.kind == "video" }.clips
    private fun waitUi(what: String, cond: (EditorUi) -> Boolean) {
        try { compose.waitUntil(30_000) { QaHooks.ui?.let(cond) == true } } catch (e: Throwable) { throw AssertionError("timed out waiting for: $what; ui=${QaHooks.ui}", e) }
        compose.waitForIdle()
    }

    @Test fun curvaPanelPresetsAndUndo() {
        val f = copyVideo()
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        waitUi("imported") { it.hasMedia && clips(it).size == 1 }
        val before = clips().first().lenS
        compose.onNodeWithTag("clip-video-0", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("tools", useUnmergedTree = true).performScrollToNode(hasText("Velocidade"))
        compose.onNode(hasText("Velocidade") and hasAnyAncestor(hasTestTag("tools")), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("speed-mode-curve").performClick()
        compose.onNodeWithTag("speed-preset-bala").performScrollTo().performClick()
        waitUi("bala applied") { clips(it).first().speedPreset == "bala" }
        val after = clips().first()
        log("ui: ${before}s -> ${after.lenS}s, mean ${after.speed}x, ${after.speedPoints.size} points")
        assertNotEquals(before, after.lenS, 0.05f)
        compose.onNodeWithTag("speed-curve").assertExists()
        // Dragging the middle of the curve down adds a point and makes it custom.
        compose.onNodeWithTag("speed-curve").performTouchInput { down(Offset(width * 0.25f, height * 0.5f)); moveBy(Offset(0f, height * 0.3f)); up() }
        waitUi("custom curve") { clips(it).first().speedPreset == "personalizado" }
        compose.onNodeWithContentDescription("Desfazer").performClick()
        waitUi("undo to bala") { clips(it).first().speedPreset == "bala" }
        compose.onNodeWithText("Nenhuma").performClick()
        waitUi("curve cleared") { clips(it).first().speedPreset == null }
    }
}
