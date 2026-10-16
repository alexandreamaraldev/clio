package dev.clio.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import dev.clio.app.R
import uniffi.clio_ffi.AnimFrame
import uniffi.clio_ffi.Caption
import uniffi.clio_ffi.CaptionFrame
import uniffi.clio_ffi.CaptionPreset
import uniffi.clio_ffi.TextAnim
import uniffi.clio_ffi.TextFont
import uniffi.clio_ffi.TextStyle
import uniffi.clio_ffi.WordFrame
import uniffi.clio_ffi.WordState
import kotlin.math.max

/**
 * Styled text and word-by-word captions. The engine (Rust, crates/ffi-mobile/src/text.rs) decides
 * *what* each frame looks like ([textFrame], [captionFrame]); [TextPainter] draws it on an Android
 * canvas. The Compose preview and the Media3 export both use this painter, so they match.
 */
object StyledText {
    /** Mirrors `TextStyle::default()` in Rust; used only when the native engine isn't loaded (JVM tests). */
    val fallbackStyle = TextStyle(TextFont.MONTSERRAT, true, 0.045f, 0xFFFFFFFFu, 0u, 0f, 0u, true,
        uniffi.clio_ffi.TextAlign.CENTER, TextAnim.POP, TextAnim.FADE, 300u)

    val defaultStyle: TextStyle by lazy { runCatching { uniffi.clio_ffi.defaultTextStyle() }.getOrDefault(fallbackStyle) }

    fun textFrame(style: TextStyle, text: String, localMs: Long, durationMs: Long): AnimFrame =
        runCatching { uniffi.clio_ffi.textAnimFrame(style, text, localMs.coerceAtLeast(0).toULong(), durationMs.coerceAtLeast(1).toULong()) }
            .getOrElse { AnimFrame(1f, 1f, 0f, text.codePointCount(0, text.length).toUInt()) }

    fun captionFrame(c: Caption, preset: CaptionPreset, tMs: Long): CaptionFrame =
        runCatching { uniffi.clio_ffi.captionFrame(c, preset, tMs.coerceAtLeast(0).toULong()) }.getOrElse {
            CaptionFrame(c.text.split(' ').filter { it.isNotEmpty() }.map { WordFrame(it, WordState.FUTURE, 0xFFFFFFFFu, 0u, 1f, 1f, 0f) },
                fallbackStyle.copy(size = 0.034f, font = TextFont.INTER, bold = false), 1f, 1f)
        }

    /** Words for an animated text clip frame (typewriter cuts the string). */
    fun textWords(text: String, style: TextStyle, f: AnimFrame): List<WordFrame> {
        val n = f.visibleChars.toInt().coerceAtMost(text.codePointCount(0, text.length))
        val shown = text.substring(0, text.offsetByCodePoints(0, n))
        return shown.split(' ').filter { it.isNotEmpty() }.map { WordFrame(it, WordState.PAST, style.color, 0u, 1f, 1f, 0f) }
    }
}

/** Typefaces for [TextFont]; all bundled fonts are SIL OFL 1.1 (see assets/licenses). */
object Fonts {
    private val cache = HashMap<Pair<TextFont, Boolean>, Typeface>()
    fun res(f: TextFont) = when (f) {
        TextFont.INTER -> R.font.inter
        TextFont.MONTSERRAT -> R.font.montserrat
        TextFont.BEBAS_NEUE -> R.font.bebas_neue
        TextFont.PACIFICO -> R.font.pacifico
    }
    fun label(f: TextFont) = when (f) {
        TextFont.INTER -> "Inter"; TextFont.MONTSERRAT -> "Montserrat"; TextFont.BEBAS_NEUE -> "Bebas"; TextFont.PACIFICO -> "Pacifico"
    }
    fun get(context: Context, f: TextFont, bold: Boolean): Typeface = cache.getOrPut(f to bold) {
        val base = runCatching { ResourcesCompat.getFont(context, res(f)) }.getOrNull() ?: Typeface.DEFAULT
        // Inter and Montserrat are variable fonts: pick a real heavy weight where the platform allows.
        if (!bold) base else if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 800, false) else Typeface.create(base, Typeface.BOLD)
    }
}

/** Draws a block of words (wrapped, aligned, outlined, shadowed, per-word scale/offset/pill) on a canvas. */
class TextPainter(private val context: Context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private class Line(val words: List<WordFrame>, val widths: List<Float>, val width: Float)

    private fun lines(words: List<WordFrame>, maxW: Float): List<Line> {
        val space = paint.measureText(" ")
        val out = mutableListOf<Line>()
        var cur = mutableListOf<WordFrame>(); var ws = mutableListOf<Float>(); var w = 0f
        for (word in words) {
            val ww = paint.measureText(word.text)
            if (cur.isNotEmpty() && w + space + ww > maxW) { out += Line(cur, ws, w); cur = mutableListOf(); ws = mutableListOf(); w = 0f }
            w += (if (cur.isEmpty()) 0f else space) + ww; cur += word; ws += ww
        }
        if (cur.isNotEmpty()) out += Line(cur, ws, w)
        return out
    }

    /** Height of the block for a frame `frameW`×`frameH`. */
    fun measure(words: List<WordFrame>, style: TextStyle, frameW: Int, frameH: Int): Float {
        setup(style, frameH)
        val px = paint.textSize
        return lines(words, frameW * 0.86f).size * px * 1.25f + px * 0.6f
    }

    private fun setup(style: TextStyle, frameH: Int) {
        paint.typeface = Fonts.get(context, style.font, style.bold)
        paint.textSize = style.size * frameH
    }

    /** Draws centred on (`cx`, `cy`). */
    fun draw(canvas: Canvas, words: List<WordFrame>, style: TextStyle, frameW: Int, frameH: Int, cx: Float, cy: Float,
             alpha: Float = 1f, scale: Float = 1f, offsetY: Float = 0f) {
        if (words.isEmpty() || alpha <= 0f) return
        setup(style, frameH)
        val px = paint.textSize
        val maxW = frameW * 0.86f
        val ls = lines(words, maxW)
        val lineH = px * 1.25f
        val space = paint.measureText(" ")
        val blockH = ls.size * lineH
        canvas.save()
        canvas.translate(0f, offsetY * px)
        canvas.scale(scale, scale, cx, cy)
        val a = alpha.coerceIn(0f, 1f)
        var y = cy - blockH / 2 + lineH * 0.8f
        for (l in ls) {
            var x = when (style.align) {
                uniffi.clio_ffi.TextAlign.START -> cx - maxW / 2
                uniffi.clio_ffi.TextAlign.END -> cx + maxW / 2 - l.width
                uniffi.clio_ffi.TextAlign.CENTER -> cx - l.width / 2
            }
            if (style.background.alpha() > 0) {
                bg.color = style.background.toInt(); bg.alpha = (style.background.alpha() * a).toInt()
                rect.set(x - px * 0.35f, y - px * 1.0f, x + l.width + px * 0.35f, y + px * 0.3f)
                canvas.drawRoundRect(rect, px * 0.25f, px * 0.25f, bg)
            }
            for ((i, w) in l.words.withIndex()) {
                val ww = l.widths[i]
                val wa = a * w.alpha.coerceIn(0f, 1f)
                if (wa > 0f) {
                    canvas.save()
                    canvas.translate(0f, w.offsetY * px)
                    canvas.scale(w.scale, w.scale, x + ww / 2, y - px * 0.35f)
                    if (w.background.alpha() > 0) {
                        bg.color = w.background.toInt(); bg.alpha = (w.background.alpha() * wa).toInt()
                        rect.set(x - px * 0.2f, y - px * 0.95f, x + ww + px * 0.2f, y + px * 0.25f)
                        canvas.drawRoundRect(rect, px * 0.22f, px * 0.22f, bg)
                    }
                    if (style.strokeWidth > 0f && style.strokeColor.alpha() > 0) {
                        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND
                        paint.strokeWidth = px * style.strokeWidth
                        paint.color = style.strokeColor.toInt(); paint.alpha = (style.strokeColor.alpha() * wa).toInt()
                        paint.clearShadowLayer()
                        canvas.drawText(w.text, x, y, paint)
                    }
                    paint.style = Paint.Style.FILL
                    paint.color = w.color.toInt(); paint.alpha = (w.color.alpha() * wa).toInt()
                    if (style.shadow) paint.setShadowLayer(px * 0.12f, 0f, px * 0.05f, ((0x90 * wa).toInt() shl 24)) else paint.clearShadowLayer()
                    canvas.drawText(w.text, x, y, paint)
                    canvas.restore()
                }
                x += ww + space
            }
            y += lineH
        }
        canvas.restore()
    }

    private fun UInt.alpha(): Int = (this shr 24).toInt() and 0xFF
}

/** Timeline time (ms) → what to draw: a style, words and a whole-block animation. */
class TextScene(val style: TextStyle, val words: List<WordFrame>, val alpha: Float, val scale: Float, val offsetY: Float) {
    val key: String = "$style|$words|$alpha|$scale|$offsetY"
    companion object {
        val Empty = TextScene(StyledText.fallbackStyle, emptyList(), 0f, 1f, 0f)

        fun text(items: List<StyledTextItem>, ms: Long): TextScene {
            val t = items.firstOrNull { ms >= it.startMs && ms < it.endMs } ?: return Empty
            val f = StyledText.textFrame(t.style, t.text, ms - t.startMs, t.endMs - t.startMs)
            return TextScene(t.style, StyledText.textWords(t.text, t.style, f), f.alpha, f.scale, f.offsetY)
        }

        fun caption(caps: List<Caption>, preset: CaptionPreset, ms: Long): TextScene {
            val c = caps.firstOrNull { ms >= it.startMs.toLong() && ms <= it.endMs.toLong() } ?: return Empty
            val f = StyledText.captionFrame(c, preset, ms)
            return TextScene(f.style, f.words, f.alpha, f.scale, 0f)
        }
    }
}

data class StyledTextItem(val text: String, val startMs: Long, val endMs: Long, val style: TextStyle)

/**
 * Export: paints the current [TextScene] into a frame-wide bitmap. A new bitmap is made only when
 * the scene changes (Media3 re-uploads on bitmap identity), so static text costs nothing per frame.
 */
@OptIn(UnstableApi::class)
class StyledTextOverlay(
    context: Context,
    private val clipStartMs: Long,
    private val frameW: Int,
    private val frameH: Int,
    /** Vertical anchor in NDC (0 = centre, -0.72 = caption line). */
    private val anchorY: Float,
    private val scene: (Long) -> TextScene,
) : BitmapOverlay() {
    private val painter = TextPainter(context)
    private var basePts = Long.MIN_VALUE
    private var lastKey: String? = null
    private var last: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (basePts == Long.MIN_VALUE) basePts = presentationTimeUs
        val s = scene(clipStartMs + (presentationTimeUs - basePts) / 1000)
        if (s.key == lastKey) return last
        lastKey = s.key
        if (s.words.isEmpty() || s.alpha <= 0f) { last = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); return last }
        // Room for pop/bounce overshoot around the block.
        val h = max(2, (painter.measure(s.words, s.style, frameW, frameH) * 1.6f).toInt())
        last = Bitmap.createBitmap(frameW, h, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(last), s.words, s.style, frameW, frameH, frameW / 2f, h / 2f, s.alpha, s.scale, s.offsetY)
        return last
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder().setBackgroundFrameAnchor(0f, anchorY).build()
}
