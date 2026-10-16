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
    @Test fun editorStyledText() = shot(ThemeMode.Dark) {
        val st = dev.clio.app.engine.StyledText.fallbackStyle.copy(font = uniffi.clio_ffi.TextFont.PACIFICO, size = 0.07f, color = 0xFFF2683Cu,
            strokeColor = 0xFFFFFFFFu, strokeWidth = 0.12f)
        EditorScreen(ui(caps).copy(texts = listOf(TextItem(1, "Olá, São Paulo!", 2000, 5000, st))), { 3.0f }, initialTool = TOOL_TEXT)
    }
    @Test fun editorSpeed() = shot(ThemeMode.Dark) { EditorScreen(ui(), { 2.4f }, initialTool = TOOL_SPEED) }
    @Test fun editorVolume() = shot(ThemeMode.Light) { EditorScreen(ui(), { 2.4f }, initialTool = TOOL_VOLUME) }
    @Test fun editorFormat() = shot(ThemeMode.Dark) { EditorScreen(ui().copy(canvasW = 1080, canvasH = 1080, blurBg = true), { 2.4f }, initialTool = TOOL_FORMAT) }
    @Test fun editorMusic() = shot(ThemeMode.Light) { EditorScreen(ui().copy(music = "Lo-fi morning"), { 2.4f }, initialTool = TOOL_MUSIC) }
    @Test fun exportChoose() = shot(ThemeMode.Dark) { ExportSheet(dev.clio.app.engine.ExportState.Choosing, {}, {}, {}) }
    @Test fun exportDone() = shot(ThemeMode.Light) { ExportSheet(dev.clio.app.engine.ExportState.Done(android.net.Uri.EMPTY), {}, {}, {}) }
    @Test fun exportProgress() = shot(ThemeMode.Dark) { ExportSheet(dev.clio.app.engine.ExportState.Running(0.62f, "Stories / Reels / TikTok"), {}, {}, {}) }
    private fun w(s: Long, e: Long, t: String) = uniffi.clio_ffi.CaptionWord(s.toULong(), e.toULong(), t)
    private val ptCaps = listOf(Engine.Cap(2000, 4600, "Atenção: você não vai acreditar!",
        listOf(w(2000, 2600, "Atenção:"), w(2600, 3000, "você"), w(3000, 3400, "não"), w(3400, 3700, "vai"), w(3700, 4600, "acreditar!"))))
    // Playhead on "não" (3.1 s). Note: Paparazzi has no native engine, so the overlay uses the
    // StyledText fallback (plain line); these shots cover the preset picker + panel per preset.
    private fun preset(mode: ThemeMode, s: CaptionStyle) = shot(mode) { EditorScreen(ui(ptCaps).copy(captionStyle = s), { 3.1f }, initialTool = TOOL_CAPTIONS) }
    @Test fun captionPresetClean() = preset(ThemeMode.Dark, CaptionStyle.Clean)
    @Test fun captionPresetBold() = preset(ThemeMode.Dark, CaptionStyle.Bold)
    @Test fun captionPresetBox() = preset(ThemeMode.Light, CaptionStyle.Box)
    @Test fun captionPresetKaraoke() = preset(ThemeMode.Dark, CaptionStyle.Karaoke)
    @Test fun captionPresetPop() = preset(ThemeMode.Dark, CaptionStyle.Pop)
    @Test fun captionPresetBounce() = preset(ThemeMode.Dark, CaptionStyle.Bounce)
    @Test fun captionPresetHighlight() = preset(ThemeMode.Light, CaptionStyle.Highlight)
}
