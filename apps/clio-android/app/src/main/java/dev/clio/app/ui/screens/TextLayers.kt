package dev.clio.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import dev.clio.app.engine.Engine
import dev.clio.app.engine.StyledTextItem
import dev.clio.app.engine.TextPainter
import dev.clio.app.engine.TextScene

/**
 * Preview layers for styled text and word-by-word captions. They read the playhead only while
 * drawing (no recomposition during playback) and use the same engine frames + [TextPainter] as
 * the Media3 export, so what you see is what gets burned in.
 */
@Composable
fun StyledTextLayer(texts: List<TextItem>, playhead: () -> Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val painter = remember { TextPainter(ctx) }
    val items = remember(texts) { texts.map { StyledTextItem(it.text, it.startMs, it.endMs, it.style) } }
    Canvas(modifier) {
        val s = TextScene.text(items, (playhead() * 1000).toLong())
        drawIntoCanvas { painter.draw(it.nativeCanvas, s.words, s.style, size.width.toInt(), size.height.toInt(), size.width / 2, size.height / 2, s.alpha, s.scale, s.offsetY) }
    }
}

@Composable
fun StyledCaptionLayer(caps: List<Engine.Cap>, style: CaptionStyle, playhead: () -> Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val painter = remember { TextPainter(ctx) }
    val ffi = remember(caps) { caps.map { it.toFfi() } }
    Canvas(modifier) {
        val s = TextScene.caption(ffi, style.preset, (playhead() * 1000).toLong())
        // Same spot as the export overlay anchor (-0.72 NDC).
        drawIntoCanvas { painter.draw(it.nativeCanvas, s.words, s.style, size.width.toInt(), size.height.toInt(), size.width / 2, size.height * 0.86f, s.alpha, s.scale, s.offsetY) }
    }
}
