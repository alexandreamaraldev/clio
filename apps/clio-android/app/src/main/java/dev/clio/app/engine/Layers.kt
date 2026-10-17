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
import uniffi.clio_ffi.KeyTracks
import uniffi.clio_ffi.clipLook
import uniffi.clio_ffi.clipLookKeyed
import uniffi.clio_ffi.mainKeyedTransform
import uniffi.clio_ffi.keyedTransform

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

    /** Canvas a main-track clip is framed in (zoom/pan fills it for the clip's aspect). */
    data class Canvas(val w: Int, val h: Int, val crop: Boolean)

    /** Transitions only (the preview's GL effects). */
    fun look(c: Clip, incoming: Transition, localMs: Long): FrameLook = clipLook(c, incoming, localMs.coerceAtLeast(0).toULong())

    /** Export: the engine's keyed transform (zoom/pan covered) then transitions. */
    fun look(c: Clip, incoming: Transition, localMs: Long, keys: KeyTracks?, canvas: Canvas): FrameLook =
        clipLookKeyed(c, keys, incoming, localMs.coerceAtLeast(0).toULong(), canvas.w.toUInt(), canvas.h.toUInt(), canvas.crop)

    /** Preview: the same keyed transform, drawn on the canvas by Compose (stills never reach the GL chain). */
    fun mainTransform(c: Clip, keys: KeyTracks, localMs: Long, canvas: Canvas): LayerTransform =
        mainKeyedTransform(c, keys, localMs.coerceIn(0, c.durationMs.toLong()).toULong(), canvas.w.toUInt(), canvas.h.toUInt(), canvas.crop)

    /** Keyed main-track transform shown by the preview (identity when the clip has no keys). */
    object PreviewKeys { val transform = androidx.compose.runtime.mutableStateOf(IDENTITY.transform) }

    /** A layer's transform at clip-local [localMs] (keyframes over the static transform). */
    fun transformAt(c: Clip, keys: KeyTracks?, localMs: Long): LayerTransform =
        if (keys == null) c.transform else keyedTransform(c.transform, keys, localMs.coerceIn(0, c.durationMs.toLong()).toULong())

    /** Largest scale a layer reaches (bitmaps are decoded at that size). */
    fun maxScale(c: Clip, keys: KeyTracks?): Float = (keys?.scale?.maxOfOrNull { it.value } ?: 0f).coerceAtLeast(c.transform.scale)



    /** NDC matrix for a main-track look (layer x/y are already NDC; rotation ignored on the main track). */
    fun ndcMatrix(t: LayerTransform): Matrix = Matrix().apply {
        postScale(t.scale, t.scale)
        postTranslate(t.x, t.y)
    }

    /** Main-track brightness: transitions' fade times the keyed opacity (a main clip fades to black). */
    fun mainBrightness(l: FrameLook): Float = l.brightness * l.transform.opacity

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

    /**
     * The current main-clip transition look for Compose (written on Main once per frame). The
     * blurred backdrop belongs to its clip, so the preview backdrop moves with it during
     * Slide/Zoom, as in export.
     */
    object PreviewTransition { val look = androidx.compose.runtime.mutableStateOf(IDENTITY) }

    /** Live preview: ExoPlayer has a single effect chain, so it reads a look pushed once per UI frame. */
    class PreviewLook {
        @Volatile var look: FrameLook = IDENTITY
            set(v) { field = v; PreviewTransition.look.value = v }
        fun reset() { look = IDENTITY }
        val effects = listOf(
            MatrixTransformation { _ -> ndcMatrix(look.transform) },
            RgbMatrix { _, _ -> brightnessMatrix(look.brightness) },
        )
    }

    /** Export: per-item effects, local time from the first frame the item sees. One FFI call per frame, shared by both effects. */
    fun exportEffects(c: Clip, incoming: Transition, keys: KeyTracks? = null, canvas: Canvas = Canvas(0, 0, false)): List<androidx.media3.common.Effect> {
        if (keys == null && c.transition.kind == TransitionKind.NONE && incoming.kind == TransitionKind.NONE) return emptyList()
        val cache = LookCache(c, incoming, keys, canvas)
        return listOf(
            MatrixTransformation { pts -> ndcMatrix(cache.at(pts).transform) },
            RgbMatrix { pts, _ -> brightnessMatrix(mainBrightness(cache.at(pts))) },
        )
    }

    /** Memoises clip_look per presentation time (both GL effects ask for the same frame). */
    class LookCache(private val c: Clip, private val incoming: Transition, private val keys: KeyTracks? = null, private val canvas: Canvas = Canvas(0, 0, false)) {
        private val clock = LocalClock()
        private var lastPts = Long.MIN_VALUE
        private var last: FrameLook? = null
        @Synchronized fun at(pts: Long): FrameLook {
            if (pts != lastPts || last == null) { last = look(c, incoming, clock.ms(pts), keys, canvas); lastPts = pts }
            return last!!
        }
    }

    /** Presentation times restart per item inside a sequence; anchor on the first frame seen. */
    class LocalClock {
        private var base = Long.MIN_VALUE
        fun ms(ptsUs: Long): Long { if (base == Long.MIN_VALUE) base = ptsUs; return (ptsUs - base) / 1000 }
    }

    /**
     * Frames for one overlay layer, shared by every host item it overlaps. Video is decoded once,
     * forward, by a [SequentialVideoDecoder] that picks frames by their real sample timestamps
     * (VFR-safe) into one reused RGB_565 bitmap; images are decoded once (ARGB, keeps alpha).
     * [memScale] (<= 1, from [LayerMemory]) shrinks the bitmap to fit the export memory budget;
     * OverlaySettings scales it back up. Times are layer-local (0 = layer start on the timeline).
     */
    class FrameSource(
        private val context: Context, private val c: Clip, private val isImage: Boolean, canvasW: Int,
        /** Display height / width of the source (after rotation). */
        val aspect: Float, memScale: Float = 1f, maxScale: Float = c.transform.scale,
    ) : AutoCloseable {
        /** Wanted on-canvas width in px (at the largest keyed scale). */
        val wantW = canvasW * maxScale.coerceIn(LayerLimits.scaleRange)
        val targetW = (wantW * memScale).toInt().coerceIn(2, 4096)
        val targetH = (targetW * aspect).toInt().coerceIn(2, 4096)
        private val blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        private var still: Bitmap? = null
        private var decoder: SequentialVideoDecoder? = null
        private var failed = false
        /** Frames requested by the exporter (one per output frame while visible). */
        var requests = 0; private set
        val decodes: Int get() = (decoder?.decoded ?: 0) + endDecodes

        val latest: Bitmap? get() = still ?: decoder?.bitmap ?: endFrame ?: fallbackFrame

        @Synchronized fun frame(localMs: Long): Bitmap {
            requests++
            if (isImage) return still ?: decodeStill().also { still = it }
            val srcUs = (c.inMs.toLong() + Mix.spanMs(localMs.coerceAtLeast(0), c.speed)) * 1000
            if (ended) return endFrame ?: fallbackFrame ?: blank
            if (!failed) {
                val d = decoder ?: runCatching { SequentialVideoDecoder(context, Uri.parse(c.source), targetW, targetH) }
                    .onFailure { failed = true; android.util.Log.w("Clio", "layer decoder unavailable, using retriever", it) }
                    .getOrNull()?.also { decoder = it }
                if (d != null) {
                    runCatching { d.frameAt(srcUs) }.onSuccess { return it }.onFailure {
                        // Mid-stream codec error: drop the decoder and continue on the retriever path.
                        android.util.Log.w("Clio", "layer decode failed mid-stream, using retriever", it)
                        fallbackFrame = d.bitmap.copy(Bitmap.Config.RGB_565, false)
                        endDecodes += d.decoded; d.close(); decoder = null; failed = true
                    }
                }
            }
            return fallback(srcUs)
        }

        /** Hardware decoder limit hit: slow but correct per-frame MediaMetadataRetriever path. */
        private var retriever: android.media.MediaMetadataRetriever? = null
        private var fallbackFrame: Bitmap? = null
        /** Set when no frame could be read at all; the exporter turns it into a failed export. */
        var error: String? = null; private set

        private fun fallback(srcUs: Long): Bitmap {
            val r = retriever ?: runCatching { android.media.MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(c.source)) } }
                .getOrNull()?.also { retriever = it }
            val f = r?.let { m -> runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 27) m.getScaledFrameAtTime(srcUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH)
                else m.getFrameAtTime(srcUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)?.let { b -> Bitmap.createScaledBitmap(b, targetW, targetH, true).also { s -> if (s !== b) b.recycle() } }
            }.getOrNull() }
            if (f == null) {
                if (fallbackFrame == null) error = "Não foi possível ler o vídeo de uma camada. Feche outros apps e tente de novo."
                return fallbackFrame ?: blank
            }
            fallbackFrame?.recycle(); fallbackFrame = f
            return f
        }

        private var ended = false
        private var endFrame: Bitmap? = null
        private var endDecodes = 0
        /** The layer is past its end on the timeline (export runs forward): free the decoder now. */
        @Synchronized fun end() {
            if (ended || isImage) return
            ended = true
            // Codec, extractor and the int arrays go; only the last bitmap is kept for the hidden overlay.
            endFrame = decoder?.bitmap; endDecodes = decoder?.decoded ?: 0; decoder?.close(); decoder = null
            retriever?.release(); retriever = null
        }

        private fun decodeStill(): Bitmap = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            read { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2
            val b = read { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
            if (b.width == targetW) b else Bitmap.createScaledBitmap(b, targetW, targetH, true).also { if (it !== b) b.recycle() }
        }.getOrNull() ?: blank

        private fun <T> read(f: (java.io.InputStream) -> T): T? = context.contentResolver.openInputStream(Uri.parse(c.source))?.use(f)

        @Synchronized override fun close() { decoder?.close(); decoder = null; retriever?.release(); retriever = null }

        companion object {
            /** Builds one source per layer, sized together under [LayerMemory.BUDGET_BYTES]. */
            fun plan(context: Context, layers: List<Clip>, canvasW: Int, keys: Map<ULong, KeyTracks> = emptyMap(), isImage: (Clip) -> Boolean): Map<ULong, FrameSource> {
                val info = layers.map { c ->
                    val img = isImage(c)
                    val aspect = (if (img) null else SequentialVideoDecoder.probe(context, Uri.parse(c.source))?.let { (w, h, _) -> h.toFloat() / w })
                        ?: if (c.width > 0u && c.height > 0u) c.height.toFloat() / c.width.toFloat() else 9f / 16f
                    Triple(c, img, aspect)
                }
                val sizes = info.map { (c, _, a) -> val w = (canvasW * maxScale(c, keys[c.id]).coerceIn(LayerLimits.scaleRange)).toInt().coerceIn(2, 4096); w to (w * a).toInt().coerceAtLeast(2) }
                val scale = LayerMemory.fit(sizes, info.map { if (it.second) LayerMemory.IMAGE_BPP else LayerMemory.VIDEO_BPP })
                return info.associate { (c, img, a) -> c.id to FrameSource(context, c, img, canvasW, a, scale, maxScale(c, keys[c.id])) }
            }
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
        private val keys: KeyTracks? = null,
    ) : BitmapOverlay() {
        private val clock = LocalClock()
        private var visible = false

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val t = itemStartMs + clock.ms(presentationTimeUs) - c.startMs.toLong()
            visible = t >= 0 && t < c.durationMs.toLong()
            if (t >= c.durationMs.toLong()) src.end()
            return if (visible) src.frame(t) else src.latest ?: src.frame(0)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val tr = transformAt(c, keys, itemStartMs + clock.ms(presentationTimeUs) - c.startMs.toLong())
            // The bitmap is sized for the largest keyed scale; shrink it to this frame's scale.
            val k = tr.scale.coerceIn(LayerLimits.scaleRange) * canvasW / src.wantW
            val up = src.wantW / src.targetW * k
            val wPx = src.wantW * k; val hPx = wPx * src.aspect
            // Half-size of the layer in background NDC (1 = half the canvas). Media3 applies the
            // overlay anchor after rotating about the layer centre, so the shift stays unrotated.
            val halfW = wPx / canvasW
            val halfH = hPx / canvasH
            val x = tr.x.coerceIn(LayerLimits.offsetRange); val y = tr.y.coerceIn(LayerLimits.offsetRange)
            val (bx, ox, _) = anchor(x, halfW)
            val (by, oy, _) = anchor(y, halfH)
            val on = layerOnCanvas(x * canvasW / 2, y * canvasH / 2, wPx, hPx, tr.rotation, canvasW, canvasH)
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(bx, by)
                .setOverlayFrameAnchor(ox, oy)
                .setScale(up, up)
                .setRotationDegrees(tr.rotation)
                .setAlphaScale(if (visible && on) tr.opacity else 0f)
                .build()
        }
    }
}
