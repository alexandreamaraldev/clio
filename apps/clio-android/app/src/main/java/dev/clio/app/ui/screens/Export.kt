package dev.clio.app.ui.screens

import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.clio.app.engine.Engine
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.ExportRes
import dev.clio.app.engine.Mix
import androidx.compose.runtime.saveable.rememberSaveable
import dev.clio.app.engine.ExportState
import dev.clio.app.ui.components.*
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space

/** Export size for the project canvas at a resolution (short side) and frame rate. */
fun exportSpec(canvasW: Int, canvasH: Int, res: ExportRes, fps: Int): ExportSpec {
    val g = gcd(canvasW, canvasH).coerceAtLeast(1)
    val (w, h) = Mix.canvas(canvasW / g, canvasH / g, res.shortSide)
    return ExportSpec("${res.label} · $fps fps", w, h, fps)
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/** Export sheet: preset picker → real progress (Clio "working") → saved to gallery (Clio "happy" + haptic). */
@Composable
fun ExportSheet(
    state: ExportState,
    onStart: (ExportSpec) -> Unit,
    onShare: (android.net.Uri) -> Unit,
    onClose: () -> Unit,
    canvasW: Int = 1080,
    canvasH: Int = 1920,
) {
    val haptics = rememberHaptics()
    BackHandler(onBack = onClose)
    var res by rememberSaveable { mutableStateOf(ExportRes.P1080) }
    var fps by rememberSaveable { mutableIntStateOf(30) }
    val spec = exportSpec(canvasW, canvasH, res, fps)
    LaunchedEffect(state is ExportState.Done) { if (state is ExportState.Done) haptics.confirm() }

    Box(Modifier.fillMaxSize().background(ClioColors.Scrim).pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = {
        if (state !is ExportState.Running) onClose()
    }), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().pressable(onClick = {}).clip(ClioShapes.lg).background(ClioColors.Surface)
                .border(1.dp, ClioColors.Hairline, ClioShapes.lg).navigationBarsPadding().padding(Space.lg),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(ClioShapes.pill).background(ClioColors.HairlineStrong))
            Spacer(Modifier.height(Space.lg))
            AnimatedContent(state, contentKey = { it is ExportState.Choosing }, transitionSpec = {
                (fadeIn(ClioMotion.fade()) + slideInVertically(ClioMotion.slide) { it / 6 }) togetherWith fadeOut(ClioMotion.fade(160))
            }, label = "export") { st ->
                if (st is ExportState.Choosing || st is ExportState.Closed) Column {
                    Text("Exportar", style = MaterialTheme.typography.headlineMedium, color = ClioColors.TextPrimary)
                    Spacer(Modifier.height(Space.md))
                    Text("Resolução", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary)
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        ExportRes.entries.forEach { r -> Choice(r.label, r == res, Modifier.weight(1f)) { res = r } }
                    }
                    Spacer(Modifier.height(Space.md))
                    Text("Quadros por segundo", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary)
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        listOf(24, 30, 60).forEach { f -> Choice("$f", f == fps, Modifier.weight(1f)) { fps = f } }
                    }
                    Spacer(Modifier.height(Space.md))
                    Text("${spec.width}×${spec.height} · ${spec.fps} fps" + if (res == ExportRes.K4) " · 4K pode ser reduzido se o aparelho não suportar" else "",
                        style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
                    Spacer(Modifier.height(Space.lg))
                    PrimaryButton("Exportar vídeo", Modifier.fillMaxWidth()) { onStart(spec) }
                } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val done = st is ExportState.Done
                    val failed = st is ExportState.Failed
                    val p = when (st) { is ExportState.Running -> st.progress; is ExportState.Done -> 1f; else -> 0f }
                    val shown by animateFloatAsState(p, ClioMotion.gentle(), label = "p")
                    ClioCharacter(mood = when { done -> Mood.Happy; failed -> Mood.Sleepy; else -> Mood.Working }, size = 56.dp)
                    Spacer(Modifier.height(Space.lg))
                    Text(when { done -> "Pronto."; failed -> "Não deu certo"; else -> "Renderizando…" }, style = MaterialTheme.typography.headlineMedium, color = ClioColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(when (st) {
                        is ExportState.Done -> "Salvo na galeria, em Filmes/Clio"
                        is ExportState.Failed -> st.message
                        is ExportState.Running -> "${(shown * 100).toInt()}% · ${st.label}"
                        else -> ""
                    }, style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary,
                        modifier = if (failed) Modifier.testTag("export_error") else Modifier)
                    Spacer(Modifier.height(Space.lg))
                    if (!failed) Box(Modifier.fillMaxWidth().height(4.dp).clip(ClioShapes.pill).background(ClioColors.SurfaceHigh)) {
                        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(ClioShapes.pill).background(if (done) ClioColors.Clay else ClioColors.Primary))
                    }
                    Spacer(Modifier.height(Space.lg))
                    when (st) {
                        is ExportState.Done -> Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            GhostButton("Fechar", Modifier.weight(1f), onClick = onClose)
                            PrimaryButton("Compartilhar", Modifier.weight(1f)) { onShare(st.uri) }
                        }
                        is ExportState.Failed -> GhostButton("Fechar", Modifier.fillMaxWidth(), onClick = onClose)
                        else -> GhostButton("Cancelar", Modifier.fillMaxWidth(), onClick = onClose)
                    }
                }
            }
        }
    }
}
