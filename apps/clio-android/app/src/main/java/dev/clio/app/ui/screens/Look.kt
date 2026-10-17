package dev.clio.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.clio.app.engine.Grades
import dev.clio.app.engine.LookPresets
import dev.clio.app.ui.LocalThumbnails
import dev.clio.app.ui.components.pressable
import dev.clio.app.ui.components.rememberHaptics
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.clio_ffi.ColorGrade
import kotlin.math.abs

/** Signed pt-BR percentage for adjustments: "+25%", "−40%", "0%". */
internal fun signedPct(v: Float): String {
    val p = PtBr.pct(abs(v))
    return when { v > 0.004f -> "+$p"; v < -0.004f -> "−$p"; else -> PtBr.pct(0f) }
}

/** A colourful test pattern for thumbnails when the clip has no frame yet (and in screenshots). */
private fun swatch(w: Int = 96, h: Int = 128): Bitmap {
    val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val fx = x / (w - 1f); val fy = y / (h - 1f)
        val r = (255 * (0.25f + 0.7f * fx)).toInt(); val g = (255 * (0.2f + 0.6f * (1 - fy))).toInt(); val b = (255 * (0.35f + 0.5f * fy * (1 - fx))).toInt()
        px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** Filtros: a strip of graded thumbnails of the clip plus an intensity slider. */
@Composable
fun FiltersPanel(clip: UiClip?, grade: ColorGrade, onPreview: (ColorGrade?) -> Unit, onCommit: (ColorGrade) -> Unit, onApplyAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filtros", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            if (clip != null && !Grades.isNeutral(grade)) Choice("Aplicar a todos", false, onClick = onApplyAll)
        }
        if (clip == null) { LookHint(); return@Column }
        var g by remember(clip.id, grade) { mutableStateOf(grade) }
        val haptics = rememberHaptics()
        val ctx = LocalContext.current
        val lib = remember { Grades.library(ctx.applicationContext) }
        val thumbs = LocalThumbnails.current
        // No thumbnail source (screenshots, previews): grade a built-in swatch synchronously.
        val still = thumbs == null
        val base by produceState<Bitmap?>(if (still) swatch() else null, clip.source, clip.inS) {
            if (still) return@produceState
            value = withContext(Dispatchers.IO) {
                clip.source?.let { s -> thumbs?.invoke(s, (clip.inS * 1000).toLong() + 200)?.asAndroidBitmap() }
                    ?.let { Bitmap.createScaledBitmap(it, 96, (96f * it.height / it.width).toInt().coerceAtLeast(1), true) } ?: swatch()
            }
        }
        Spacer(Modifier.height(Space.sm))
        LazyRow(Modifier.testTag("filter-strip"), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val all = listOf("" to "Nenhum") + LookPresets.all
            items(all, key = { it.first }) { (id, label) ->
                FilterThumb(label, base, id, lib, sync = still, selected = g.filter == id) {
                    haptics.tick()
                    g = g.copy(filter = id, intensity = if (id.isEmpty() || g.intensity > 0f) g.intensity else 1f)
                    onCommit(g)
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
        val enabled = g.filter.isNotEmpty()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }) {
            Text("Intensidade", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary, modifier = Modifier.width(88.dp))
            Slider(g.intensity, { g = g.copy(intensity = it); onPreview(g) }, Modifier.weight(1f).semantics { contentDescription = "Intensidade do filtro ${PtBr.pct(g.intensity)}" }, enabled = enabled,
                onValueChangeFinished = { onCommit(g) }, colors = sliderColors())
            Text(PtBr.pct(g.intensity), style = MaterialTheme.typography.labelSmall, color = ClioColors.TextTertiary, modifier = Modifier.width(44.dp))
        }
    }
}

@Composable
private fun FilterThumb(label: String, base: Bitmap?, id: String, lib: Grades.Library, sync: Boolean, selected: Boolean, onClick: () -> Unit) {
    fun render(b: Bitmap) = runCatching { if (id.isEmpty()) b.asImageBitmap() else Grades.applyTo(b, lib.plane(Grades.NEUTRAL.copy(filter = id))).asImageBitmap() }.getOrNull()
    // Screenshots/previews render synchronously; on device the grading runs off the main thread.
    val still = sync
    val img by produceState(if (still) base?.let(::render) else null, base, id) {
        if (!still) value = base?.let { b -> withContext(Dispatchers.Default) { render(b) } }
    }
    val lift by animateFloatAsState(if (selected) 1f else 0f, ClioMotion.snappy(), label = "f")
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).semantics { contentDescription = "Filtro $label"; this.selected = selected; role = androidx.compose.ui.semantics.Role.RadioButton }.pressable(onClick = onClick)) {
        Box(Modifier.size(64.dp, 84.dp).graphicsLayer { scaleX = 1f + 0.06f * lift; scaleY = 1f + 0.06f * lift }
            .clip(ClioShapes.sm).background(ClioColors.SurfaceHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) ClioColors.Clay else ClioColors.Hairline, ClioShapes.sm)) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) ClioColors.Clay else ClioColors.TextSecondary, maxLines = 1)
    }
}

/** Ajustes: brightness, contrast, saturation, temperature, highlights and shadows (−100%..+100%). */
@Composable
fun AdjustPanel(clip: UiClip?, grade: ColorGrade, onPreview: (ColorGrade?) -> Unit, onCommit: (ColorGrade) -> Unit, onApplyAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajustes", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            if (clip != null) {
                Choice("Redefinir", false) { onCommit(Grades.NEUTRAL.copy(filter = grade.filter, intensity = grade.intensity)) }
                if (!Grades.isNeutral(grade)) { Spacer(Modifier.width(Space.sm)); Choice("Aplicar a todos", false, onClick = onApplyAll) }
            }
        }
        if (clip == null) { LookHint(); return@Column }
        var g by remember(clip.id, grade) { mutableStateOf(grade) }
        val haptics = rememberHaptics()
        val done = { onCommit(g) }
        @Composable fun row(label: String, v: Float, set: (ColorGrade, Float) -> ColorGrade) =
            AdjustSlider(label, v, { x ->
                val s = if (abs(x) < 0.04f) 0f else x
                if (s == 0f && v != 0f) haptics.tick()
                g = set(g, s); onPreview(g)
            }, done)
        Spacer(Modifier.height(Space.xs))
        row("Brilho", g.brightness) { a, x -> a.copy(brightness = x) }
        row("Contraste", g.contrast) { a, x -> a.copy(contrast = x) }
        row("Saturação", g.saturation) { a, x -> a.copy(saturation = x) }
        row("Temperatura", g.temperature) { a, x -> a.copy(temperature = x) }
        row("Realces", g.highlights) { a, x -> a.copy(highlights = x) }
        row("Sombras", g.shadows) { a, x -> a.copy(shadows = x) }
    }
}

@Composable
private fun AdjustSlider(label: String, v: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary, modifier = Modifier.width(88.dp))
        Slider(v, onChange, Modifier.weight(1f).semantics { contentDescription = "$label ${signedPct(v)}" }, valueRange = -1f..1f,
            onValueChangeFinished = onDone, colors = sliderColors())
        Text(signedPct(v), style = MaterialTheme.typography.labelSmall, color = if (v == 0f) ClioColors.TextTertiary else ClioColors.Clay, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = ClioColors.Clay, activeTrackColor = ClioColors.Clay, inactiveTrackColor = ClioColors.SurfaceHigh)

@Composable
private fun LookHint() {
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Text("Selecione um clipe de vídeo na linha do tempo.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
    }
}
