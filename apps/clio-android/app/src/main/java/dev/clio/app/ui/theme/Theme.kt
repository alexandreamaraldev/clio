package dev.clio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

enum class ThemeMode { System, Light, Dark }

object ClioShapes {
    val sm = RoundedCornerShape(10.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(50)
}

object Space {
    val xs = 4.dp; val sm = 8.dp; val md = 16.dp; val lg = 24.dp; val xl = 32.dp; val xxl = 48.dp
}

@Composable
fun ClioTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.Primary, onPrimary = p.OnPrimary, secondary = p.Clay,
        background = p.Ink, onBackground = p.TextPrimary, surface = p.Surface, onSurface = p.TextPrimary,
        surfaceVariant = p.SurfaceRaised, onSurfaceVariant = p.TextSecondary,
        outline = p.HairlineStrong, outlineVariant = p.Hairline,
    )
    CompositionLocalProvider(LocalClioPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ClioTypography,
            shapes = Shapes(small = ClioShapes.sm, medium = ClioShapes.md, large = ClioShapes.lg),
            content = content,
        )
    }
}
