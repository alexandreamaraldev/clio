package dev.clio.app.ui.screens

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.clio.app.ui.components.*
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space

@Composable
fun HomeScreen(
    streak: Int,
    drafts: List<Draft> = Samples.drafts,
    themeMode: dev.clio.app.ui.theme.ThemeMode,
    onCycleTheme: () -> Unit,
    onNewProject: () -> Unit,
    onOpenDraft: (Draft) -> Unit,
    onQuickCaptions: () -> Unit = {},
) {
    LazyColumn(
        Modifier.fillMaxSize().background(ClioColors.Ink),
        contentPadding = WindowInsets.systemBars.asPaddingValues().let {
            PaddingValues(top = it.calculateTopPadding() + Space.lg, bottom = it.calculateBottomPadding() + Space.xxl)
        },
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        item {
            Row(Modifier.padding(horizontal = Space.lg), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
                    Spacer(Modifier.height(4.dp))
                    Text("Projetos", style = MaterialTheme.typography.headlineLarge, color = ClioColors.TextPrimary)
                }
                Pill(if (streak == 1) "1 dia" else "$streak dias", icon = Icons.Outlined.LocalFireDepartment, accent = streak > 0)
                Spacer(Modifier.width(Space.sm))
                Box(Modifier.size(34.dp).pressable(onClick = onCycleTheme).clip(ClioShapes.pill)
                    .border(1.dp, ClioColors.Hairline, ClioShapes.pill), contentAlignment = Alignment.Center) {
                    Icon(when (themeMode) { dev.clio.app.ui.theme.ThemeMode.System -> Icons.Outlined.BrightnessAuto; dev.clio.app.ui.theme.ThemeMode.Light -> Icons.Outlined.LightMode; dev.clio.app.ui.theme.ThemeMode.Dark -> Icons.Outlined.DarkMode }, "Tema: ${themeMode.name}",
                        tint = ClioColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        item {
            // Hero: create
            Box(
                Modifier.padding(horizontal = Space.lg).fillMaxWidth().height(168.dp)
                    .pressable(onClick = onNewProject).clip(ClioShapes.lg)
                    .background(Brush.linearGradient(listOf(ClioColors.SurfaceRaised, ClioColors.Surface)))
                    .border(1.dp, ClioColors.Hairline, ClioShapes.lg)
                    .padding(Space.lg),
            ) {
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text("Novo projeto", style = MaterialTheme.typography.headlineMedium, color = ClioColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text("Vertical 9:16 · 1080p · 30 fps", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).size(44.dp).clip(ClioShapes.pill).background(ClioColors.Primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, null, tint = ClioColors.OnPrimary) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                QuickAction(Icons.Outlined.ClosedCaption, "Legendas", Modifier.weight(1f), onClick = onQuickCaptions)
                QuickAction(Icons.Outlined.AutoAwesome, "Auto edição", Modifier.weight(1f), soon = true)
                QuickAction(Icons.Outlined.Crop, "Recortar", Modifier.weight(1f), soon = true)
            }
        }
        item {
            SectionHeader("Modelos", "Em breve", Modifier.padding(horizontal = Space.lg))
            Spacer(Modifier.height(Space.md))
            LazyRow(contentPadding = PaddingValues(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                itemsIndexed(Samples.templates) { i, t -> TemplateCard(t, i) }
            }
        }
        item {
            SectionHeader("Rascunhos", "Salvos automaticamente", Modifier.padding(horizontal = Space.lg))
        }
        if (drafts.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                ClioCharacter(mood = Mood.Sleepy, size = 56.dp)
                Spacer(Modifier.height(Space.md))
                Text("Nada por aqui ainda", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary)
                Text("Seus rascunhos aparecem aqui, salvos a cada toque.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
            }
        }
        items(drafts.chunked(2)) { row ->
            Row(Modifier.padding(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                row.forEach { d -> DraftCard(d, Modifier.weight(1f).testTag("draft-${drafts.indexOf(d)}")) { onOpenDraft(d) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, soon: Boolean = false, onClick: () -> Unit = {}) {
    GlassSurface(modifier.height(84.dp).pressable(enabled = !soon, onClick = onClick), color = ClioColors.Surface) {
        Column(Modifier.padding(14.dp).fillMaxSize().graphicsLayer { alpha = if (soon) 0.5f else 1f }, verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = ClioColors.TextSecondary, modifier = Modifier.size(20.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextPrimary)
                if (soon) Text("em breve", style = MaterialTheme.typography.labelSmall, color = ClioColors.TextTertiary)
            }
        }
    }
}

/** Abstract layout sketches instead of fake thumbnails: calm, on-palette, different per template. */
@Composable
private fun TemplateCard(t: Template, index: Int) {
    val fg = ClioColors.TextPrimary; val soft = ClioColors.HairlineStrong; val accent = ClioColors.Clay
    Column(Modifier.width(132.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 14f).clip(ClioShapes.md)
                .background(Brush.verticalGradient(listOf(ClioColors.SurfaceHigh, ClioColors.Surface)))
                .border(1.dp, ClioColors.Hairline, ClioShapes.md),
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(14.dp)) {
                val w = size.width; val h = size.height
                val r = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                fun bar(x: Float, y: Float, bw: Float, bh: Float, c: androidx.compose.ui.graphics.Color) =
                    drawRoundRect(c, androidx.compose.ui.geometry.Offset(x * w, y * h), androidx.compose.ui.geometry.Size(bw * w, bh * h), r)
                when (index % 4) {
                    0 -> { bar(0f, 0f, 1f, 0.62f, soft); bar(0f, 0.7f, 0.8f, 0.06f, fg.copy(alpha = 0.7f)); bar(0f, 0.8f, 0.5f, 0.05f, soft) }
                    1 -> { bar(0f, 0f, 0.48f, 0.45f, soft); bar(0.52f, 0f, 0.48f, 0.45f, soft); bar(0f, 0.5f, 1f, 0.35f, soft); bar(0.3f, 0.9f, 0.4f, 0.05f, accent.copy(alpha = 0.8f)) }
                    2 -> { drawCircle(soft, w * 0.32f, androidx.compose.ui.geometry.Offset(w / 2, h * 0.38f)); bar(0.15f, 0.78f, 0.7f, 0.06f, fg.copy(alpha = 0.7f)) }
                    else -> { for (i in 0..3) bar(0f, i * 0.24f, if (i % 2 == 0) 1f else 0.7f, 0.18f, soft); bar(0f, 0.97f, 0.35f, 0.03f, accent.copy(alpha = 0.8f)) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(t.title, style = MaterialTheme.typography.labelLarge, color = ClioColors.TextPrimary)
        Text(t.uses, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Bom dia"
    in 12..17 -> "Boa tarde"
    else -> "Boa noite"
}

@Composable
private fun DraftCard(d: Draft, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.pressable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(ClioShapes.md)
                .background(Brush.linearGradient(listOf(if (d.tint == Color.Unspecified) ClioColors.SurfaceHigh else d.tint, ClioColors.Surface)))
                .border(1.dp, ClioColors.Hairline, ClioShapes.md),
        ) {
            val thumbs = dev.clio.app.ui.LocalThumbnails.current
            if (thumbs != null && d.thumbSource != null) {
                val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, d.thumbSource) { value = thumbs(d.thumbSource, d.thumbAtMs) }
                androidx.compose.animation.AnimatedVisibility(img != null, enter = androidx.compose.animation.fadeIn(dev.clio.app.ui.theme.ClioMotion.fade(400))) {
                    img?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                }
            }
            Text(d.duration, style = MaterialTheme.typography.labelSmall, color = ClioColors.OnMedia,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    .clip(ClioShapes.pill).background(ClioColors.MediaScrim).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(d.title, style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary)
        Text("Editado ${d.edited}", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
    }
}
