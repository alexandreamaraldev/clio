package dev.clio.app

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
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
 * End-to-end tests on a real device/emulator: the test video is pushed by CI to
 * /data/local/tmp/clio-test.mp4 (6 s, 1280x720, 30 fps, AAC audio), shared into Clio,
 * edited through the real UI and exported through Media3 to the gallery.
 */
@RunWith(AndroidJUnit4::class)
class EditorE2ETest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        ClioCharacterConfig.enabled = false // its idle loop would keep Compose busy forever
        AppStore(ctx).onboarded = true
        QaHooks.ui = null; QaHooks.export = null
    }

    @After fun tearDown() { shot("end-${System.currentTimeMillis()}"); scenario?.close() }

    // --- helpers ---

    private fun shell(cmd: String): String =
        inst.uiAutomation.executeShellCommand(cmd).let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() } }

    private fun shot(name: String) { shell("mkdir -p /sdcard/clio-qa"); shell("screencap -p /sdcard/clio-qa/$name.png") }

    /** Copies the CI test video into the app cache (the app can't read /data/local/tmp itself). */
    private fun testVideo(): Uri {
        val dir = File(ctx.cacheDir, "import").apply { mkdirs() }
        val dst = File(dir, "clio-test.mp4")
        inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4").let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> dst.outputStream().use { input.copyTo(it) } }
        }
        assertTrue("test video missing: push it to /data/local/tmp/clio-test.mp4", dst.length() > 10_000)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dst)
    }

    private fun launchWithVideo() {
        val uri = testVideo()
        val i = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(i)
        waitUi("video imported") { u -> u.hasMedia && videoClips(u).size == 1 }
        shot("01-imported")
    }

    private fun videoClips(u: EditorUi = QaHooks.ui!!) = u.tracks.first { it.kind == "video" }.clips

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

    private fun closePanel(label: String) = tool(label) // tapping the active tool closes its panel

    /** Drags the timeline so the playhead moves forward by about [dp]/64 seconds. */
    private fun scrubForward(dp: Float) {
        compose.onNodeWithTag("timeline", useUnmergedTree = true).performTouchInput {
            val y = centerY; val px = dp * density
            down(Offset(centerX + px / 2, y)); repeat(10) { moveBy(Offset(-px / 10, 0f)) }; up()
        }
        compose.waitForIdle()
    }

    private fun exportAndCheck(res: String, w: Int, h: Int): Uri {
        compose.onNodeWithText("Exportar").performClick()
        compose.onNodeWithText(res).performClick()
        shot("export-$res-choose")
        compose.onNodeWithText("Exportar vídeo").performClick()
        try { compose.waitUntil(300_000) { QaHooks.export is ExportState.Done || QaHooks.export is ExportState.Failed } }
        catch (e: Throwable) { shot("fail-export-$res"); throw e }
        shot("export-$res-done")
        val st = QaHooks.export
        assertTrue("export $res failed: ${(st as? ExportState.Failed)?.message}", st is ExportState.Done)
        val uri = (st as ExportState.Done).uri
        val expected = (QaHooks.ui!!.durationS * 1000).toLong()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (ow, oh) = if (rot % 180 != 0) vh to vw else vw to vh
            println("CLIO-QA export $res: ${ow}x$oh ${d}ms (expected ${w}x$h ${expected}ms) audio=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)}")
            assertTrue("export $res duration $d ms, timeline $expected ms", abs(d - expected) <= 350)
            assertEquals("export $res size", w to h, ow to oh)
            assertEquals("export $res audio track", "yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
        } finally { r.release() }
        ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { assertTrue("exported file is empty", it.statSize > 50_000) }
        compose.onNodeWithText("Fechar").performClick()
        compose.waitForIdle()
        return uri
    }

    /** Home: scroll to the first draft card (it's below the fold) and optionally open it. */
    private fun openDraftCard(check: Boolean) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("draft-0"))
        shot(if (check) "11-home-reopen" else "11-home")
        if (check) compose.onNodeWithTag("draft-0").performClick()
    }

    // --- tests ---

    @Test fun fullEditAndExport() {
        launchWithVideo()
        val src = videoClips().single()
        assertEquals(6f, src.lenS, 0.3f)

        // Trim: select the clip and drag its right handle left.
        compose.onNodeWithTag("clip-video-0", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("trim-right", useUnmergedTree = true).performTouchInput { down(center); repeat(10) { moveBy(Offset(-12f * density, 0f)) }; up() }
        waitUi("trim") { videoClips(it).single().lenS < src.lenS - 0.5f }
        val trimmed = videoClips().single().lenS
        shot("02-trimmed")

        // Split at the playhead, then duplicate the first part.
        compose.onNodeWithTag("clip-video-0", useUnmergedTree = true).performClick() // deselect so split uses the playhead
        scrubForward(96f)
        tool("Dividir")
        waitUi("split") { videoClips(it).size == 2 }
        assertEquals(trimmed, videoClips().sumOf { it.lenS.toDouble() }.toFloat(), 0.05f)
        compose.onNodeWithTag("clip-video-0", useUnmergedTree = true).performClick()
        tool("Duplicar")
        waitUi("duplicate") { videoClips(it).size == 3 }
        shot("03-split-duplicated")

        // Reorder: long-press the last clip and drag it to the front.
        val before = videoClips().map { it.id }
        compose.onNodeWithTag("clip-video-2", useUnmergedTree = true).performTouchInput {
            down(center); advanceEventTime(900); repeat(20) { moveBy(Offset(-30f * density, 0f)) }; up()
        }
        waitUi("reorder") { videoClips(it).map { c -> c.id } != before }
        assertEquals(before.last(), videoClips().first().id)
        shot("04-reordered")

        // Speed 2x on the first clip: timeline length halves, source span kept.
        val first = videoClips().first()
        compose.onNodeWithTag("clip-video-0", useUnmergedTree = true).performClick()
        tool("Velocidade")
        compose.onNodeWithText("2x").performClick()
        waitUi("speed") { videoClips(it).first().speed == 2f }
        assertEquals(first.lenS / 2f, videoClips().first().lenS, 0.05f)
        shot("05-speed-2x")
        closePanel("Velocidade")

        // Volume: fade in on the selected clip.
        tool("Volume")
        compose.onNodeWithTag("fade-in", useUnmergedTree = true).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitUi("fade in") { videoClips(it).first().fadeInS > 0.1f }
        shot("06-volume-fade")
        closePanel("Volume")

        // Canvas 9:16 with a blurred background.
        tool("Formato")
        compose.onNodeWithText("1:1").performClick()
        waitUi("1:1") { it.canvasW == it.canvasH }
        compose.onNodeWithText("9:16").performClick()
        compose.onNodeWithText("Desfoque").performClick()
        waitUi("9:16 blur") { it.canvasW == 1080 && it.canvasH == 1920 && it.blurBg && !it.fill }
        shot("07-format-blur")
        closePanel("Formato")

        // Text overlay at the playhead.
        tool("Texto")
        compose.onNodeWithText("Adicionar texto").performClick()
        waitUi("text") { it.texts.size == 1 }
        shot("08-text")
        closePanel("Texto")

        val total = videoClips().sumOf { it.lenS.toDouble() }.toFloat()
        assertEquals(total, QaHooks.ui!!.durationS, 0.05f)
        exportAndCheck("720p", 720, 1280)
        exportAndCheck("1080p", 1080, 1920)
    }

    @Test fun splitSurvivesRecreateAndReopen() {
        launchWithVideo()
        scrubForward(128f)
        tool("Dividir")
        waitUi("split") { videoClips(it).size == 2 }
        val ids = videoClips().map { it.id }

        scenario!!.recreate()
        waitUi("after recreate") { videoClips(it).size == 2 }
        shot("10-recreated")
        assertEquals(ids, videoClips().map { it.id })

        // Leave the editor (flushes the draft), kill the activity and reopen the draft from Home.
        compose.onNodeWithContentDescription("Voltar").performClick()
        openDraftCard(check = false)
        scenario!!.close()
        QaHooks.ui = null
        scenario = ActivityScenario.launch(MainActivity::class.java)
        openDraftCard(check = true)
        waitUi("draft reopened") { it.hasMedia }
        shot("12-reopened")
        assertEquals(2, videoClips().size)
        assertEquals(6f, videoClips().sumOf { it.lenS.toDouble() }.toFloat(), 0.3f)
    }
}
