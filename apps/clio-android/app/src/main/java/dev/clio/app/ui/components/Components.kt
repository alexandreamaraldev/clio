package dev.clio.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes

/** Haptics with the right semantics per interaction (View constants, not text-handle feedback). */
class Haptics(private val view: android.view.View) {
    fun click() = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = view.performHapticFeedback(
        if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
}

@Composable
fun rememberHaptics(): Haptics {
    val v = LocalView.current
    return remember(v) { Haptics(v) }
}

/**
 * Press feedback: a quiet 4% scale-down on a spring + a light haptic, with Button semantics.
 * Apply it before clip/background so the touch target and scale cover the whole shape.
 */
@Composable
fun Modifier.pressable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptics = rememberHaptics()
    val s by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, ClioMotion.snappy(), label = "press")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, enabled = enabled, role = role) {
            haptics.click()
            onClick()
        }
}

/** Translucent "glass" surface with a hairline border. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = ClioShapes.md,
    color: Color = ClioColors.Glass,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(color)
            .border(1.dp, ClioColors.Hairline, shape),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier
            .pressable(onClick = onClick)
            .clip(ClioShapes.pill)
            .background(ClioColors.Primary)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ClioColors.OnPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = ClioColors.OnPrimary)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .pressable(onClick = onClick)
            .clip(ClioShapes.pill)
            .border(1.dp, ClioColors.HairlineStrong, ClioShapes.pill)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = ClioColors.TextPrimary) }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, accent: Boolean = false) {
    Row(
        modifier
            .clip(ClioShapes.pill)
            .background(if (accent) ClioColors.ClaySoft else ClioColors.SurfaceRaised)
            .border(1.dp, ClioColors.Hairline, ClioShapes.pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (accent) ClioColors.ClayText else ClioColors.TextSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (accent) ClioColors.ClayText else ClioColors.TextSecondary)
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
    }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Column(
        Modifier.pressable(enabled = enabled, onClick = onClick).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(ClioShapes.sm)
                .background(if (selected) ClioColors.SurfaceHigh else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (selected) ClioColors.TextPrimary else ClioColors.TextSecondary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) ClioColors.TextPrimary else ClioColors.TextTertiary)
    }
}
