package dev.clio.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.clio.app.engine.Engine
import dev.clio.app.ui.components.*
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space

sealed interface CaptionStatus {
    data object Idle : CaptionStatus
    data class Downloading(val progress: Float, val sizeMb: Int) : CaptionStatus
    data class Decoding(val progress: Float) : CaptionStatus
    data class Transcribing(val progress: Float) : CaptionStatus
    data class Failed(val message: String) : CaptionStatus
}

enum class CaptionStyle(val label: String) { Clean("Clean"), Bold("Bold"), Box("Caixa"), Karaoke("Karaokê") }

/** How a caption looks on the preview, per style. */
@Composable
fun CaptionOverlay(text: String, style: CaptionStyle, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.titleLarge
    when (style) {
        CaptionStyle.Clean -> Text(text, modifier, style = base, color = ClioColors.OnMedia)
        CaptionStyle.Bold -> Text(text.uppercase(), modifier, style = base.copy(fontWeight = FontWeight.Black, fontSize = 20.sp), color = ClioColors.OnMedia)
        CaptionStyle.Box -> Text(text, modifier.clip(ClioShapes.sm).background(ClioColors.MediaScrim).padding(horizontal = 10.dp, vertical = 4.dp), style = base, color = ClioColors.OnMedia)
        CaptionStyle.Karaoke -> Text(text, modifier, style = base.copy(fontWeight = FontWeight.Bold), color = ClioColors.CaptionHighlight)
    }
}

@Composable
fun CaptionsPanel(
    caps: List<Engine.Cap>,
    style: CaptionStyle,
    status: CaptionStatus,
    onStyle: (CaptionStyle) -> Unit,
    onGenerate: () -> Unit,
    onCaps: (List<Engine.Cap>) -> Unit,
) {
    val generating = status is CaptionStatus.Downloading || status is CaptionStatus.Decoding || status is CaptionStatus.Transcribing
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Legendas", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            Row(
                Modifier.pressable { if (!generating) onGenerate() }.clip(ClioShapes.pill)
                    .background(ClioColors.ClaySoft).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val spin by rememberInfiniteTransition(label = "g").animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "r")
                Icon(Icons.Outlined.AutoAwesome, null, tint = ClioColors.ClayText,
                    modifier = Modifier.size(14.dp).then(if (generating) Modifier.graphicsLayer { rotationZ = spin } else Modifier))
                Spacer(Modifier.width(6.dp))
                Text(if (generating) "Gerando…" else if (status is CaptionStatus.Failed) "Tentar de novo" else if (caps.isEmpty()) "Gerar automático" else "Gerar de novo",
                    style = MaterialTheme.typography.labelMedium, color = ClioColors.ClayText)
            }
        }
        Spacer(Modifier.height(Space.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(CaptionStyle.entries) { _, s ->
                val sel = s == style
                Box(Modifier.pressable { onStyle(s) }.clip(ClioShapes.pill)
                    .background(if (sel) ClioColors.Primary else ClioColors.SurfaceRaised)
                    .border(1.dp, ClioColors.Hairline, ClioShapes.pill).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(s.label, style = MaterialTheme.typography.labelMedium, color = if (sel) ClioColors.OnPrimary else ClioColors.TextSecondary)
                }
            }
        }
        AnimatedVisibility(generating || status is CaptionStatus.Failed, enter = fadeIn() + expandVertically(ClioMotion.gentle()), exit = fadeOut() + shrinkVertically()) {
            val (label, p) = when (status) {
                is CaptionStatus.Downloading -> "Baixando modelo de fala (${status.sizeMb} MB, só na primeira vez)" to status.progress
                is CaptionStatus.Decoding -> "Lendo o áudio…" to status.progress
                is CaptionStatus.Transcribing -> "Transcrevendo no aparelho…" to status.progress
                is CaptionStatus.Failed -> "Não deu para gerar: ${status.message}" to 0f
                else -> "" to 0f
            }
            val shown by animateFloatAsState(p, ClioMotion.gentle(), label = "cp")
            Column(Modifier.padding(top = Space.sm)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (status is CaptionStatus.Failed) ClioColors.ClayText else ClioColors.TextTertiary)
                if (status !is CaptionStatus.Failed) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(ClioShapes.pill).background(ClioColors.SurfaceHigh)) {
                        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(ClioShapes.pill).background(ClioColors.Primary))
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
        if (caps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Legendas automáticas da fala, geradas no aparelho.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(caps, key = { i, _ -> i }) { i, c ->
                Row(
                    Modifier.fillMaxWidth().animateItem(fadeInSpec = ClioMotion.fade(), placementSpec = ClioMotion.gentle())
                        .clip(ClioShapes.sm).background(ClioColors.Surface).border(1.dp, ClioColors.Hairline, ClioShapes.sm)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%d:%02d".format(c.startMs / 60000, (c.startMs / 1000) % 60), style = MaterialTheme.typography.labelSmall,
                        color = ClioColors.TextTertiary, modifier = Modifier.width(40.dp))
                    var value by remember(i, c.startMs) { mutableStateOf(c.text) }
                    BasicTextField(
                        value, { t -> value = t; onCaps(caps.toMutableList().also { it[i] = c.copy(text = t) }) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ClioColors.TextPrimary),
                        cursorBrush = SolidColor(ClioColors.Clay), modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

