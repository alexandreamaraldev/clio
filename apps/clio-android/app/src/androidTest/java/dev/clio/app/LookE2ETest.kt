package dev.clio.app

import android.content.Context
import android.content.Intent
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
import dev.clio.app.engine.LookPresets
import dev.clio.app.engine.QaHooks
import dev.clio.app.ui.components.ClioCharacterConfig
import dev.clio.app.ui.screens.EditorUi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.ColorGrade
import java.io.File
import kotlin.math.abs

/**
 * Filters and colour adjustments through the real UI: every preset, every slider, undo/redo, and
 * the grade surviving a draft save + cold reopen. (Export pixels: ExportGradeE2ETest.)
 */
@RunWith(AndroidJUnit4::class)
class LookE2ETest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() { ClioCharacterConfig.enabled = false; AppStore(ctx).onboarded = true; QaHooks.ui = null; QaHooks.export = null }
    @After fun tearDown() { shot("look-end"); scenario?.close() }

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes().decodeToString() } }
    private fun shot(name: String) { shell("mkdir -p /sdcard/clio-qa"); shell("screencap -p /sdcard/clio-qa/$name.png") }

    private fun testVideo(): Uri {
        val dst = File(File(ctx.cacheDir, "import").apply { mkdirs() }, "clio-test.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4")).use { i -> dst.outputStream().use { i.copyTo(it) } }
        assertTrue("test video missing", dst.length() > 10_000)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dst)
    }

    private fun waitUi(what: String, timeoutMs: Long = 30_000, cond: (EditorUi) -> Boolean) {
        try { compose.waitUntil(timeoutMs) { QaHooks.ui?.let(cond) == true } }
        catch (e: Throwable) { shot("fail-$what"); throw AssertionError("timed out waiting for: $what; ui=${QaHooks.ui}", e) }
        compose.waitForIdle()
    }

    private fun clipId() = QaHooks.ui!!.tracks.first { it.kind == "video" }.clips.single().id
    private fun grade(u: EditorUi = QaHooks.ui!!): ColorGrade? = u.grades[clipId()]

    private fun tool(label: String) {
        compose.onNodeWithTag("tools", useUnmergedTree = true).performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("tools")), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /** AdjustSlider carries "<label> <value>" as its content description. */
    private fun slide(label: String, v: Float) {
        compose.onNode(hasContentDescription("$label ", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        compose.waitForIdle()
    }

    /** The filter strip is a LazyRow: scroll it to the thumbnail, then tap it. */
    private fun pick(label: String) {
        compose.onNodeWithTag("filter-strip", useUnmergedTree = true)
            .performScrollToNode(hasContentDescription("Filtro $label"))
        compose.onNodeWithContentDescription("Filtro $label").performClick()
        compose.waitForIdle()
    }

    private fun openDraft() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("draft-0"))
        compose.onNodeWithTag("draft-0").performClick()
    }

    @Test fun presetsSlidersUndoRedoAndDraft() {
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, testVideo()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        waitUi("video imported") { it.hasMedia && it.tracks.first { t -> t.kind == "video" }.clips.size == 1 }
        compose.onNodeWithTag("clip-video-0", useUnmergedTree = true).performClick()

        // Every preset, then back to none.
        tool("Filtros")
        for ((id, label) in LookPresets.all) {
            pick(label)
            waitUi("preset $id") { grade(it)?.filter == id && grade(it)?.intensity == 1f }
            shot("look-preset-$id")
        }
        pick("Nenhum")
        waitUi("no filter") { grade(it) == null }
        pick("Vívido")
        waitUi("vivido") { grade(it)?.filter == "vivido" }
        tool("Filtros")

        // Every slider, each committed as its own grade field.
        tool("Ajustes")
        val sliders = listOf<Triple<String, Float, (ColorGrade) -> Float>>(
            Triple("Brilho", 0.5f) { it.brightness }, Triple("Contraste", -0.4f) { it.contrast },
            Triple("Saturação", 0.6f) { it.saturation }, Triple("Temperatura", -0.7f) { it.temperature },
            Triple("Realces", -0.3f) { it.highlights }, Triple("Sombras", 0.8f) { it.shadows },
        )
        for ((label, v, field) in sliders) {
            slide(label, v)
            waitUi("slider $label") { grade(it)?.let { g -> abs(field(g) - v) < 0.02f } == true }
        }
        val full = grade()!!
        assertEquals("vivido", full.filter)
        shot("look-adjusted")

        // Undo the last slider, redo it.
        compose.onNodeWithContentDescription(ctx.getString(R.string.action_undo)).performClick()
        waitUi("undo shadows") { grade(it)?.let { g -> g.shadows == 0f && abs(g.highlights + 0.3f) < 0.02f } == true }
        compose.onNodeWithContentDescription(ctx.getString(R.string.action_redo)).performClick()
        waitUi("redo shadows") { grade(it) == full }
        // Undo back past the whole grade -> neutral, then redo everything.
        repeat(LookPresets.all.size + 2 + sliders.size) { // presets, Nenhum, Vívido, sliders
            compose.onNodeWithContentDescription(ctx.getString(R.string.action_undo)).performClick()
            compose.waitForIdle()
        }
        waitUi("undone to neutral") { grade(it) == null }
        while (QaHooks.ui!!.canRedo) { compose.onNodeWithContentDescription(ctx.getString(R.string.action_redo)).performClick(); compose.waitForIdle() }
        waitUi("redone to full grade") { grade(it) == full }
        tool("Ajustes")

        // Draft save + cold reopen keeps the grade.
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.close(); QaHooks.ui = null
        scenario = ActivityScenario.launch(MainActivity::class.java)
        openDraft()
        waitUi("draft reopened") { it.hasMedia && grade(it) != null }
        assertEquals(full, grade())
        shot("look-reopened")
    }
}
