package dev.clio.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.clio.app.engine.Aspect
import dev.clio.app.ui.components.pressable
import dev.clio.app.ui.components.rememberHaptics
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
private fun PanelTitle(title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
    }
}

@Composable
fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) ClioColors.Primary else ClioColors.Surface, ClioMotion.fade(180), label = "c")
    val fg by animateColorAsState(if (selected) ClioColors.OnPrimary else ClioColors.TextPrimary, ClioMotion.fade(180), label = "f")
    Box(modifier.pressable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick).clip(ClioShapes.pill).background(bg)
        .border(1.dp, if (selected) bg else ClioColors.Hairline, ClioShapes.pill).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

@Composable
private fun ClioSlider(value: Float, onChange: (Float) -> Unit, onDone: () -> Unit, range: ClosedFloatingPointRange<Float> = 0f..1f, tag: String = "") {
    Slider(value, onChange, Modifier.testTag(tag), valueRange = range, onValueChangeFinished = onDone, colors = SliderDefaults.colors(
        thumbColor = ClioColors.Clay, activeTrackColor = ClioColors.Clay, inactiveTrackColor = ClioColors.SurfaceHigh,
        activeTickColor = ClioColors.Clay, inactiveTickColor = ClioColors.SurfaceHigh))
}

@Composable
private fun NoClip() = Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
    Text("Selecione um clipe na linha do tempo.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
}

/** Constant speed 0.1x–10x on a log scale, with quick presets. */
@Composable
fun SpeedPanel(clip: UiClip?, onSpeed: (Float) -> Unit, onPreset: (String?) -> Unit = {}, onCurve: (List<Pair<Float, Float>>) -> Unit = {},
               speedAt: (List<Pair<Float, Float>>, Float) -> Float = { p, _ -> p.firstOrNull()?.second ?: 1f }) {
    Column(Modifier.fillMaxWidth().height(248.dp).padding(horizontal = Space.md)) {
        if (clip == null) { PanelTitle("Velocidade", ""); NoClip(); return@Column }
        val haptics = rememberHaptics()
        var curveMode by remember(clip.id) { mutableStateOf(clip.speedPreset != null) }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Choice("Constante", !curveMode, Modifier.testTag("speed-mode-constant")) { curveMode = false }
            Choice("Curva", curveMode, Modifier.testTag("speed-mode-curve")) { curveMode = true }
        }
        Spacer(Modifier.height(Space.sm))
        if (curveMode) {
            PanelTitle("Curva", "média ${fmtSpeed(snapSpeed(clip.speed))} · ${"%.1f".format(clip.lenS).replace('.', ',')} s")
            Spacer(Modifier.height(Space.sm))
            SpeedCurveSection(clip.speedPreset, clip.speedPoints, speedAt, onPreset, onCurve)
            return@Column
        }
        var pos by remember(clip.id, clip.speed) { mutableFloatStateOf(((log10(clip.speed) + 1f) / 2f).coerceIn(0f, 1f)) }
        val speed = snapSpeed(10f.pow(pos * 2f - 1f))
        PanelTitle("Velocidade", "${fmtSpeed(speed)} · ${"%.1f".format(clip.lenS * clip.speed / speed).replace('.', ',')} s")
        Spacer(Modifier.height(Space.lg))
        ClioSlider(pos, { val before = snapSpeed(10f.pow(pos * 2f - 1f)); pos = it; if (snapSpeed(10f.pow(it * 2f - 1f)) == 1f && before != 1f) haptics.tick() }, { onSpeed(speed) })
        Row(Modifier.fillMaxWidth()) {
            listOf("0,1x", "1x", "10x").forEachIndexed { i, t ->
                Text(t, style = MaterialTheme.typography.labelSmall, color = ClioColors.TextTertiary, modifier = Modifier.weight(1f),
                    textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; 1 -> androidx.compose.ui.text.style.TextAlign.Center; else -> androidx.compose.ui.text.style.TextAlign.End })
            }
        }
        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            listOf(0.5f, 1f, 1.5f, 2f, 4f).forEach { v -> Choice(fmtSpeed(v), speed == v) { onSpeed(v) } }
        }
    }
}

/** Rounds to 0.1 below 2x and to 0.5 above, so the readout feels deliberate. */
internal fun snapSpeed(s: Float): Float = if (s < 2f) (s * 10).roundToInt() / 10f else (s * 2).roundToInt() / 2f

/** Per-clip volume 0–200 %, fade in/out (0–3 s), mute. */
@Composable
fun VolumePanel(clip: UiClip?, onAudio: (volume: Float, fadeInS: Float, fadeOutS: Float, muted: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        if (clip == null) { PanelTitle("Volume", ""); NoClip(); return@Column }
        var vol by remember(clip.id, clip.volume) { mutableFloatStateOf(clip.volume) }
        var fi by remember(clip.id, clip.fadeInS) { mutableFloatStateOf(clip.fadeInS) }
        var fo by remember(clip.id, clip.fadeOutS) { mutableFloatStateOf(clip.fadeOutS) }
        val maxFade = (clip.lenS / 2).coerceIn(0f, 3f)
        val commit = { m: Boolean -> onAudio(vol, fi, fo, m) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(if (clip.muted) "Mudo" else "${(vol * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
            Spacer(Modifier.width(Space.sm))
            Choice(if (clip.muted) "Ativar som" else "Silenciar", clip.muted) { commit(!clip.muted) }
        }
        ClioSlider(vol, { vol = it }, { commit(clip.muted) }, 0f..2f, "volume")
        Text("Aparecer  ${"%.1f".format(fi).replace('.', ',')} s", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary)
        ClioSlider(fi, { fi = it }, { commit(clip.muted) }, 0f..maxFade.coerceAtLeast(0.01f), "fade-in")
        Text("Sumir  ${"%.1f".format(fo).replace('.', ',')} s", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary)
        ClioSlider(fo, { fo = it }, { commit(clip.muted) }, 0f..maxFade.coerceAtLeast(0.01f), "fade-out")
    }
}

/** Canvas aspect ratio + how media sits in it. */
@Composable
fun FormatPanel(w: Int, h: Int, fill: Boolean, blur: Boolean, onAspect: (Aspect) -> Unit, onFraming: (fill: Boolean, blur: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        val cur = Aspect.of(w, h)
        PanelTitle("Formato", "${w}×${h}")
        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Aspect.entries.forEach { a ->
                Column(Modifier.weight(1f).pressable(onClick = { onAspect(a) }), horizontalAlignment = Alignment.CenterHorizontally) {
                    val sel = a == cur
                    val border by animateColorAsState(if (sel) ClioColors.Clay else ClioColors.HairlineStrong, ClioMotion.fade(180), label = "a")
                    Box(Modifier.height(44.dp).aspectRatio(a.w.toFloat() / a.h).clip(ClioShapes.sm).border(if (sel) 2.dp else 1.dp, border, ClioShapes.sm))
                    Spacer(Modifier.height(6.dp))
                    Text(a.label, style = MaterialTheme.typography.labelMedium, color = if (sel) ClioColors.TextPrimary else ClioColors.TextTertiary)
                }
            }
        }
        Spacer(Modifier.height(Space.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Choice("Ajustar", !fill && !blur, Modifier.weight(1f)) { onFraming(false, false) }
            Choice("Desfoque", !fill && blur, Modifier.weight(1f)) { onFraming(false, true) }
            Choice("Preencher", fill, Modifier.weight(1f)) { onFraming(true, false) }
        }
    }
}
