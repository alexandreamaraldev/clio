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
 *  - overlay/PIP layers in export: one [BitmapOverlay] per layer (images decoded once, video
 *    frames pulled with MediaMetadataRetriever), placed with [OverlaySettings].
 */
@OptIn(UnstableApi::class)
object Layers {
    val NO_TRANSITION = Transition(TransitionKind.NONE, 0uL)

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

    /** Live preview: ExoPlayer has a single effect chain, so it reads a look pushed once per UI frame. */
    class PreviewLook {
        @Volatile var look: FrameLook = FrameLook(LayerTransform(0f, 0f, 1f, 0f, 1f), 1f)
        val effects = listOf(
            MatrixTransformation { _ -> ndcMatrix(look.transform) },
            RgbMatrix { _, _ -> brightnessMatrix(look.brightness) },
        )
    }

    /** Export: per-item effects, local time from the first frame the item sees. */
    fun exportEffects(c: Clip, incoming: Transition): List<androidx.media3.common.Effect> {
        if (c.transition.kind == TransitionKind.NONE && incoming.kind == TransitionKind.NONE) return emptyList()
        val clock = LocalClock()
        return listOf(
            MatrixTransformation { pts -> ndcMatrix(look(c, incoming, clock.ms(pts)).transform) },
            RgbMatrix { pts, _ -> brightnessMatrix(look(c, incoming, clock.ms(pts)).brightness) },
        )
    }

    /** Presentation times restart per item inside a sequence; anchor on the first frame seen. */
    class LocalClock {
        private var base = Long.MIN_VALUE
        fun ms(ptsUs: Long): Long { if (base == Long.MIN_VALUE) base = ptsUs; return (ptsUs - base) / 1000 }
    }

    /**
     * One overlay layer drawn on top of a main-track item. [itemStartMs] is where the host item
     * sits on the timeline; the layer shows only while the timeline time is inside the clip.
     */
    class LayerOverlay(
        private val context: Context,
        private val c: Clip,
        private val itemStartMs: Long,
        private val canvasW: Int,
        private val canvasH: Int,
        private val isImage: Boolean,
        fps: Int,
    ) : BitmapOverlay() {
        private val clock = LocalClock()
        private val frameMs = (1000 / fps.coerceAtLeast(1)).toLong()
        private val targetW = (canvasW * c.transform.scale).toInt().coerceIn(2, 4096)
        private val blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        private var still: Bitmap? = null
        private var retriever: MediaMetadataRetriever? = null
        private var lastFrameAt = Long.MIN_VALUE
        private var last: Bitmap? = null
        private var visible = false

        private fun localMs(pts: Long) = itemStartMs + clock.ms(pts) - c.startMs.toLong()

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val t = localMs(presentationTimeUs)
            visible = t >= 0 && t < c.durationMs.toLong()
            if (!visible) return last ?: still ?: blank
            return if (isImage) still ?: decodeStill().also { still = it } else videoFrame(t)
        }

        private fun fitWidth(b: Bitmap): Bitmap {
            val h = (b.height.toFloat() * targetW / b.width.coerceAtLeast(1)).toInt().coerceAtLeast(2)
            return if (b.width == targetW) b else Bitmap.createScaledBitmap(b, targetW, h, true)
        }

        private fun decodeStill(): Bitmap = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2
            val b = open { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            b?.let(::fitWidth)
        }.getOrNull() ?: blank

        private fun <T> open(f: (java.io.InputStream) -> T): T? =
            context.contentResolver.openInputStream(Uri.parse(c.source))?.use(f)

        private fun videoFrame(localMs: Long): Bitmap {
            val bucket = localMs / frameMs
            if (bucket == lastFrameAt && last != null) return last!!
            val r = retriever ?: MediaMetadataRetriever().also {
                runCatching { it.setDataSource(context, Uri.parse(c.source)) }
                retriever = it
            }
            val srcUs = (c.inMs.toLong() + Mix.spanMs(localMs, c.speed)) * 1000
            val f = runCatching { r.getFrameAtTime(srcUs, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
            if (f != null) { last = fitWidth(f); lastFrameAt = bucket }
            return last ?: blank
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val tr = c.transform
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(tr.x.coerceIn(-1f, 1f), tr.y.coerceIn(-1f, 1f))
                .setRotationDegrees(tr.rotation)
                .setAlphaScale(if (visible) tr.opacity else 0f)
                .build()
        }

        override fun release() {
            super.release()
            runCatching { retriever?.release() }
        }
    }
}
