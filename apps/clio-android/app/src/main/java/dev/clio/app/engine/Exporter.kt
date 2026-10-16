package dev.clio.app.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.FrameDropEffect
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
import androidx.media3.effect.TextOverlay
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
    /** Diagnostics toggle used by the device QA export matrix. */
    internal var frameDrop = true

    companion object {
        const val MAX_MUXER_GAP_MS = 60_000L
    }


    suspend fun export(snap: Snapshot, spec: ExportSpec, captionStyle: CaptionStyle, onProgress: (Float) -> Unit): Uri {
        val out = File(context.cacheDir, "export-${System.currentTimeMillis()}.mp4")
        val composition = buildComposition(snap, spec, captionStyle)
        withContext(Dispatchers.Main) { run(composition, out, onProgress) }
        return withContext(Dispatchers.IO) { publish(out, snap.summary.name) }
    }

    private fun buildComposition(snap: Snapshot, spec: ExportSpec, style: CaptionStyle): Composition {
        val video = snap.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips.orEmpty()
        require(video.isNotEmpty()) { "Adicione um vídeo antes de exportar" }
        val texts = snap.tracks.filter { it.kind == TrackKind.TEXT }.flatMap { it.clips }
            .map { Timed(it.startMs.toLong(), (it.startMs + it.durationMs).toLong(), it.name) }
        val caps = snap.captions.map { Timed(it.startMs.toLong(), it.endMs.toLong(), it.text) }
        val totalMs = snap.summary.durationMs.toLong()
        val blur = snap.blurBg && !snap.fill

        val items = video.map { c ->
            val isImage = MediaKind.isImage(context, c.source)
            val speed = if (isImage) 1f else c.speed
            val mi = MediaItem.Builder().setUri(c.source).apply {
                if (!isImage) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.inMs.toLong()).setEndPositionMs(c.inMs.toLong() + Mix.spanMs(c.durationMs.toLong(), speed)).build())
                else setImageDurationMs(c.durationMs.toLong())
            }.build()
            val video = mutableListOf<androidx.media3.common.Effect>()
            if (speed != 1f) video += SpeedChangeEffect(speed)
            if (!isImage && frameDrop) video += FrameDropEffect.createDefaultFrameDropEffect(spec.fps.toFloat())
            video += Presentation.createForWidthAndHeight(spec.width, spec.height,
                if (snap.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT)
            val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
            // Blurred background: a pre-blurred still of the clip fills the letterbox bars (the centre is
            // transparent, so the fitted video shows through). Same look as the preview, and cheap on any GPU.
            // (Media3 1.4's GaussianBlurWithFrameOverlaid produced wrong output sizes / stalled on device QA.)
            if (blur) blurredBackdrop(c, spec.width, spec.height)?.let { overlays += BitmapOverlay.createStaticBitmapOverlay(it) }
            overlays += TimedTextOverlay(c.startMs.toLong(), texts, spec.height, centered = true, style = CaptionStyle.Bold)
            overlays += TimedTextOverlay(c.startMs.toLong(), caps, spec.height, centered = false, style = style)
            video += OverlayEffect(ImmutableList.copyOf(overlays))
            EditedMediaItem.Builder(mi)
                .apply { if (isImage) { setDurationUs(c.durationMs.toLong() * 1000); setFrameRate(spec.fps) } }
                .setEffects(Effects(audioChain(c, speed), video))
                .build()
        }
        val seqs = mutableListOf(EditedMediaItemSequence(items))
        seqs += AudioExport.sequences(context, snap, totalMs, chain = ::audioChain)
        return Composition.Builder(seqs).experimentalSetForceAudioTrack(true)
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
    private fun audioChain(c: uniffi.clio_ffi.Clip, speed: Float): List<AudioProcessor> = buildList {
        if (speed != 1f) add(SonicAudioProcessor().apply { setSpeed(speed); setPitch(1f) })
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

    private data class Timed(val startMs: Long, val endMs: Long, val text: String)

    /** Shows whichever cue is active at the current frame; times are relative to the clip's place on the timeline. */
    private class TimedTextOverlay(
        private val clipStartMs: Long,
        private val cues: List<Timed>,
        private val frameHeight: Int,
        private val centered: Boolean,
        private val style: CaptionStyle,
    ) : TextOverlay() {
        private var basePts = Long.MIN_VALUE
        private val empty = SpannableString(" ")

        override fun getText(presentationTimeUs: Long): SpannableString {
            if (basePts == Long.MIN_VALUE) basePts = presentationTimeUs
            val t = clipStartMs + (presentationTimeUs - basePts) / 1000
            val cue = cues.firstOrNull { t in it.startMs until it.endMs } ?: return empty
            val text = if (style == CaptionStyle.Bold) cue.text.uppercase() else cue.text
            return SpannableString(text).apply {
                val all = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                setSpan(AbsoluteSizeSpan((frameHeight * if (centered) 0.045f else 0.032f).toInt()), 0, length, all)
                setSpan(StyleSpan(if (style == CaptionStyle.Clean) Typeface.NORMAL else Typeface.BOLD), 0, length, all)
                setSpan(ForegroundColorSpan(if (style == CaptionStyle.Karaoke) 0xFFFFD27A.toInt() else Color.WHITE), 0, length, all)
                if (style == CaptionStyle.Box) setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, length, all)
            }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            OverlaySettings.Builder().setBackgroundFrameAnchor(0f, if (centered) 0f else -0.72f).build()
    }
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
