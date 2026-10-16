package dev.clio.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.clio.app.ui.components.*
import dev.clio.app.ui.components.PrimaryButton
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private data class Page(val eyebrow: String, val title: String, val body: String)

private val pages = listOf(
    Page("Clio", "Edite como\num profissional.", "Um motor de vídeo completo, no seu bolso. Rápido, preciso e silencioso."),
    Page("Timeline", "Cada quadro,\nsob controle.", "Corte, divida e organize em várias faixas com gestos naturais."),
    Page("Comece", "Sua história,\ntodo dia.", "Modelos prontos, rascunhos salvos automaticamente e uma sequência para manter o ritmo."),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val breathe = rememberInfiniteTransition(label = "orb")
    val t by breathe.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "t")

    val look = rememberLookTarget()
    val mood = when (pager.currentPage) { 0 -> Mood.Idle; 1 -> Mood.Curious; else -> Mood.Happy }
    Box(Modifier.fillMaxSize().background(ClioColors.Ink).lookSource(look)) {
        val clay = ClioColors.Clay; val hair = ClioColors.Hairline
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Space.lg)) {
            // Soft ambient orb + Clio, centred in the space above the copy (no dead gap).
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width * (0.5f + 0.08f * (t - 0.5f)), size.height * 0.5f)
                    val r = minOf(size.width, size.height) * 0.62f
                    drawCircle(Brush.radialGradient(listOf(clay.copy(alpha = 0.22f), Color.Transparent), c, r), r, c)
                    for (i in 1..3) drawCircle(hair, r * (0.3f + i * 0.15f + 0.015f * t), c, style = Stroke(1f))
                }
                ClioCharacter(mood = mood, size = 64.dp, look = look)
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().height(220.dp)) { i ->
                val off = ((pager.currentPage - i) + pager.currentPageOffsetFraction).absoluteValue
                Column(Modifier.graphicsLayer { alpha = 1f - off.coerceIn(0f, 1f); translationX = off * 60f }) {
                    Text(pages[i].eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = ClioColors.ClayText)
                    Spacer(Modifier.height(Space.md))
                    Text(pages[i].title, style = MaterialTheme.typography.displayMedium, color = ClioColors.TextPrimary)
                    Spacer(Modifier.height(Space.md))
                    Text(pages[i].body, style = MaterialTheme.typography.bodyLarge, color = ClioColors.TextSecondary)
                }
            }
            Row(Modifier.padding(vertical = Space.lg), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size) { i ->
                    val w by animateDpAsState(if (pager.currentPage == i) 22.dp else 6.dp, ClioMotion.gentle(), label = "dot")
                    Box(Modifier.height(6.dp).width(w).clip(CircleShape)
                        .background(if (pager.currentPage == i) ClioColors.TextPrimary else ClioColors.HairlineStrong))
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                val last = pager.currentPage == pages.lastIndex
                AnimatedVisibility(!last, Modifier.weight(1f), enter = fadeIn(ClioMotion.fade()) + expandHorizontally(ClioMotion.gentle()),
                    exit = fadeOut(ClioMotion.fade(120)) + shrinkHorizontally(ClioMotion.gentle())) {
                    GhostButton("Pular", Modifier.fillMaxWidth(), onClick = onDone)
                }
                PrimaryButton(if (last) "Começar" else "Continuar", Modifier.weight(1.4f)) {
                    if (pager.currentPage == pages.lastIndex) onDone()
                    else scope.launch { pager.animateScrollToPage(pager.currentPage + 1, animationSpec = ClioMotion.gentle()) }
                }
            }
        }
    }
}
