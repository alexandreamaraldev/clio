package dev.clio.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double) = TextStyle(
    fontFamily = Sans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = tracking.em,
)

/** Strong hierarchy: tight, heavy display; calm, airy body. */
val ClioTypography = Typography(
    displayLarge = style(44, FontWeight.SemiBold, 48, -0.035),
    displayMedium = style(34, FontWeight.SemiBold, 40, -0.03),
    headlineLarge = style(28, FontWeight.SemiBold, 34, -0.025),
    headlineMedium = style(22, FontWeight.SemiBold, 28, -0.02),
    titleLarge = style(18, FontWeight.Medium, 24, -0.01),
    titleMedium = style(15, FontWeight.Medium, 20, -0.005),
    bodyLarge = style(16, FontWeight.Normal, 24, 0.0),
    bodyMedium = style(14, FontWeight.Normal, 20, 0.0),
    labelLarge = style(14, FontWeight.Medium, 18, 0.0),
    labelMedium = style(12, FontWeight.Medium, 16, 0.01),
    labelSmall = style(11, FontWeight.Medium, 14, 0.04),
)
