package dev.clio.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/** Thumbnail loader `(source, timeMs) -> bitmap`; null in previews/tests. */
val LocalThumbnails = staticCompositionLocalOf<(suspend (String, Long) -> ImageBitmap?)?> { null }
