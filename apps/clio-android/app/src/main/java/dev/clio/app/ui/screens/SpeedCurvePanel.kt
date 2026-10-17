package dev.clio.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.clio.app.ui.components.rememberHaptics
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Speed-curve presets shown as chips (ids match the engine's speed_presets). */
val SPEED_PRESETS = listOf("montagem" to "Montagem", "heroi" to "Herói", "bala" to "Bala", "flash_in" to "Flash de entrada", "flash_out" to "Flash de saída")
const val SPEED_CUSTOM = "personalizado"

/** Vertical axis: log scale 0.1x (bottom) .. 10x (top). */
internal fun speedToY(s: Float) = 1f - ((log10(s.coerceIn(0.1f, 10f)) + 1f) / 2f)
internal fun yToSpeed(y: Float) = 10f.pow((1f - y.coerceIn(0f, 1f)) * 2f - 1f)

/**
 * "Curva": preset chips plus an editable curve. Points: (x 0..1 of the source, speed). Dragging a
 * point moves it (endpoints only vertically); a tap on empty space adds one; the drag is
 * committed on release. [speedAt] draws the engine's interpolation (same as playback).
 */
@Composable
fun SpeedCurveSection(preset: String?, points: List<Pair<Float, Float>>, speedAt: (List<Pair<Float, Float>>, Float) -> Float,
                      onPreset: (String?) -> Unit, onCurve: (List<Pair<Float, Float>>) -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Choice("Nenhuma", preset == null) { haptics.tick(); onPreset(null) }
        SPEED_PRESETS.forEach { (id, label) -> Choice(label, preset == id, Modifier.testTag("speed-preset-$id")) { haptics.tick(); onPreset(id) } }
        if (preset == SPEED_CUSTOM) Choice("Personalizado", true) {}
    }
    Spacer(Modifier.height(Space.sm))
    var live by remember(points) { mutableStateOf(points.ifEmpty { listOf(0f to 1f, 1f to 1f) }) }
    var drag by remember { mutableIntStateOf(-1) }
    val press by animateFloatAsState(if (drag >= 0) 1f else 0f, ClioMotion.snappy(), label = "p")
    val accent = ClioColors.Clay
    val line = ClioColors.Hairline
    Box(Modifier.fillMaxWidth().height(96.dp).clip(ClioShapes.sm).background(ClioColors.SurfaceHigh)
        .testTag("speed-curve").semantics { contentDescription = "Curva de velocidade, ${live.size} pontos" }
        .pointerInput(points) {
            fun hit(o: Offset): Int = live.indices.minByOrNull { i -> val (x, s) = live[i]; abs(x * size.width - o.x) + abs(speedToY(s) * size.height - o.y) }
                ?.takeIf { i -> val (x, s) = live[i]; abs(x * size.width - o.x) < 48f && abs(speedToY(s) * size.height - o.y) < 64f } ?: -1
            detectDragGestures(
                onDragStart = { o ->
                    drag = hit(o)
                    if (drag < 0 && live.size < 12) { // add a point under the finger
                        val x = (o.x / size.width).coerceIn(0.02f, 0.98f)
                        live = (live + (x to yToSpeed(o.y / size.height))).sortedBy { it.first }
                        drag = live.indexOfFirst { it.first == x }
                    }
                    if (drag >= 0) haptics.tick()
                },
                onDragEnd = { drag = -1; onCurve(live) },
                onDragCancel = { drag = -1; live = points },
            ) { change, _ ->
                val i = drag; if (i < 0) return@detectDragGestures
                val p = change.position
                val last = live.lastIndex
                val x = when (i) { 0 -> 0f; last -> 1f; else -> (p.x / size.width).coerceIn(live[i - 1].first + 0.02f, live[i + 1].first - 0.02f) }
                val before = live[i].second; val s = yToSpeed(p.y / size.height)
                if ((before < 1f) != (s < 1f)) haptics.tick() // crossing 1x
                live = live.toMutableList().also { it[i] = x to s }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val y1 = speedToY(1f) * size.height
            drawLine(line, Offset(0f, y1), Offset(size.width, y1), 1.dp.toPx())
            val path = Path(); val n = 48
            for (k in 0..n) { val x = k / n.toFloat(); val y = speedToY(speedAt(live, x)) * size.height
                if (k == 0) path.moveTo(0f, y) else path.lineTo(x * size.width, y) }
            drawPath(path, accent, style = Stroke(2.dp.toPx()))
            live.forEachIndexed { i, (x, s) ->
                val r = (if (i == drag) 7f + 3f * press else 6f).dp.toPx()
                drawCircle(Color.White, r, Offset(x * size.width, speedToY(s) * size.height))
                drawCircle(accent, r - 2.dp.toPx(), Offset(x * size.width, speedToY(s) * size.height))
            }
        }
        Text("10x", Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = ClioColors.TextTertiary)
        Text("0,1x", Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = ClioColors.TextTertiary)
    }
}
