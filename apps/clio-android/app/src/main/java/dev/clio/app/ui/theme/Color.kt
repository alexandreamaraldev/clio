package dev.clio.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Clio palette — restrained, near-monochrome (Apple / Vercel / Anthropic inspired).
 * One warm accent ("Clay", brand #F2683C) used sparingly. Ships in dark and light.
 */
@Immutable
data class ClioPalette(
    val isDark: Boolean,
    val Ink: Color,            // app background
    val Surface: Color,
    val SurfaceRaised: Color,
    val SurfaceHigh: Color,
    val Glass: Color,          // translucent bars
    val Hairline: Color,       // 1dp borders
    val HairlineStrong: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,
    val Primary: Color,        // primary button fill (inverse of background)
    val OnPrimary: Color,
    val Clay: Color,
    val ClaySoft: Color,
    val ClayText: Color,       // accent for text/icons on surfaces (WCAG AA)
    val OnClay: Color,         // text/icons on a Clay fill
    val Scrim: Color,
    // Media & timeline
    val PreviewTop: Color,
    val PreviewBottom: Color,
    val OnMedia: Color,          // text/icons drawn over video
    val MediaScrim: Color,       // pill behind text over video
    val TrackAudio: Color,
    val CaptionHighlight: Color, // karaoke style
)

val DarkPalette = ClioPalette(
    isDark = true,
    Ink = Color(0xFF0A0A0B), Surface = Color(0xFF111113), SurfaceRaised = Color(0xFF18181B),
    SurfaceHigh = Color(0xFF222226), Glass = Color(0xB3141416),
    Hairline = Color(0x14FFFFFF), HairlineStrong = Color(0x26FFFFFF),
    TextPrimary = Color(0xFFEDEDED), TextSecondary = Color(0xFFA1A1AA), TextTertiary = Color(0xFF71717A),
    Primary = Color(0xFFF5F5F4), OnPrimary = Color(0xFF0A0A0B),
    Clay = Color(0xFFF2683C), ClaySoft = Color(0x29F2683C), ClayText = Color(0xFFF2683C), OnClay = Color(0xFF1A0B05), Scrim = Color(0x73000000),
    PreviewTop = Color(0xFF26262A), PreviewBottom = Color(0xFF151517), OnMedia = Color.White, MediaScrim = Color(0x73000000),
    TrackAudio = Color(0x3355A68A), CaptionHighlight = Color(0xFFFFD27A),
)

val LightPalette = ClioPalette(
    isDark = false,
    Ink = Color(0xFFFAFAF9), Surface = Color(0xFFFFFFFF), SurfaceRaised = Color(0xFFF4F4F5),
    SurfaceHigh = Color(0xFFE9E9EB), Glass = Color(0xCCFFFFFF),
    Hairline = Color(0x14000000), HairlineStrong = Color(0x24000000),
    TextPrimary = Color(0xFF0A0A0B), TextSecondary = Color(0xFF52525B), TextTertiary = Color(0xFF8A8A93),
    Primary = Color(0xFF111113), OnPrimary = Color(0xFFFAFAF9),
    Clay = Color(0xFFF2683C), ClaySoft = Color(0x1FF2683C), ClayText = Color(0xFFB8461E), OnClay = Color(0xFF1A0B05), Scrim = Color(0x40000000),
    PreviewTop = Color(0xFF2E2E32), PreviewBottom = Color(0xFF18181B), OnMedia = Color.White, MediaScrim = Color(0x73000000),
    TrackAudio = Color(0x4055A68A), CaptionHighlight = Color(0xFFFFD27A),
)

val LocalClioPalette = staticCompositionLocalOf { DarkPalette }

/** Theme-aware palette accessor: `ClioColors.TextPrimary`. */
val ClioColors: ClioPalette
    @Composable @ReadOnlyComposable get() = LocalClioPalette.current
