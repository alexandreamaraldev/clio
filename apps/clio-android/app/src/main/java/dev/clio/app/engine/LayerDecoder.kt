package dev.clio.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Picks frames by their real sample timestamps (VFR-safe). Frames come in decode order with
 * increasing pts; for a wanted source time `t` the right frame is the last one with pts <= t.
 * This class only holds the bookkeeping so it can be unit tested without MediaCodec.
 */
internal class FrameSelector {
    /** pts of the frame currently shown (converted), or MIN when none. */
    var shownPts = Long.MIN_VALUE; private set
    /** pts of a decoded frame held back because it is still in the future. */
    var pendingPts = Long.MIN_VALUE; private set

    enum class Action { SHOW_CURRENT, NEED_MORE, SEEK }

    /** What to do for wanted time [tUs] before decoding anything else. */
    fun plan(tUs: Long, seekThresholdUs: Long = 2_000_000): Action = when {
        shownPts != Long.MIN_VALUE && tUs < shownPts - 1_000 -> Action.SEEK        // went backwards
        shownPts != Long.MIN_VALUE && tUs - shownPts > seekThresholdUs && pendingPts == Long.MIN_VALUE -> Action.SEEK
        pendingPts != Long.MIN_VALUE && pendingPts > tUs -> Action.SHOW_CURRENT    // next frame is still ahead
        pendingPts == Long.MIN_VALUE && shownPts != Long.MIN_VALUE && tUs == shownPts -> Action.SHOW_CURRENT
        else -> Action.NEED_MORE
    }

    /**
     * A frame with [pts] came out of the decoder (or the held one is re-examined).
     * Returns true if it should be converted and shown now (it is the newest frame <= t and the
     * following one, [pendingPts], is past t) — callers report frames one at a time.
     */
    fun offer(pts: Long, tUs: Long): Offer =
        if (pts <= tUs) { Offer.CANDIDATE } else { pendingPts = pts; Offer.FUTURE }

    fun shown(pts: Long) { shownPts = pts; if (pendingPts == pts) pendingPts = Long.MIN_VALUE }
    fun consumePending() { pendingPts = Long.MIN_VALUE }
    fun reset() { shownPts = Long.MIN_VALUE; pendingPts = Long.MIN_VALUE }

    enum class Offer { CANDIDATE, FUTURE }
}

/** Source pixel for a destination pixel, honouring the container rotation (90/180/270, clockwise). */
internal fun rotatedSource(dx: Int, dy: Int, dstW: Int, dstH: Int, srcW: Int, srcH: Int, rotation: Int): Pair<Int, Int> {
    // Normalised destination coordinates.
    val u = (dx + 0.5f) / dstW; val v = (dy + 0.5f) / dstH
    val (su, sv) = when (((rotation % 360) + 360) % 360) {
        90 -> v to (1f - u)
        180 -> (1f - u) to (1f - v)
        270 -> (1f - v) to u
        else -> u to v
    }
    return (su * srcW).toInt().coerceIn(0, srcW - 1) to (sv * srcH).toInt().coerceIn(0, srcH - 1)
}

/** Display size of a source after rotation. */
internal fun displaySize(w: Int, h: Int, rotation: Int): Pair<Int, Int> =
    if (((rotation % 180) + 180) % 180 == 90) h to w else w to h

/**
 * Rotation-aware visibility: is any part of a layer (size [wPx]×[hPx], centre offset
 * [cx],[cy] px from the canvas centre, rotated [deg]) on a [canvasW]×[canvasH] canvas?
 */
internal fun layerOnCanvas(cx: Float, cy: Float, wPx: Float, hPx: Float, deg: Float, canvasW: Int, canvasH: Int): Boolean {
    val r = Math.toRadians(deg.toDouble())
    val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
    val bw = c * wPx + s * hPx; val bh = s * wPx + c * hPx
    return abs(cx) < (canvasW + bw) / 2 && abs(cy) < (canvasH + bh) / 2
}

/**
 * Total pixel budget for export layer bitmaps (RGB_565 video frames + ARGB stills + the shared
 * conversion buffer), so many layers can't exhaust memory. Each layer's export size is scaled
 * down to fit; OverlaySettings scales it back up on the GPU.
 */
internal object LayerMemory {
    const val BUDGET_BYTES = 48L * 1024 * 1024
    /** Video: RGB_565 bitmap (2) + ARGB pixels and the X/Y source maps (3 IntArrays, 12). */
    const val VIDEO_BPP = 2 + 12
    /** Images: one ARGB bitmap. */
    const val IMAGE_BPP = 4

    /** Scale factor (<= 1) applied to every layer so that their summed bytes fit the budget. */
    fun fit(sizes: List<Pair<Int, Int>>, bytesPerPixel: List<Int>): Float {
        val total = sizes.zip(bytesPerPixel).sumOf { (s, b) -> s.first.toLong() * s.second * b }
        if (total <= BUDGET_BYTES || total == 0L) return 1f
        return kotlin.math.sqrt(BUDGET_BYTES.toDouble() / total).toFloat().coerceIn(0.05f, 1f)
    }
}

/**
 * One forward MediaCodec decode per layer. Frames are fetched by source time; only the frame
 * actually shown is converted (YUV → RGB_565, nearest sample, rotation applied) into a single
 * reused bitmap. Counts decoded frames so the bench can report decodes per output frame.
 */
internal class SequentialVideoDecoder(context: Context, uri: Uri, private val dstW: Int, private val dstH: Int) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val track: Int
    private val srcW: Int
    private val srcH: Int
    val rotation: Int
    private val info = MediaCodec.BufferInfo()
    private val sel = FrameSelector()
    private var inputDone = false
    private var heldIndex = -1
    private var heldPts = Long.MIN_VALUE
    private var pendingIndex = -1
    private val pixels = IntArray(dstW * dstH)
    val bitmap: Bitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.RGB_565)
    var decoded = 0; private set
    var converted = 0; private set

    init {
        extractor.setDataSource(context, uri, null)
        track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val f = extractor.getTrackFormat(track)
        srcW = f.getInteger(MediaFormat.KEY_WIDTH); srcH = f.getInteger(MediaFormat.KEY_HEIGHT)
        rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
        codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec.configure(f, null, null, 0)
        codec.start()
    }

    /** Bitmap for source time [tUs] (microseconds in the source file). */
    fun frameAt(tUs: Long): Bitmap {
        when (sel.plan(tUs)) {
            FrameSelector.Action.SHOW_CURRENT -> return bitmap
            FrameSelector.Action.SEEK -> seek(tUs)
            FrameSelector.Action.NEED_MORE -> {}
        }
        // A held future frame may now be due.
        if (pendingIndex >= 0 && sel.pendingPts <= tUs) { hold(pendingIndex, sel.pendingPts); pendingIndex = -1; sel.consumePending() }
        var guard = 0
        while (pendingIndex < 0 && guard++ < 2000) {
            feed()
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            if (idx < 0) { if (inputDone && idx == MediaCodec.INFO_TRY_AGAIN_LATER) break; continue }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && info.size == 0) { codec.releaseOutputBuffer(idx, false); break }
            decoded++
            when (sel.offer(info.presentationTimeUs, tUs)) {
                FrameSelector.Offer.CANDIDATE -> hold(idx, info.presentationTimeUs)
                FrameSelector.Offer.FUTURE -> pendingIndex = idx
            }
        }
        if (heldIndex >= 0) {
            convert(heldIndex); codec.releaseOutputBuffer(heldIndex, false)
            sel.shown(heldPts); heldIndex = -1
        }
        return bitmap
    }

    /** Keep the newest candidate; drop the previous one without converting it. */
    private fun hold(idx: Int, pts: Long) {
        if (heldIndex >= 0) codec.releaseOutputBuffer(heldIndex, false)
        heldIndex = idx; heldPts = pts
    }

    private fun feed() {
        if (inputDone) return
        val i = codec.dequeueInputBuffer(0)
        if (i < 0) return
        val buf = codec.getInputBuffer(i)!!
        val n = extractor.readSampleData(buf, 0)
        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
        else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
    }

    private fun seek(tUs: Long) {
        if (heldIndex >= 0) { codec.releaseOutputBuffer(heldIndex, false); heldIndex = -1 }
        if (pendingIndex >= 0) { codec.releaseOutputBuffer(pendingIndex, false); pendingIndex = -1 }
        codec.flush(); sel.reset(); inputDone = false
        extractor.seekTo(tUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    private val mapX = IntArray(dstW * dstH)
    private val mapY = IntArray(dstW * dstH)
    private var mapW = -1
    private var mapH = -1

    /** Destination → source sample positions, computed once per source size. */
    private fun buildMap(w: Int, h: Int) {
        for (dy in 0 until dstH) for (dx in 0 until dstW) {
            val (sx, sy) = rotatedSource(dx, dy, dstW, dstH, w, h, rotation)
            mapX[dy * dstW + dx] = sx; mapY[dy * dstW + dx] = sy
        }
        mapW = w; mapH = h
    }

    private fun convert(idx: Int) {
        val img = codec.getOutputImage(idx) ?: return
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        val crop = img.cropRect
        val w = crop.width().coerceAtMost(srcW).coerceAtLeast(1); val h = crop.height().coerceAtMost(srcH).coerceAtLeast(1)
        if (mapW != w || mapH != h) buildMap(w, h)
        for (i in 0 until dstW * dstH) {
            val sx = mapX[i] + crop.left; val sy = mapY[i] + crop.top
            val yy = (yb.get(sy * y.rowStride + sx * y.pixelStride).toInt() and 0xFF) - 16
            val ci = (sy / 2) * u.rowStride + (sx / 2) * u.pixelStride
            val cu = (ub.get(ci).toInt() and 0xFF) - 128
            val cv = (vb.get((sy / 2) * v.rowStride + (sx / 2) * v.pixelStride).toInt() and 0xFF) - 128
            val yf = 1.164f * yy
            val r = (yf + 1.596f * cv).toInt().coerceIn(0, 255)
            val g = (yf - 0.392f * cu - 0.813f * cv).toInt().coerceIn(0, 255)
            val b = (yf + 2.017f * cu).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        img.close()
        bitmap.setPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        converted++
    }

    override fun close() {
        runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { extractor.release() }
    }

    companion object {
        /** Display aspect (h / w) of a video, after rotation, without starting a decoder. */
        fun probe(context: Context, uri: Uri): Triple<Int, Int, Int>? = runCatching {
            val e = MediaExtractor(); try {
                e.setDataSource(context, uri, null)
                val f = (0 until e.trackCount).map { e.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                val (w, h) = displaySize(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT), rot)
                Triple(w, h, rot)
            } finally { e.release() }
        }.getOrNull()
    }
}
