package dev.clio.app.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import dev.clio.app.ui.screens.CaptionStyle
import kotlinx.coroutines.*
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.TrackKind
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ExportSpec(val label: String, val width: Int, val height: Int, val fps: Int)

/**
 * INTERIM exporter on Media3 Transformer (H.264/AAC MP4): concatenates the video track (clips
 * trimmed by their in/out points), scales to the preset with fit (letterbox) or fill (crop), burns
 * in captions and text overlays, mixes the music track, then saves to the gallery (MediaStore).
 * The long-term path is the FilmCraft `render`/`export` crates through ffi-mobile.
 */
@OptIn(UnstableApi::class)
class Exporter(private val context: Context) {
    private val gradeLib by lazy { Grades.library(context) }
    /** Diagnostics toggle used by the device QA export matrix. */
    internal var frameDrop = true

    /** Layer decode counters from the last export (bench/QA diagnostics). */
    data class LayerStats(val decodedFrames: Int, val requestedFrames: Int)
    /** HDR handling for one attempt: GPU tone-map, MediaCodec tone-map, or drop the grades. */
    private enum class HdrPlan { CODEC, GL, AS_SDR, DROP }
    private var hdrPlan = HdrPlan.GL
    private var toneMap = false
    /** pt-BR notice about the last export (e.g. HDR grade skipped), or null. */
    @Volatile var lastNotice: String? = null; private set
    @Volatile internal var lastLayerStats = LayerStats(0, 0); private set

    companion object {
        const val MAX_MUXER_GAP_MS = 60_000L
    }


    suspend fun export(snap: Snapshot, spec: ExportSpec, captionStyle: CaptionStyle, onProgress: (Float) -> Unit): Uri {
        val out = File(context.cacheDir, "export-${System.currentTimeMillis()}.mp4")
        // Layer frame sources live for exactly one export (Exporter instances may be shared).
        val sources = mutableListOf<Layers.FrameSource>()
        try {
            // Probe HDR off the main thread before building (MediaExtractor per new URI, cached).
            val videoSources = snap.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips.orEmpty().map { it.source }.filter { !MediaKind.isImage(context, it) }
            HdrProbe.prefetch(context, videoSources)
            // HDR + grades: MediaCodec tone-mapping (API 31+) first, then GPU tone-mapping (needs
            // GL_EXT_YUV_target, missing on some GPUs/emulators), then reading HDR as SDR (keeps grades,
            // flatter colours), then exporting without grades. Each failure retries automatically.
            // Ungraded HDR projects keep HDR (one attempt, no tone-map).
            val anyHdr = videoSources.any { HdrProbe.cached(it) == true }
            val plans = if (!anyHdr || snap.grades.isEmpty()) listOf(HdrPlan.DROP)
                else buildList { if (Build.VERSION.SDK_INT >= 31) add(HdrPlan.CODEC); if (Build.VERSION.SDK_INT >= 29) { add(HdrPlan.GL); add(HdrPlan.AS_SDR) }; add(HdrPlan.DROP) }
            for ((k, plan) in plans.withIndex()) {
                hdrPlan = plan
                sources.forEach { it.close() }; sources.clear()
                val composition = buildComposition(snap, spec, captionStyle, sources)
                try { withContext(Dispatchers.Main) { run(composition, out, onProgress) }; break }
                catch (e: ExportException) {
                    if (!anyHdr) throw e
                    // The last stage already exports without grades: say the device can't do this HDR video.
                    if (k == plans.lastIndex) throw UserFacingExportError("Este aparelho não consegue exportar este vídeo HDR.", e)
                    android.util.Log.w("Clio", "HDR tone-map ($plan) failed, trying ${plans[k + 1]}", e)
                }
            }
            lastLayerStats = LayerStats(sources.sumOf { it.decodes }, sources.sumOf { it.requests })
            sources.firstNotNullOfOrNull { it.error }?.let { throw IllegalStateException(it) }
        } finally { sources.forEach { it.close() } }
        return withContext(Dispatchers.IO) { publish(out, snap.summary.name) }
    }

    private fun buildComposition(snap: Snapshot, spec: ExportSpec, style: CaptionStyle, opened: MutableList<Layers.FrameSource>): Composition {
        val video = snap.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips.orEmpty()
        require(video.isNotEmpty()) { "Adicione um vídeo antes de exportar" }
        val texts = snap.tracks.filter { it.kind == TrackKind.TEXT }.flatMap { it.clips }.map { c ->
            StyledTextItem(c.name, c.startMs.toLong(), (c.startMs + c.durationMs).toLong(), snap.textStyles.firstOrNull { it.clipId == c.id }?.style ?: StyledText.defaultStyle)
        }
        val caps = snap.captions
        // Media3 1.4's LUT shader is SDR-only (checkArgument(!useHdr)), and one HDR clip makes the whole
        // sequence's pipeline HDR. So with ANY HDR clip and any grade the export is tone-mapped to SDR on
        // the GPU (API 29+); below that all grades are dropped for this export and [lastNotice] says so.
        val graded = Grades.byClip(snap.grades)
        val anyHdr = video.any { !MediaKind.isImage(context, it.source) && HdrProbe.cached(it.source) == true }
        // Any HDR clip: convert the whole export to SDR (matches the SDR preview), grades or not.
        // Tone-map only when grades meet HDR (Media3's LUT is SDR-only); ungraded HDR stays HDR.
        toneMap = anyHdr && graded.isNotEmpty() && hdrPlan != HdrPlan.DROP
        val dropGrades = anyHdr && graded.isNotEmpty() && !toneMap
        lastNotice = when {
            dropGrades -> "Vídeo HDR: os filtros foram ignorados nesta exportação (este aparelho não converte HDR para SDR)."
            toneMap && hdrPlan == HdrPlan.AS_SDR -> "Vídeo HDR: este aparelho não converte HDR; as cores do trecho HDR podem ficar lavadas."
            else -> null
        }
        val gradeOf = if (dropGrades) emptyMap() else graded
        val totalMs = snap.summary.durationMs.toLong()
        val blur = snap.blurBg && !snap.fill

        // One frame source per layer, shared by every main item it overlaps (closed after export).
        val layers = Layers.overlayClips(snap)
        val sources = Layers.FrameSource.plan(context, layers, spec.width) { MediaKind.isImage(context, it.source) }
        opened += sources.values
        curveOf = snap.speedCurves.associate { it.clipId to it.curve }
        val items = video.mapIndexed { index, c ->
            val isImage = MediaKind.isImage(context, c.source)
            val speed = if (isImage) 1f else c.speed
            val curve = if (isImage) null else curveMap(c)?.exportEffects()
            val mi = MediaItem.Builder().setUri(c.source).apply {
                if (!isImage) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.inMs.toLong()).setEndPositionMs(c.inMs.toLong() + Mix.spanMs(c.durationMs.toLong(), speed)).build())
                else setImageDurationMs(c.durationMs.toLong())
            }.build()
            // Overlay/PIP layers that overlap this item (bottom row first); drawn after the blur backdrop, before text.
            val layerOverlays = layers.filter { it.startMs < c.startMs + c.durationMs && it.startMs + it.durationMs > c.startMs }
                .map { Layers.LayerOverlay(sources.getValue(it.id), it, c.startMs.toLong(), spec.width, spec.height) }
            val incoming = Layers.incoming(video, index)
            val video = mutableListOf<androidx.media3.common.Effect>()
            // A speed curve retimes each frame (slow parts hold frames longer; no frame blending).
            if (curve != null) video += curve.first else if (speed != 1f) video += SpeedChangeEffect(speed)
            if (!isImage && frameDrop && FpsCap.needed(FpsCap.sourceFps(context, Uri.parse(c.source), c.inMs.toLong() * 1000), spec.fps.toFloat(), (if (curve != null) curveMap(c)?.maxSpeed else null) ?: speed)) video += FpsCap(spec.fps.toFloat(), smooth = curve != null)
            video += Presentation.createForWidthAndHeight(spec.width, spec.height,
                if (snap.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT)
            // Blurred background: a pre-blurred still of the clip fills the letterbox bars (the centre is
            // transparent, so the fitted video shows through). Same look as the preview, and cheap on any GPU.
            // (Media3 1.4's GaussianBlurWithFrameOverlaid produced wrong output sizes / stalled on device QA.)
            // It is part of the clip: applied before the transition effects so Slide/Zoom move clip + backdrop together.
            if (blur) blurredBackdrop(c, spec.width, spec.height)?.let { video += OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(it))) }
            // Filter + colour adjustments: the same baked LUT effect the preview uses (grades the backdrop too).
            gradeOf[c.id.toLong()]?.let { g -> val plane = gradeLib.plane(g); video += GradeLut { plane } }
            video += Layers.exportEffects(c, incoming)
            val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
            overlays += layerOverlays
            overlays += StyledTextOverlay(context, c.startMs.toLong(), spec.width, spec.height, 0f) { TextScene.text(texts, it) }
            overlays += StyledTextOverlay(context, c.startMs.toLong(), spec.width, spec.height, -0.72f) { TextScene.caption(caps, style.preset, it) }
            video += OverlayEffect(ImmutableList.copyOf(overlays))
            EditedMediaItem.Builder(mi)
                .apply { if (isImage) { setDurationUs(c.durationMs.toLong() * 1000); setFrameRate(spec.fps) } }
                .setEffects(Effects(if (curve != null) audioChain(c, speed, curve.second) else audioChain(c, speed), video))
                .build()
        }
        val seqs = mutableListOf(EditedMediaItemSequence(items))
        seqs += AudioExport.sequences(context, snap, totalMs, chain = ::audioChain)
        return Composition.Builder(seqs).experimentalSetForceAudioTrack(true)
            .apply { if (toneMap) setHdrMode(when (hdrPlan) {
                HdrPlan.CODEC -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
                HdrPlan.AS_SDR -> Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR
                else -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
            }) }
            .setEffects(Effects(listOf(LimiterProcessor()), emptyList())).build()
    }

    private suspend fun run(composition: Composition, out: File, onProgress: (Float) -> Unit) = coroutineScope {
        lateinit var transformer: Transformer
        val poll = launch {
            val h = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(h.progress / 100f)
                delay(150)
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                transformer = Transformer.Builder(context)
                    // Keep SDR inputs in their own colour space. The DEFAULT working space converts sRGB
                    // stills to SMPTE 170M (smpte170mOetf(srgbEotf(x))), so grey 128 encoded as 115.
                    .setVideoFrameProcessorFactory(androidx.media3.effect.DefaultVideoFrameProcessor.Factory.Builder()
                        .setSdrWorkingColorSpace(androidx.media3.effect.DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL).build())
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    // Heavy effect chains (blur, 4K) on slow devices can legitimately exceed the 10 s default.
                    .setMaxDelayBetweenMuxerSamplesMs(MAX_MUXER_GAP_MS)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            cont.resumeWithException(exportException)
                        }
                    }).build()
                transformer.start(composition, out.path)
                // Cancellation can arrive on any thread (e.g. a timeout); Transformer is confined to Main.
                cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() } }
            }
        } finally { poll.cancel() }
        onProgress(1f)
    }

    private fun publish(file: File, title: String): Uri {
        val name = "Clio ${title.take(40).replace(Regex("[^\\p{L}\\p{N} _-]"), "")} ${System.currentTimeMillis() / 1000}.mp4"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Clio")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val r = context.contentResolver
                val uri = r.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv) ?: error("MediaStore insert failed")
                r.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
                r.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                return uri
            }
            // API 26–28: app-specific Movies dir (no storage permission needed), shared via FileProvider.
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Clio").apply { mkdirs() }
            val dst = File(dir, name); file.copyTo(dst, overwrite = true)
            return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", dst)
        } finally { file.delete() }
    }

    /** Speed (pitch kept) then volume × fade envelope. */
    private var curveOf: Map<ULong, uniffi.clio_ffi.SpeedCurve> = emptyMap()
    private fun curveMap(c: uniffi.clio_ffi.Clip): SpeedMap? = curveOf[c.id]?.let { SpeedMap.of(it, Mix.spanMs(c.durationMs.toLong(), c.speed), c.durationMs.toLong()) }

    private fun audioChain(c: uniffi.clio_ffi.Clip, speed: Float, curveAudio: AudioProcessor? = null): List<AudioProcessor> = buildList {
        if (curveAudio != null) add(curveAudio)
        else if (speed != 1f) add(SonicAudioProcessor().apply { setSpeed(speed); setPitch(1f) })
        if (AudioGain.silent(c) || c.volume != 1f || c.fadeInMs > 0u || c.fadeOutMs > 0u)
            add(GainProcessor(c.durationMs.toLong(), c.volume, c.fadeInMs.toLong(), c.fadeOutMs.toLong(), AudioGain.silent(c)))
    }

    /** Canvas-size bitmap: blurred, cover-cropped frame of [c] with a transparent hole where the fitted video sits. */
    private fun blurredBackdrop(c: uniffi.clio_ffi.Clip, w: Int, h: Int): android.graphics.Bitmap? = runCatching {
        val src: android.graphics.Bitmap = (if (MediaKind.isImage(context, c.source)) context.contentResolver.openInputStream(Uri.parse(c.source))!!.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
        } else MediaMetadataRetriever().run {
            try { setDataSource(context, Uri.parse(c.source)); getFrameAtTime((c.inMs.toLong() + 100) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } finally { release() }
        }) ?: return null
        val sw = src.width; val sh = src.height
        // Blur by repeated down/up-scaling with bilinear filtering.
        var small = android.graphics.Bitmap.createScaledBitmap(src, 24, (24f * sh / sw).toInt().coerceAtLeast(1), true)
        repeat(2) { small = android.graphics.Bitmap.createScaledBitmap(android.graphics.Bitmap.createScaledBitmap(small, small.width * 2, small.height * 2, true), small.width, small.height, true) }
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val cover = maxOf(w.toFloat() / sw, h.toFloat() / sh)
        val dw = sw * cover; val dh = sh * cover
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        cv.drawBitmap(small, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), paint)
        cv.drawColor(0x33000000)
        val (fx, fy) = Mix.fitScale(sw, sh, w, h)
        val hw = w * fx / 2; val hh = h * fy / 2
        cv.drawRect(w / 2f - hw, h / 2f - hh, w / 2f + hw, h / 2f + hh, android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) })
        if (src !== small) src.recycle()
        out
    }.getOrNull()
}

/** 16-bit PCM gain with linear fades; times are in output (post-speed) clip time. */
@OptIn(UnstableApi::class)
private class GainProcessor(private val durMs: Long, private val volume: Float, private val fadeInMs: Long, private val fadeOutMs: Long, private val muted: Boolean) : BaseAudioProcessor() {
    private var frames = 0L
    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        return f
    }
    override fun queueInput(input: ByteBuffer) {
        val ch = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val n = input.remaining()
        val out = replaceOutputBuffer(n)
        val src = input.order(ByteOrder.nativeOrder())
        while (src.remaining() >= 2 * ch) {
            val g = Mix.gain(frames * 1000 / rate, durMs, volume, fadeInMs, fadeOutMs, muted)
            repeat(ch) { out.putShort((src.short * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()) }
            frames++
        }
        input.position(input.limit())
        out.flip()
    }
    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }
}

object MediaKind {
    fun isImage(context: Context, source: String): Boolean =
        runCatching { context.contentResolver.getType(Uri.parse(source)) }.getOrNull()?.startsWith("image/")
            ?: source.substringAfterLast('.').lowercase().let { it in setOf("jpg", "jpeg", "png", "webp", "heic") }
}

/** An export failure whose message is already a complete pt-BR sentence for the user (no cause chain). */
class UserFacingExportError(message: String, cause: Throwable?) : Exception(message, cause)
