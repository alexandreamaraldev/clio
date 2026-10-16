package dev.clio.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Reads the real exported MP4: container/track properties, frames and decoded audio levels. */
object ExportProbe {
    data class Info(
        val durationMs: Long, val width: Int, val height: Int, val fps: Float,
        val hasAudio: Boolean, val audioDurationMs: Long, val audioMime: String?, val videoMime: String?,
    )

    fun info(ctx: Context, uri: Uri): Info {
        val r = MediaMetadataRetriever()
        val (d, size) = try {
            r.setDataSource(ctx, uri)
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() to (if (rot % 180 != 0) vh to vw else vw to vh)
        } finally { r.release() }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var fps = 0f; var aDur = 0L; var aMime: String? = null; var vMime: String? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME)!!
                if (mime.startsWith("video/")) {
                    vMime = mime
                    // Count samples: container frame-rate fields are optional, the samples are not.
                    ex.selectTrack(i); var n = 0; var first = -1L; var last = 0L
                    while (true) { val t = ex.sampleTime; if (t < 0) break; if (first < 0) first = t; last = t; n++; ex.advance() }
                    ex.unselectTrack(i)
                    fps = if (n > 1) (n - 1) * 1_000_000f / (last - first) else 0f
                } else if (mime.startsWith("audio/")) {
                    aMime = mime
                    aDur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
                }
            }
            return Info(d, size.first, size.second, fps, aMime != null, aDur, aMime, vMime)
        } finally { ex.release() }
    }

    fun frame(ctx: Context, uri: Uri, atMs: Long): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
        } finally { r.release() }
    }

    /** Decodes the first audio track to mono and returns RMS (0..1) per [windowMs] window from 0. */
    fun audioRms(ctx: Context, uri: Uri, windowMs: Long = 100): FloatArray {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        val fmt = ex.getTrackFormat(ti); ex.selectTrack(ti)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sums = ArrayList<Double>(); val counts = ArrayList<Int>()
        var frameIndex = 0L; var inDone = false; val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                } else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = b.remaining() / ch
                    for (f in 0 until frames) {
                        var acc = 0.0; for (c in 0 until ch) acc += b.get(f * ch + c) / 32768.0
                        val m = acc / ch
                        val w = (frameIndex * 1000 / rate / windowMs).toInt()
                        while (sums.size <= w) { sums += 0.0; counts += 0 }
                        sums[w] += m * m; counts[w] += 1; frameIndex++
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { codec.stop(); codec.release(); ex.release() }
        return FloatArray(sums.size) { if (counts[it] == 0) 0f else sqrt(sums[it] / counts[it]).toFloat() }
    }

    /** True when the pixel is within [tol] of [rgb] on every channel. */
    fun near(b: Bitmap, x: Int, y: Int, rgb: Int, tol: Int = 48): Boolean {
        val p = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
        return listOf(16, 8, 0).all { s -> kotlin.math.abs(((p shr s) and 255) - ((rgb shr s) and 255)) <= tol }
    }
}
