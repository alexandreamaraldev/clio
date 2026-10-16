package dev.clio.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.clio.app.engine.Fonts
import dev.clio.app.ui.theme.ClioMotion
import uniffi.clio_ffi.TextAnim
import uniffi.clio_ffi.TextFont
import uniffi.clio_ffi.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.clio.app.ui.components.pressable
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space

@Composable
private fun PanelHeader(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
        Row(Modifier.pressable(onClick = onAction).clip(ClioShapes.pill).background(ClioColors.SurfaceRaised)
            .border(1.dp, ClioColors.Hairline, ClioShapes.pill).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Add, null, tint = ClioColors.TextPrimary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(action, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextPrimary)
        }
    }
}

/** Text overlays: add at the playhead (3 s), edit inline; tap a row to style it and nudge its timing. */
@Composable
fun TextPanel(
    texts: List<TextItem>,
    onAdd: () -> Unit,
    onEdit: (Long, String) -> Unit,
    onStyle: (Long, TextStyle) -> Unit = { _, _ -> },
    onRetime: (Long, Long, Long) -> Unit = { _, _, _ -> },
) {
    var open by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        PanelHeader("Texto", "Adicionar texto", onAdd)
        Spacer(Modifier.height(Space.sm))
        if (texts.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("O texto entra no ponto atual da timeline.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(texts, key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().animateItem().animateContentSize(ClioMotion.gentle()).clip(ClioShapes.sm).background(ClioColors.Surface)
                    .border(1.dp, if (open == t.id) ClioColors.Clay else ClioColors.Hairline, ClioShapes.sm)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%d:%02d".format(t.startMs / 60000, (t.startMs / 1000) % 60), style = MaterialTheme.typography.labelSmall,
                            color = ClioColors.TextTertiary, modifier = Modifier.width(40.dp))
                        // Local state keeps typing instant; the engine gets every change asynchronously.
                        var value by remember(t.id) { mutableStateOf(t.text) }
                        BasicTextField(value, { value = it; onEdit(t.id, it) }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ClioColors.TextPrimary),
                            cursorBrush = SolidColor(ClioColors.Clay), modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.Palette, "Estilo", tint = if (open == t.id) ClioColors.Clay else ClioColors.TextSecondary,
                            modifier = Modifier.size(32.dp).pressable { open = if (open == t.id) null else t.id }.padding(6.dp))
                    }
                    if (open == t.id) TextStyleEditor(t, { onStyle(t.id, it) }, { s, e -> onRetime(t.id, s, e) })
                }
            }
        }
    }
}

private val swatches = listOf(0xFFFFFFFFu, 0xFF121212u, 0xFFF2683Cu, 0xFFFFD27Au, 0xFF5EC8E5u)

@Composable
private fun TextStyleEditor(t: TextItem, onStyle: (TextStyle) -> Unit, onRetime: (Long, Long) -> Unit) {
    val st = t.style
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow(TextFont.entries, st.font, { Fonts.label(it) }) { onStyle(st.copy(font = it)) }
        // Bold is a real weight only on the variable fonts; static ones (Bebas, Pacifico) hide it.
        if (Fonts.variable(st.font)) Choice("Negrito", st.bold) { onStyle(st.copy(bold = !st.bold)) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            items(swatches) { c ->
                Box(Modifier.size(24.dp).pressable { onStyle(st.copy(color = c)) }.clip(ClioShapes.pill).background(Color(c.toInt()))
                    .border(if (st.color == c) 2.dp else 1.dp, if (st.color == c) ClioColors.Clay else ClioColors.Hairline, ClioShapes.pill))
            }
            item { Choice("Contorno", st.strokeWidth > 0f) { onStyle(if (st.strokeWidth > 0f) st.copy(strokeWidth = 0f) else st.copy(strokeWidth = 0.14f, strokeColor = if (st.color == 0xFF121212u) 0xFFFFFFFFu else 0xFF000000u)) } }
            item { Choice("Fundo", st.background != 0u) { onStyle(st.copy(background = if (st.background != 0u) 0u else 0x99000000u)) } }
            item { Choice("Sombra", st.shadow) { onStyle(st.copy(shadow = !st.shadow)) } }
        }
        ChipRow(uniffi.clio_ffi.TextAlign.entries, st.align, { when (it) { uniffi.clio_ffi.TextAlign.START -> "Esquerda"; uniffi.clio_ffi.TextAlign.CENTER -> "Centro"; else -> "Direita" } }) { onStyle(st.copy(align = it)) }
        ChipRow(TextAnim.entries, st.animIn, { "Entrada: " + animLabel(it) }) { onStyle(st.copy(animIn = it)) }
        ChipRow(TextAnim.entries, st.animOut, { "Saída: " + animLabel(it) }) { onStyle(st.copy(animOut = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Nudge("Início", t.startMs, { onRetime((t.startMs + it).coerceIn(0, t.endMs - 100), t.endMs) })
            Spacer(Modifier.width(Space.md))
            Nudge("Fim", t.endMs, { onRetime(t.startMs, (t.endMs + it).coerceAtLeast(t.startMs + 100)) })
        }
    }
}

private fun animLabel(a: TextAnim) = when (a) { TextAnim.NONE -> "Nenhuma"; TextAnim.FADE -> "Suave"; TextAnim.POP -> "Pop"; TextAnim.TYPEWRITER -> "Digitação" }

@Composable
private fun <T> ChipRow(values: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(values) { v -> Choice(label(v), v == selected) { onPick(v) } } }
}

/** Timing handle: ±0,1 s steps. */
@Composable
private fun Nudge(label: String, ms: Long, onDelta: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
        Spacer(Modifier.width(6.dp))
        Choice("−", false) { onDelta(-100) }
        Text("%.1f s".format(java.util.Locale("pt", "BR"), ms / 1000f), style = MaterialTheme.typography.labelMedium, color = ClioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 6.dp))
        Choice("+", false) { onDelta(100) }
    }
}

/** Background music: one track from the device, trimmed to the video on export. */
@Composable
fun MusicPanel(current: String?, onPick: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        PanelHeader("Música", if (current == null) "Escolher música" else "Trocar", onPick)
        Spacer(Modifier.height(Space.md))
        if (current == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Escolha um áudio do aparelho para tocar junto.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
        } else Row(Modifier.fillMaxWidth().clip(ClioShapes.md).background(ClioColors.Surface).border(1.dp, ClioColors.Hairline, ClioShapes.md)
            .padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(ClioShapes.sm).background(ClioColors.TrackAudio), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LibraryMusic, null, tint = ClioColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(current, style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, maxLines = 1)
                Text("Toca desde o início", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
            }
            Icon(Icons.Outlined.Close, "Remover", tint = ClioColors.TextSecondary, modifier = Modifier.size(36.dp).pressable(onClick = onRemove).padding(8.dp))
        }
    }
}
