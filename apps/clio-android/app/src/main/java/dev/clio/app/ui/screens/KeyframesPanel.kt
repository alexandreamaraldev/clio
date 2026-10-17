package dev.clio.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.clio.app.ui.components.pressable
import dev.clio.app.ui.components.rememberHaptics
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space

/** Keyframe easing (engine `Ease`), pt-BR labels. */
enum class KeyEase(val label: String) { Linear("Linear"), Suave("Suave"), Entrada("Entrada"), Saida("Saída"), Salto("Salto") }

/** One keyframe time on a clip (seconds from the clip start) and the ease leaving it. */
@Immutable
data class UiKey(val atS: Float, val ease: KeyEase)

/** Zoom/Pan ("Ken Burns"): the frame at the clip start and at its end (scale >= 1 zooms in). */
@Immutable
data class UiZoomPan(val start: UiTransform, val end: UiTransform, val ease: KeyEase = KeyEase.Suave)

/** A keyframe at most this far from the playhead counts as "on" it (matches the engine's snap). */
internal const val KEY_SNAP_S = 0.02f

internal fun keyAt(keys: List<UiKey>, localS: Float): UiKey? = keys.firstOrNull { kotlin.math.abs(it.atS - localS) < KEY_SNAP_S }

/** Zoom/Pan presets: (label, start, end). Pans stay inside the zoomed frame (no black edges). */
internal val ZOOM_PAN_PRESETS = listOf(
    "Aproximar" to UiZoomPan(UiTransform(scale = 1f), UiTransform(scale = 1.3f)),
    "Afastar" to UiZoomPan(UiTransform(scale = 1.3f), UiTransform(scale = 1f)),
    "Para a direita" to UiZoomPan(UiTransform(x = 0.15f, scale = 1.25f), UiTransform(x = -0.15f, scale = 1.25f)),
    "Para a esquerda" to UiZoomPan(UiTransform(x = -0.15f, scale = 1.25f), UiTransform(x = 0.15f, scale = 1.25f)),
)

/** Keeps a zoomed frame covering the canvas: |offset| <= scale - 1. */
internal fun coverClamp(t: UiTransform): UiTransform {
    val s = t.scale.coerceIn(1f, 3f); val m = s - 1f
    return t.copy(scale = s, x = t.x.coerceIn(-m, m), y = t.y.coerceIn(-m, m))
}

private fun DrawScope.diamond(c: Offset, r: Float, color: Color, filled: Boolean) {
    val p = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }
    if (filled) drawPath(p, color) else drawPath(p, color, style = Stroke(width = r * 0.32f))
}

/** Diamond button: add/remove a keyframe at the playhead. Filled (accent) when one is there. */
@Composable
fun KeyframeButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val pop by animateFloatAsState(if (active) 1f else 0.82f, ClioMotion.snappy(), label = "kf")
    val color by animateColorAsState(if (!enabled) ClioColors.TextTertiary.copy(alpha = 0.35f) else if (active) ClioColors.Clay else ClioColors.TextSecondary, ClioMotion.fade(), label = "kfc")
    Box(Modifier.size(36.dp).testTag("keyframe-toggle")
        .semantics { contentDescription = "Quadro-chave"; stateDescription = if (active) "Neste ponto" else "Nenhum" }
        .pressable(enabled = enabled) { haptics.click(); onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp).graphicsLayer { scaleX = pop; scaleY = pop; rotationZ = (1f - pop) * 40f }) {
            diamond(center, size.minDimension / 2, color, active)
        }
    }
}

/** Small diamonds along a timeline clip at its keyframe times. */
@Composable
fun KeyDiamonds(keys: List<UiKey>, lenS: Float, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    if (keys.isEmpty() || lenS <= 0f) return
    val clay = ClioColors.Clay
    Canvas(modifier.testTag("clip-keyframes")) {
        val r = size.toPx() / 2
        keys.forEach { k ->
            val x = (k.atS / lenS).coerceIn(0f, 1f) * this.size.width
            diamond(Offset(x.coerceIn(r, this.size.width - r), this.size.height - r - 2f), r, clay, true)
        }
    }
}

@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary, modifier = Modifier.width(64.dp))
        Slider(value, onChange, Modifier.weight(1f).height(32.dp).testTag(tag), valueRange = range, onValueChangeFinished = onDone, colors = SliderDefaults.colors(
            thumbColor = ClioColors.Clay, activeTrackColor = ClioColors.Clay, inactiveTrackColor = ClioColors.SurfaceHigh))
    }
}

/** Start/end frame of a zoom/pan drawn as the visible window over the clip. */
@Composable
private fun FrameThumb(label: String, t: UiTransform, selected: Boolean, aspect: Float, modifier: Modifier, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) ClioColors.Clay else ClioColors.HairlineStrong, ClioMotion.fade(180), label = "ft")
    Column(modifier.pressable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        val clay = ClioColors.Clay
        Box(Modifier.height(64.dp).aspectRatio(aspect).clip(ClioShapes.sm).background(ClioColors.SurfaceHigh).border(if (selected) 2.dp else 1.dp, border, ClioShapes.sm)) {
            Canvas(Modifier.matchParentSize()) {
                // The canvas shows 1/scale of the clip, centred at -offset/scale (in clip NDC).
                val s = t.scale.coerceAtLeast(0.05f)
                val w = size.width / s; val h = size.height / s
                val cx = size.width / 2 - t.x / s * size.width / 2
                val cy = size.height / 2 + t.y / s * size.height / 2
                drawRect(clay.copy(alpha = 0.18f), Offset(cx - w / 2, cy - h / 2), Size(w, h))
                drawRect(clay, Offset(cx - w / 2, cy - h / 2), Size(w, h), style = Stroke(2f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) ClioColors.TextPrimary else ClioColors.TextTertiary)
    }
}

/**
 * "Animar": keyframes at the playhead (diamond + ease) and Zoom/Pan with start and end frames.
 * [localS] is the playhead in the clip's own seconds (null when the playhead is outside it).
 */
@Composable
fun AnimatePanel(
    clip: UiClip?, localS: () -> Float?, aspect: Float = 9f / 16f, allowZoomPan: Boolean = true,
    onToggle: () -> Unit = {}, onEase: (KeyEase) -> Unit = {}, onZoomPan: (UiZoomPan) -> Unit = {}, onClear: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().height(248.dp).padding(horizontal = Space.md).testTag("animate-panel")) {
        if (clip == null || !clip.keyable) {
            Text("Animar", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary)
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text("Selecione um clipe, foto ou camada.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
            }
            return@Column
        }
        var zoomMode by remember(clip.id) { mutableStateOf(allowZoomPan && clip.zoomPan != null) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Choice("Quadros-chave", !zoomMode, Modifier.testTag("animate-mode-keys")) { zoomMode = false }
            Spacer(Modifier.width(Space.sm))
            if (allowZoomPan) Choice("Zoom/Pan", zoomMode, Modifier.testTag("animate-mode-zoompan")) { zoomMode = true }
            Spacer(Modifier.weight(1f))
            if (clip.keys.isNotEmpty()) Text("Limpar", style = MaterialTheme.typography.labelLarge, color = ClioColors.TextSecondary,
                modifier = Modifier.testTag("keyframes-clear").pressable(onClick = onClear).padding(8.dp))
        }
        Spacer(Modifier.height(Space.sm))
        if (!zoomMode) {
            val at by remember(clip.keys) { derivedStateOf { localS()?.let { keyAt(clip.keys, it) } } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyframeButton(at != null, localS() != null, onToggle)
                Spacer(Modifier.width(Space.xs))
                Text(when { clip.keys.isEmpty() -> "Toque no losango para marcar a posição atual."; at != null -> "Quadro-chave neste ponto"
                    else -> "${clip.keys.size} quadros-chave · mova o vídeo e marque outro" },
                    style = MaterialTheme.typography.bodySmall, color = ClioColors.TextSecondary)
            }
            Spacer(Modifier.height(Space.sm))
            Text("Movimento até o próximo", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyEase.entries.forEach { e ->
                    Choice(e.label, at?.ease == e, Modifier.testTag("ease-${e.name}")) { if (at != null) onEase(e) }
                }
            }
            Spacer(Modifier.height(Space.sm))
            KeyDiamonds(clip.keys, clip.lenS, Modifier.fillMaxWidth().height(14.dp).clip(ClioShapes.pill).background(ClioColors.Surface), 9.dp)
            return@Column
        }
        var zp by remember(clip.id, clip.zoomPan) { mutableStateOf(clip.zoomPan ?: UiZoomPan(UiTransform(scale = 1f), UiTransform(scale = 1.3f))) }
        var editEnd by remember(clip.id) { mutableStateOf(true) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZOOM_PAN_PRESETS.forEach { (label, p) -> Choice(label, zp.start == p.start && zp.end == p.end && clip.zoomPan != null, Modifier.testTag("zoompan-$label")) { zp = p.copy(ease = zp.ease); onZoomPan(zp) } }
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FrameThumb("Início", zp.start, !editEnd, aspect, Modifier.testTag("zoompan-start")) { editEnd = false }
            Spacer(Modifier.width(Space.md))
            FrameThumb("Fim", zp.end, editEnd, aspect, Modifier.testTag("zoompan-end")) { editEnd = true }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                val t = if (editEnd) zp.end else zp.start
                val set = { n: UiTransform -> val c = coverClamp(n); zp = if (editEnd) zp.copy(end = c) else zp.copy(start = c) }
                val done = { onZoomPan(zp) }
                Slide("Zoom", t.scale, 1f..2f, "zoompan-scale", { set(t.copy(scale = it)) }, done)
                Slide("Lado", -t.x, -(t.scale - 1f).coerceAtLeast(0.001f)..(t.scale - 1f).coerceAtLeast(0.001f), "zoompan-x", { set(t.copy(x = -it)) }, done)
                Slide("Altura", t.y, -(t.scale - 1f).coerceAtLeast(0.001f)..(t.scale - 1f).coerceAtLeast(0.001f), "zoompan-y", { set(t.copy(y = it)) }, done)
            }
        }
    }
}
