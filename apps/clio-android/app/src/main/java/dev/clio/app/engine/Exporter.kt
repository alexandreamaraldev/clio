package dev.clio.app.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
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
    /** Diagnostics toggle used by the device QA export matrix. */
    internal var frameDrop = true


    suspend fun export(snap: Snapshot, spec: ExportSpec, captionStyle: CaptionStyle, onProgress: (Float) -> Unit): Uri {
        val out = File(context.cacheDir, "export-${System.currentTimeMillis()}.mp4")
        val composition = buildComposition(snap, spec, captionStyle)
        withContext(Dispatchers.Main) { run(composition, out, onProgress) }
        return withContext(Dispatchers.IO) { publish(out, snap.summary.name) }
    }

    private fun buildComposition(snap: Snapshot, spec: ExportSpec, style: CaptionStyle): Composition {
        val video = snap.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips.orEmpty()
        require(video.isNotEmpty()) { "Adicione um vídeo antes de exportar" }
        val texts = snap.tracks.filter { it.kind == TrackKind.TEXT }.flatMap { it.clips }.map { c ->
            StyledTextItem(c.name, c.startMs.toLong(), (c.startMs + c.durationMs).toLong(), snap.textStyles.firstOrNull { it.clipId == c.id }?.style ?: StyledText.defaultStyle)
        }
        val caps = snap.captions
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
            val overlays = ImmutableList.of<androidx.media3.effect.TextureOverlay>(
                StyledTextOverlay(context, c.startMs.toLong(), spec.width, spec.height, 0f) { TextScene.text(texts, it) },
                StyledTextOverlay(context, c.startMs.toLong(), spec.width, spec.height, -0.72f) { TextScene.caption(caps, style.preset, it) },
            )
            val video = mutableListOf<androidx.media3.common.Effect>()
            if (speed != 1f) video += SpeedChangeEffect(speed)
            if (!isImage && frameDrop) video += FrameDropEffect.createDefaultFrameDropEffect(spec.fps.toFloat())
            if (blur && c.width > 0u && c.height > 0u) {
                // Stretch to the canvas, blur it, and draw the sharp frame on top at its fitted size.
                val (sx, sy) = Mix.fitScale(c.width.toInt(), c.height.toInt(), spec.width, spec.height)
                video += Presentation.createForWidthAndHeight(spec.width, spec.height, Presentation.LAYOUT_STRETCH_TO_FIT)
                video += GaussianBlurWithFrameOverlaid(spec.height / 40f, sx, sy)
            } else {
                video += Presentation.createForWidthAndHeight(spec.width, spec.height,
                    if (snap.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT)
            }
            video += OverlayEffect(overlays)
            EditedMediaItem.Builder(mi)
                .apply { if (isImage) { setDurationUs(c.durationMs.toLong() * 1000); setFrameRate(spec.fps) } }
                .setEffects(Effects(audioChain(c, speed), video))
                .build()
        }
        val seqs = mutableListOf(EditedMediaItemSequence(items))
        snap.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.clips?.firstOrNull()?.let { m ->
            val end = minOf(m.durationMs.toLong(), totalMs - m.startMs.toLong()).coerceAtLeast(1)
            val mi = MediaItem.Builder().setUri(m.source).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(m.inMs.toLong()).setEndPositionMs(m.inMs.toLong() + Mix.spanMs(end, m.speed)).build()).build()
            seqs += EditedMediaItemSequence(ImmutableList.of(EditedMediaItem.Builder(mi).setRemoveVideo(true)
                .setEffects(Effects(audioChain(m, m.speed), ImmutableList.of())).build()))
        }
        return Composition.Builder(seqs).build()
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
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            cont.resumeWithException(exportException)
                        }
                    }).build()
                transformer.start(composition, out.path)
                cont.invokeOnCancellation { transformer.cancel() }
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
        if (c.muted || c.volume != 1f || c.fadeInMs > 0u || c.fadeOutMs > 0u)
            add(GainProcessor(c.durationMs.toLong(), c.volume, c.fadeInMs.toLong(), c.fadeOutMs.toLong(), c.muted))
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
