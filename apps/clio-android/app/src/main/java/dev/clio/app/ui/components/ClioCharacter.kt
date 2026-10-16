package dev.clio.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.clio.app.ui.theme.ClioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * "Clio" — a deliberately subtle, secondary character (two pill eyes). Used ONLY in onboarding,
 * the empty Projects state and export progress; never in the editor. To remove it entirely,
 * set [ClioCharacterConfig.enabled] = false (or delete this file and its 3 call sites).
 */
object ClioCharacterConfig { var enabled = true }

enum class Mood { Idle, Curious, Happy, Working, Sleepy }

/** Shared "where to look" target, normalised to -1..1 on each axis. */
@Stable
class LookTarget {
    var value by mutableStateOf(Offset.Zero)
    var active by mutableStateOf(false)
}

@Composable
fun rememberLookTarget() = remember { LookTarget() }

/** Feeds touches anywhere inside this node to the mascot's gaze (without consuming them). */
fun Modifier.lookSource(target: LookTarget): Modifier = pointerInput(target) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            val p = e.changes.firstOrNull() ?: continue
            target.active = p.pressed
            target.value = Offset(
                (p.position.x / size.width * 2f - 1f).coerceIn(-1f, 1f),
                (p.position.y / size.height * 2f - 1f).coerceIn(-1f, 1f),
            )
        }
    }
}

@Composable
fun ClioCharacter(
    modifier: Modifier = Modifier,
    mood: Mood = Mood.Idle,
    size: Dp = 72.dp,
    look: LookTarget? = null,
    showFace: Boolean = true,
) {
    if (!ClioCharacterConfig.enabled) return
    val blink = remember { Animatable(1f) }
    val gazeX = remember { Animatable(0f) }
    val gazeY = remember { Animatable(0f) }
    val bob = rememberInfiniteTransition(label = "bob")
    val breathe by bob.animateFloat(-1f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val scan by bob.animateFloat(-1f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    val happy by animateFloatAsState(if (mood == Mood.Happy) 1f else 0f, spring(0.6f, Spring.StiffnessLow), label = "h")
    val sleepy by animateFloatAsState(if (mood == Mood.Sleepy) 1f else 0f, tween(600), label = "z")

    // Blink loop — irregular, like a living thing; double-blink sometimes.
    LaunchedEffect(mood) {
        while (true) {
            delay(Random.nextLong(2200, 5200))
            repeat(if (Random.nextFloat() < 0.2f) 2 else 1) {
                blink.animateTo(0.08f, tween(70))
                blink.animateTo(1f, tween(130))
                delay(90)
            }
        }
    }
    // Gaze: follow touch when present, otherwise wander occasionally.
    LaunchedEffect(look?.value, look?.active, mood) {
        val spec = spring<Float>(0.75f, Spring.StiffnessLow)
        if (look != null && look.active) {
            launch { gazeX.animateTo(look.value.x, spec) }; gazeY.animateTo(look.value.y, spec)
        } else if (mood == Mood.Idle || mood == Mood.Curious) {
            while (true) {
                delay(Random.nextLong(1400, 3600))
                val x = if (Random.nextBoolean()) Random.nextFloat() * 2f - 1f else 0f
                launch { gazeX.animateTo(x, spec) }; gazeY.animateTo(Random.nextFloat() * 0.6f - 0.3f, spec)
            }
        } else { launch { gazeX.animateTo(0f, spec) }; gazeY.animateTo(0f, spec) }
    }

    val face = ClioColors.SurfaceRaised; val hair = ClioColors.HairlineStrong; val eye = ClioColors.TextPrimary
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val lift = breathe * w * 0.015f
        if (showFace) {
            drawRoundRect(face, cornerRadius = CornerRadius(w * 0.32f), topLeft = Offset(0f, lift), size = Size(w, w))
            drawRoundRect(hair, cornerRadius = CornerRadius(w * 0.32f), topLeft = Offset(0f, lift), size = Size(w, w), style = Stroke(1.dp.toPx()))
        }
        val ew = w * 0.12f
        val eh = w * (if (mood == Mood.Curious) 0.30f else 0.26f) * (1f - 0.75f * sleepy)
        val gx = (if (mood == Mood.Working) scan else gazeX.value) * w * 0.10f
        val gy = gazeY.value * w * 0.06f + lift
        for (side in listOf(-1, 1)) {
            val cx = w / 2f + side * w * 0.15f + gx
            val cy = w / 2f + gy
            if (happy > 0.5f) {
                // ^ ^ eyes
                drawArc(eye, 200f, 140f, false, Offset(cx - ew, cy - ew * 0.6f), Size(ew * 2f, ew * 1.6f),
                    style = Stroke(ew * 0.7f, cap = StrokeCap.Round))
            } else {
                val h = (eh * blink.value).coerceAtLeast(ew * 0.35f)
                drawRoundRect(eye, Offset(cx - ew / 2f, cy - h / 2f), Size(ew, h), CornerRadius(ew / 2f))
            }
        }
    }
}
