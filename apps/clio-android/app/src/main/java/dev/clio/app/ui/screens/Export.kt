package dev.clio.app.ui.screens

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
import dev.clio.app.engine.ExportState
import dev.clio.app.ui.components.*
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space

fun exportSpecs(): List<ExportSpec> =
    Engine.presets().map { ExportSpec(it.label, it.width.toInt(), it.height.toInt(), it.fps.toInt()) }
        .ifEmpty { listOf(ExportSpec("Stories / Reels / TikTok", 1080, 1920, 30), ExportSpec("YouTube 1080p", 1920, 1080, 30)) }
        .filter { it.width * it.height <= 1920 * 1080 } // 4K encode is too slow/unsupported on many phones for now

/** Export sheet: preset picker → real progress (Clio "working") → saved to gallery (Clio "happy" + haptic). */
@Composable
fun ExportSheet(
    state: ExportState,
    onStart: (ExportSpec) -> Unit,
    onShare: (android.net.Uri) -> Unit,
    onClose: () -> Unit,
) {
    val haptics = rememberHaptics()
    BackHandler(onBack = onClose)
    val presets = remember { exportSpecs() }
    var choice by remember { mutableIntStateOf(0) }
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
                    presets.forEachIndexed { i, p ->
                        val sel = i == choice
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).pressable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { choice = i })
                                .clip(ClioShapes.md).background(if (sel) ClioColors.SurfaceRaised else ClioColors.Surface)
                                .border(1.dp, if (sel) ClioColors.HairlineStrong else ClioColors.Hairline, ClioShapes.md).padding(Space.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label, style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary)
                                Text("${p.width}×${p.height} · ${p.fps} fps", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
                            }
                            val dot by animateDpAsState(if (sel) 10.dp else 0.dp, ClioMotion.snappy(), label = "d")
                            Box(Modifier.size(18.dp).clip(ClioShapes.pill).border(1.dp, ClioColors.HairlineStrong, ClioShapes.pill), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(dot).clip(ClioShapes.pill).background(ClioColors.Primary))
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.lg))
                    PrimaryButton("Exportar vídeo", Modifier.fillMaxWidth()) { onStart(presets[choice]) }
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
                    }, style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
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
