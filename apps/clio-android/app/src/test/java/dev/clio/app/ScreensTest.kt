package dev.clio.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.runtime.Composable
import dev.clio.app.engine.Engine
import dev.clio.app.ui.screens.*
import dev.clio.app.ui.theme.ClioTheme
import dev.clio.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test

/** Screenshot tests (Paparazzi). Record: ./gradlew recordPaparazziDebug */
class ScreensTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private fun shot(mode: ThemeMode, c: @Composable () -> Unit) = paparazzi.snapshot { ClioTheme(mode) { c() } }
    private val caps = listOf(Engine.Cap(2000, 4000, "Hoje vamos editar juntos"), Engine.Cap(4100, 7000, "Corte, ajuste e compartilhe"))

    @Test fun onboarding() = shot(ThemeMode.Dark) { OnboardingScreen {} }
    @Test fun homeDark() = shot(ThemeMode.Dark) { HomeScreen(5, themeMode = ThemeMode.Dark, onCycleTheme = {}, onNewProject = {}, onOpenDraft = {}) }
    @Test fun homeLight() = shot(ThemeMode.Light) { HomeScreen(5, themeMode = ThemeMode.Light, onCycleTheme = {}, onNewProject = {}, onOpenDraft = {}) }
    @Test fun homeEmpty() = shot(ThemeMode.Dark) { HomeScreen(0, drafts = emptyList(), themeMode = ThemeMode.Dark, onCycleTheme = {}, onNewProject = {}, onOpenDraft = {}) }
    private fun ui(c: List<Engine.Cap> = emptyList(), st: CaptionStatus = CaptionStatus.Idle) =
        EditorUi("Lisboa, outono", Samples.tracks, 12.3f, false, c, st, SaveState.Saved, canUndo = true)
    @Test fun editorDark() = shot(ThemeMode.Dark) { EditorScreen(ui(caps), { 3.0f }) }
    @Test fun editorLight() = shot(ThemeMode.Light) { EditorScreen(ui(), { 2.4f }) }
    @Test fun editorEmpty() = shot(ThemeMode.Dark) { EditorScreen(EditorUi("Novo projeto", listOf(UiTrack("Vídeo", "video", emptyList())), 0f, false, hasMedia = false, savedLabel = SaveState.Draft), { 0f }) }
    @Test fun editorCaptions() = shot(ThemeMode.Dark) { EditorScreen(ui(caps), { 3.0f }, initialTool = TOOL_CAPTIONS) }
    @Test fun editorCaptionsDownloading() = shot(ThemeMode.Dark) { EditorScreen(ui(st = CaptionStatus.Downloading(0.38f, 290)), { 2.4f }, initialTool = TOOL_CAPTIONS) }
    @Test fun editorText() = shot(ThemeMode.Dark) { EditorScreen(ui().copy(texts = listOf(TextItem(1, "Lisboa", 2000, 5000), TextItem(2, "Fim", 9000, 11000))), { 2.4f }, initialTool = TOOL_TEXT) }
    @Test fun editorMusic() = shot(ThemeMode.Light) { EditorScreen(ui().copy(music = "Lo-fi morning"), { 2.4f }, initialTool = TOOL_MUSIC) }
    @Test fun exportChoose() = shot(ThemeMode.Dark) { ExportSheet(dev.clio.app.engine.ExportState.Choosing, {}, {}, {}) }
    @Test fun exportDone() = shot(ThemeMode.Light) { ExportSheet(dev.clio.app.engine.ExportState.Done(android.net.Uri.EMPTY), {}, {}, {}) }
    @Test fun exportProgress() = shot(ThemeMode.Dark) { ExportSheet(dev.clio.app.engine.ExportState.Running(0.62f, "Stories / Reels / TikTok"), {}, {}, {}) }
}
