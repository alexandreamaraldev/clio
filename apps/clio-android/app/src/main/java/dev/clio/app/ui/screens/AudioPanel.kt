package dev.clio.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.clio.app.engine.SfxLibrary
import dev.clio.app.ui.components.pressable
import dev.clio.app.ui.components.rememberHaptics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.delay
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space

/**
 * Áudio: music from the device, voiceover recording at the playhead, detach a video clip's audio,
 * and the bundled (CC0) sound effects, which drop in at the playhead.
 */
@Composable
fun AudioPanel(
    music: String?, recording: Boolean, notice: String?, canDetach: Boolean,
    onPickMusic: () -> Unit, onRemoveMusic: () -> Unit, onRecord: () -> Unit, onDetach: () -> Unit, onSfx: (String) -> Unit,
) {
    var credits by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(horizontal = Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Áudio", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            androidx.compose.animation.AnimatedVisibility(notice != null, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(ClioMotion.snappy(), 0.9f),
                exit = androidx.compose.animation.fadeOut()) {
                Text(notice.orEmpty(), style = MaterialTheme.typography.labelMedium, color = ClioColors.OnClay,
                    modifier = Modifier.testTag("audio-notice").clip(ClioShapes.pill).background(ClioColors.Clay).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            if (recording) { Spacer(Modifier.width(Space.sm)); RecordingTimer() }
        }
        Spacer(Modifier.height(Space.sm))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            AudioAction(Icons.Outlined.LibraryMusic, if (music == null) "Música" else "Trocar música", tag = "audio-music", onClick = onPickMusic)
            AudioAction(if (recording) Icons.Rounded.Stop else Icons.Outlined.Mic, if (recording) "Parar" else "Narração", active = recording, tag = "audio-record", onClick = onRecord)
            AudioAction(Icons.Outlined.CallSplit, "Separar áudio", enabled = canDetach, tag = "audio-detach", onClick = onDetach)
        }
        if (music != null) {
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(music, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Close, "Remover música", tint = ClioColors.TextTertiary, modifier = Modifier.size(28.dp).pressable(onClick = onRemoveMusic).padding(6.dp))
            }
        }
        Spacer(Modifier.height(Space.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Efeitos sonoros", style = MaterialTheme.typography.labelLarge, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("Créditos", style = MaterialTheme.typography.labelMedium, color = ClioColors.ClayText,
                modifier = Modifier.pressable(onClick = { credits = true }).padding(4.dp).testTag("sfx-credits"))
        }
        Spacer(Modifier.height(Space.xs))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.testTag("sfx")) {
            items(SfxLibrary.all) { s -> Choice(s.label, selected = false) { haptics.click(); onSfx(s.id) } }
        }
    }
    if (credits) AlertDialog(
        onDismissRequest = { credits = false },
        confirmButton = { TextButton({ credits = false }) { Text("Fechar", color = ClioColors.ClayText) } },
        title = { Text("Créditos de áudio") },
        text = {
            Column {
                SfxLibrary.credits.forEach {
                    Text("${it.title} — ${it.author}", style = MaterialTheme.typography.bodyMedium)
                    Text("${it.license} · ${it.url}", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
                    Spacer(Modifier.height(Space.sm))
                }
            }
        },
    )
}

@Composable
private fun AudioAction(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, tag: String, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) ClioColors.Clay else ClioColors.Surface, ClioMotion.fade(180), label = "aa")
    val fg = when { active -> ClioColors.OnClay; enabled -> ClioColors.TextPrimary; else -> ClioColors.TextTertiary.copy(alpha = 0.5f) }
    // Recording pulses softly so it is obvious the mic is live.
    val pulse = if (active) rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.55f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p").value else 1f
    Row(Modifier.pressable(enabled = enabled, onClick = onClick).testTag(tag).clip(ClioShapes.pill).background(bg)
        .border(1.dp, if (active) bg else ClioColors.Hairline, ClioShapes.pill).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp).graphicsLayer { alpha = pulse })
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Red dot + elapsed time while the mic is live. */
@Composable
private fun RecordingTimer() {
    val start = remember { android.os.SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(start) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(250) } }
    val s = (now - start) / 1000
    Row(Modifier.testTag("rec-timer"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(RecordRed))
        Spacer(Modifier.width(6.dp))
        Text("%d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelLarge, color = ClioColors.TextPrimary)
    }
}

private val RecordRed = Color(0xFFE5484D)

/** Explains the mic before asking; after a permanent denial, points to the system Settings. */
@Composable
fun MicPermissionDialog(permanent: Boolean, onAllow: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
        TextButton(if (permanent) onSettings else onAllow) { Text(if (permanent) "Abrir configurações" else "Permitir", color = ClioColors.ClayText) }
    },
    dismissButton = { TextButton(onDismiss) { Text("Agora não", color = ClioColors.TextSecondary) } },
    title = { Text("Microfone para narração") },
    text = {
        Text(if (permanent) "O acesso ao microfone está desativado. Ative em Configurações > Permissões para gravar narrações."
        else "O Clio usa o microfone só enquanto você grava uma narração. Nada sai do seu aparelho.")
    },
)

/** Waveform for an audio clip: real peaks fade in once decoded; [placeholder] until then. */
@Composable
fun ClipWaveform(c: UiClip, modifier: Modifier, placeholder: @Composable (Modifier) -> Unit) {
    val key = remember(c.id, c.source, c.inS, c.lenS, c.speed) {
        dev.clio.app.engine.Waveforms.key(c.id, c.source, Math.round(c.inS * 1000.0), dev.clio.app.engine.Mix.spanMs(Math.round(c.lenS * 1000.0), c.speed))
    }
    val peaks = remember(key) { dev.clio.app.engine.Waveforms.state(key) }.value
    val a by animateFloatAsState(if (peaks != null) 1f else 0f, ClioMotion.fade(), label = "wave")
    if (peaks == null || a < 1f) placeholder(modifier.graphicsLayer { alpha = 1f - a })
    if (peaks != null) PeaksWaveform(peaks, modifier.graphicsLayer { alpha = a })
}

/** Real waveform bars from engine peaks (0..1), stretched to the clip width. */
@Composable
fun PeaksWaveform(peaks: FloatArray, modifier: Modifier) {
    val c = ClioColors.TextTertiary.copy(alpha = 0.6f)
    Canvas(modifier) {
        if (peaks.isEmpty()) return@Canvas
        val step = 3.dp.toPx()
        val bar = 1.5.dp.toPx()
        val n = (size.width / step).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val a = peaks[(i.toLong() * peaks.size / n).toInt().coerceIn(0, peaks.size - 1)].coerceIn(0.04f, 1f)
            val h = size.height * a
            val x = i * step + step / 2
            drawLine(c, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), bar)
        }
    }
}
