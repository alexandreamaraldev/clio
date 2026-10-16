package dev.clio.app.engine

/**
 * Mirror of the engine's layer/transition limits in `crates/ffi-mobile/src/layers.rs`.
 * The engine stays the source of truth (it clamps every edit); these exist so the UI and export
 * use the same ranges without a native call (Paparazzi/JVM tests have no native lib).
 * `LayerLimitsTest` parses the Rust file and fails if any value drifts.
 */
object LayerLimits {
    const val MIN_LAYER_SCALE = 0.05f
    const val MAX_LAYER_SCALE = 4.0f
    const val MAX_LAYER_OFFSET = 2.0f
    const val MIN_TRANSITION_MS = 100L
    const val MAX_TRANSITION_MS = 3000L

    val scaleRange get() = MIN_LAYER_SCALE..MAX_LAYER_SCALE
    val offsetRange get() = -MAX_LAYER_OFFSET..MAX_LAYER_OFFSET
}
