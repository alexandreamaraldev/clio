package dev.clio.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.RgbMatrix
import uniffi.clio_ffi.Clip
import uniffi.clio_ffi.FrameLook
import uniffi.clio_ffi.LayerTransform
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.TrackKind
import uniffi.clio_ffi.Transition
import uniffi.clio_ffi.TransitionKind
import uniffi.clio_ffi.clipLook

/**
 * Phase 2 layers on Media3. The engine (`clip_look`) decides what a frame looks like; this file
 * turns that into GL effects for ExoPlayer (preview) and Transformer (export):
 *  - main-track transitions: a [MatrixTransformation] (slide/zoom) + [RgbMatrix] (fade) driven by
 *    the clip's local time;
 *  - overlay/PIP layers in export: one [BitmapOverlay] per layer and host item, fed by one shared
 *    [FrameSource] per layer (images decoded once; video decoded sequentially in batches into a
 *    reused bitmap), placed with [OverlaySettings].
 */
@OptIn(UnstableApi::class)
object Layers {
    val NO_TRANSITION = Transition(TransitionKind.NONE, 0uL)
    val IDENTITY = FrameLook(LayerTransform(0f, 0f, 1f, 0f, 1f), 1f)

    fun mainClips(s: Snapshot): List<Clip> = s.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips.orEmpty()
    /** Overlay rows bottom-to-top; clips within a row never overlap. */
    fun overlayClips(s: Snapshot): List<Clip> = s.tracks.filter { it.kind == TrackKind.OVERLAY }.flatMap { it.clips }

    fun incoming(main: List<Clip>, index: Int): Transition = main.getOrNull(index - 1)?.transition ?: NO_TRANSITION

    fun hasTransition(main: List<Clip>, index: Int) =
        main[index].transition.kind != TransitionKind.NONE || incoming(main, index).kind != TransitionKind.NONE

    fun look(c: Clip, incoming: Transition, localMs: Long): FrameLook = clipLook(c, incoming, localMs.coerceAtLeast(0).toULong())

    /** NDC matrix for a main-track look (layer x/y are already NDC; rotation ignored on the main track). */
    fun ndcMatrix(t: LayerTransform): Matrix = Matrix().apply {
        postScale(t.scale, t.scale)
        postTranslate(t.x, t.y)
    }

    fun brightnessMatrix(b: Float): FloatArray = floatArrayOf(
        b, 0f, 0f, 0f,
        0f, b, 0f, 0f,
        0f, 0f, b, 0f,
        0f, 0f, 0f, 1f,
    )

    /**
     * Media3 only accepts anchors in -1..1, but layers may sit up to ±[LayerLimits.MAX_LAYER_OFFSET].
     * Put the background anchor at the clamped point and shift the layer with its own anchor so its
     * centre still lands at [pos]. Returns (backgroundAnchor, overlayAnchor, anyPartOnCanvas).
     * [half] is the layer half-size in background NDC.
     */
    fun anchor(pos: Float, half: Float): Triple<Float, Float, Boolean> {
        val bg = pos.coerceIn(-1f, 1f)
        val h = half.coerceAtLeast(1e-4f)
        // Overlay anchor `a` (in layer units) placed at bg means centre = bg - a * h.
        val a = (bg - pos) / h
        return Triple(bg, a.coerceIn(-1f, 1f), kotlin.math.abs(a) < 1f)
    }

    /** Live preview: ExoPlayer has a single effect chain, so it reads a look pushed once per UI frame. */
    class PreviewLook {
        @Volatile var look: FrameLook = IDENTITY
        fun reset() { look = IDENTITY }
        val effects = listOf(
            MatrixTransformation { _ -> ndcMatrix(look.transform) },
            RgbMatrix { _, _ -> brightnessMatrix(look.brightness) },
        )
    }

    /** Export: per-item effects, local time from the first frame the item sees. One FFI call per frame, shared by both effects. */
    fun exportEffects(c: Clip, incoming: Transition): List<androidx.media3.common.Effect> {
        if (c.transition.kind == TransitionKind.NONE && incoming.kind == TransitionKind.NONE) return emptyList()
        val cache = LookCache(c, incoming)
        return listOf(
            MatrixTransformation { pts -> ndcMatrix(cache.at(pts).transform) },
            RgbMatrix { pts, _ -> brightnessMatrix(cache.at(pts).brightness) },
        )
    }

    /** Memoises clip_look per presentation time (both GL effects ask for the same frame). */
    class LookCache(private val c: Clip, private val incoming: Transition) {
        private val clock = LocalClock()
        private var lastPts = Long.MIN_VALUE
        private var last: FrameLook? = null
        @Synchronized fun at(pts: Long): FrameLook {
            if (pts != lastPts || last == null) { last = look(c, incoming, clock.ms(pts)); lastPts = pts }
            return last!!
        }
    }

    /** Presentation times restart per item inside a sequence; anchor on the first frame seen. */
    class LocalClock {
        private var base = Long.MIN_VALUE
        fun ms(ptsUs: Long): Long { if (base == Long.MIN_VALUE) base = ptsUs; return (ptsUs - base) / 1000 }
    }

    /**
     * Frames for one overlay layer, shared by every host item it overlaps. Video is decoded
     * forward in batches with `getFramesAtIndex` (API 28+, one decoder pass instead of a seek per
     * frame) and drawn into a single reused bitmap at the layer's export size; older devices fall
     * back to `getScaledFrameAtTime`. Times are layer-local (0 = layer start on the timeline).
     */
    class FrameSource(private val context: Context, private val c: Clip, private val isImage: Boolean, canvasW: Int) : AutoCloseable {
        /** Wanted on-canvas width; the bitmap is capped at 4096 px and scaled up by OverlaySettings. */
        val wantW = canvasW * c.transform.scale.coerceIn(LayerLimits.scaleRange)
        val targetW = wantW.toInt().coerceIn(2, 4096)
        private val aspect = if (c.width > 0u && c.height > 0u) c.height.toFloat() / c.width.toFloat() else 9f / 16f
        private val targetH = (targetW * aspect).toInt().coerceAtLeast(2)
        private val blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        private var out: Bitmap? = null
        private var still: Bitmap? = null
        private var retriever: MediaMetadataRetriever? = null
        private var fps = 30f
        private var frameCount = -1
        private var batchStart = -1
        private var batch: List<Bitmap> = emptyList()
        private var shownIndex = -1
        var decodes = 0; private set

        val latest: Bitmap? get() = out ?: still

        @Synchronized fun frame(localMs: Long): Bitmap {
            if (isImage) return still ?: decodeStill().also { still = it }
            val r = retriever ?: open()
            val srcMs = c.inMs.toLong() + Mix.spanMs(localMs.coerceAtLeast(0), c.speed)
            if (android.os.Build.VERSION.SDK_INT >= 28 && frameCount > 0) {
                val idx = (srcMs * fps / 1000f).toInt().coerceIn(0, frameCount - 1)
                if (idx == shownIndex && out != null) return out!!
                if (idx !in batchStart until batchStart + batch.size) {
                    batch.forEach { it.recycle() }
                    val n = batchSize().coerceAtMost(frameCount - idx)
                    batch = runCatching { r.getFramesAtIndex(idx, n) }.getOrDefault(emptyList())
                    batchStart = idx; decodes++
                }
                batch.getOrNull(idx - batchStart)?.let { draw(it); shownIndex = idx }
            } else {
                val b = runCatching { r.getScaledFrameAtTime(srcMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH) }.getOrNull()
                decodes++
                b?.let { draw(it); it.recycle() }
            }
            return out ?: blank
        }

        /** Up to [BATCH] frames, but at most ~64 MB of decoded source frames per pass. */
        private fun batchSize(): Int {
            val px = (c.width.toLong() * c.height.toLong()).coerceAtLeast(1280L * 720L)
            return (64L * 1024 * 1024 / (px * 4)).toInt().coerceIn(2, BATCH)
        }

        private fun open(): MediaMetadataRetriever = MediaMetadataRetriever().also { m ->
            runCatching {
                m.setDataSource(context, Uri.parse(c.source))
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    frameCount = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: -1
                    val dur = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull() ?: 0f
                    if (frameCount > 0 && dur > 0f) fps = frameCount * 1000f / dur
                }
            }
            retriever = m
        }

        /** Scales [src] into the reused output bitmap. */
        private fun draw(src: Bitmap) {
            val o = out ?: Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also { out = it }
            android.graphics.Canvas(o).drawBitmap(src, null, android.graphics.Rect(0, 0, o.width, o.height), PAINT)
        }

        private fun decodeStill(): Bitmap = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            read { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2
            val b = read { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
            val h = (b.height.toFloat() * targetW / b.width.coerceAtLeast(1)).toInt().coerceAtLeast(2)
            if (b.width == targetW) b else Bitmap.createScaledBitmap(b, targetW, h, true).also { if (it !== b) b.recycle() }
        }.getOrNull() ?: blank

        private fun <T> read(f: (java.io.InputStream) -> T): T? = context.contentResolver.openInputStream(Uri.parse(c.source))?.use(f)

        @Synchronized override fun close() {
            runCatching { retriever?.release() }; retriever = null
            batch.forEach { it.recycle() }; batch = emptyList()
        }

        companion object {
            /** Frames decoded per pass (memory: BATCH full-size source frames). */
            const val BATCH = 8
            private val PAINT = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        }
    }

    /**
     * One overlay layer drawn on top of a main-track item. [itemStartMs] is where the host item
     * sits on the timeline; the layer shows only while the timeline time is inside the clip.
     */
    class LayerOverlay(
        private val src: FrameSource,
        private val c: Clip,
        private val itemStartMs: Long,
        private val canvasW: Int,
        private val canvasH: Int,
    ) : BitmapOverlay() {
        private val clock = LocalClock()
        private var visible = false

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val t = itemStartMs + clock.ms(presentationTimeUs) - c.startMs.toLong()
            visible = t >= 0 && t < c.durationMs.toLong()
            return if (visible) src.frame(t) else src.latest ?: src.frame(0)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val tr = c.transform
            val b = src.latest
            val up = src.wantW / src.targetW
            // Half-size of the layer in background NDC (1 = half the canvas).
            val halfW = src.wantW / canvasW
            val halfH = if (b != null && b.width > 0) halfW * b.height / b.width * canvasW / canvasH else halfW * canvasW / canvasH
            val (bx, ox, inX) = anchor(tr.x.coerceIn(LayerLimits.offsetRange), halfW)
            val (by, oy, inY) = anchor(tr.y.coerceIn(LayerLimits.offsetRange), halfH)
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(bx, by)
                .setOverlayFrameAnchor(ox, oy)
                .setScale(up, up)
                .setRotationDegrees(tr.rotation)
                .setAlphaScale(if (visible && inX && inY) tr.opacity else 0f)
                .build()
        }
    }
}
