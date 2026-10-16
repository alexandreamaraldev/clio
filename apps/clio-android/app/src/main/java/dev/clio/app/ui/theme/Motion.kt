package dev.clio.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/** Subtle, smooth motion. Springs are critically damped-ish: no bounce theatrics. */
object ClioMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> gentle() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    fun <T> snappy() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
    val slide = spring<IntOffset>(dampingRatio = 0.92f, stiffness = Spring.StiffnessLow)
    fun <T> fade(ms: Int = 280) = tween<T>(ms, easing = Standard)
}
