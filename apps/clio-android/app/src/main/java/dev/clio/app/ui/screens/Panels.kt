package dev.clio.app.ui.screens

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

/** Text overlays: add at the playhead (3 s), edit inline. */
@Composable
fun TextPanel(texts: List<TextItem>, onAdd: () -> Unit, onEdit: (Long, String) -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        PanelHeader("Texto", "Adicionar texto", onAdd)
        Spacer(Modifier.height(Space.sm))
        if (texts.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("O texto entra no ponto atual da timeline.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(texts, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().animateItem().clip(ClioShapes.sm).background(ClioColors.Surface)
                    .border(1.dp, ClioColors.Hairline, ClioShapes.sm).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%d:%02d".format(t.startMs / 60000, (t.startMs / 1000) % 60), style = MaterialTheme.typography.labelSmall,
                        color = ClioColors.TextTertiary, modifier = Modifier.width(40.dp))
                    // Local state keeps typing instant; the engine gets every change asynchronously.
                    var value by androidx.compose.runtime.remember(t.id) { androidx.compose.runtime.mutableStateOf(t.text) }
                    BasicTextField(value, { value = it; onEdit(t.id, it) }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ClioColors.TextPrimary),
                        cursorBrush = SolidColor(ClioColors.Clay), modifier = Modifier.weight(1f))
                }
            }
        }
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
