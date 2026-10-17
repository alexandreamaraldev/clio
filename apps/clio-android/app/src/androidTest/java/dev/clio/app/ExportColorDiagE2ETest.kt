package dev.clio.app

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Colour-path diagnosis (logs only): exports a grey/red/skin/blue still through bare Transformer
 * variants (no Clio effects) and reads both the raw decoded Y'CbCr and the retriever's RGB, so an
 * encoder/decoder offset can be told apart from a shader/LUT one.
 */
@RunWith(AndroidJUnit4::class)
class ExportColorDiagE2ETest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val swatches = intArrayOf(0x808080, 0xC8503C, 0xDCAA8C, 0x325AB4)
    private fun log(s: String) = android.util.Log.i("ClioQA", "CLIO-QA color-diag $s").also { println("CLIO-QA color-diag $s") }

    private fun still(): String {
        val b = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b); val p = android.graphics.Paint()
        swatches.forEachIndexed { i, rgb -> p.color = 0xFF000000.toInt() or rgb; c.drawRect(0f, i * 320f, 720f, (i + 1) * 320f, p) }
        val f = File(ctx.cacheDir, "diag.png"); f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f).toString()
    }

    private fun export(name: String, original: Boolean, presentation: Boolean): File {
        val out = File(ctx.cacheDir, "diag-$name.mp4"); out.delete()
        val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(still()).setImageDurationMs(1000).build())
            .setDurationUs(1_000_000).setFrameRate(30)
            .setEffects(Effects(emptyList(), if (presentation) listOf(Presentation.createForWidthAndHeight(720, 1280, Presentation.LAYOUT_SCALE_TO_FIT)) else emptyList()))
            .build()
        val done = CountDownLatch(1); var err: Exception? = null
        Handler(Looper.getMainLooper()).post {
            Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264)
                .apply { if (original) setVideoFrameProcessorFactory(DefaultVideoFrameProcessor.Factory.Builder().setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL).build()) }
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) { done.countDown() }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) { err = e; done.countDown() }
                }).build().start(Composition.Builder(EditedMediaItemSequence(item)).build(), out.path)
        }
        done.await(120, TimeUnit.SECONDS); err?.let { throw it }
        return out
    }

    /** Mean RGB (retriever) per swatch. */
    private fun rgb(f: File): String {
        val m = MediaMetadataRetriever(); m.setDataSource(f.path)
        val b = m.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!; m.release()
        return swatches.indices.joinToString(" ") { i ->
            var r = 0; var g = 0; var bb = 0; var n = 0
            val cy = b.height * (2 * i + 1) / 8
            for (y in cy - 16 until cy + 16) for (x in b.width / 2 - 16 until b.width / 2 + 16) { val p = b.getPixel(x, y); r += (p shr 16) and 255; g += (p shr 8) and 255; bb += p and 255; n++ }
            "${r / n},${g / n},${bb / n}"
        }
    }

    /** Raw decoded Y/U/V per swatch plus the stream's colour tags. */
    private fun yuv(f: File): String {
        val ex = MediaExtractor(); ex.setDataSource(f.path); ex.selectTrack(0)
        val fmt = ex.getTrackFormat(0)
        val tags = listOf(MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER).joinToString(",") { if (fmt.containsKey(it)) fmt.getInteger(it).toString() else "-" }
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo(); var res = "none"; var eos = false
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!eos) { val i = codec.dequeueInputBuffer(10_000); if (i >= 0) { val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0); if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() } } }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val img = codec.getOutputImage(o)
                if (img != null && info.presentationTimeUs >= 400_000) {
                    res = swatches.indices.joinToString(" ") { i ->
                        // Bands run along the long axis (the encoder may store the frame rotated).
                        val land = img.width > img.height
                        val cy = if (land) img.height / 2 else img.height * (2 * i + 1) / 8
                        val cx = if (land) img.width * (2 * i + 1) / 8 else img.width / 2
                        fun at(pl: Int, x: Int, y: Int): Int { val p = img.planes[pl]; return p.buffer.get(y * p.rowStride + x * p.pixelStride).toInt() and 255 }
                        "Y${at(0, cx, cy)}U${at(1, cx / 2, cy / 2)}V${at(2, cx / 2, cy / 2)}"
                    }
                    img.close(); codec.releaseOutputBuffer(o, false); break
                }
                img?.close(); codec.releaseOutputBuffer(o, false)
            }
        }
        codec.stop(); codec.release(); ex.release()
        return "tags(range,std,transfer)=$tags rot=${if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0} $res"
    }

    /** Encodes exact limited-range BT.709 grey (Y126 U128 V128) with MediaCodec, no GL at all. */
    private fun rawGrey(): File {
        val out = File(ctx.cacheDir, "diag-rawgrey.mp4"); out.delete()
        val fmt = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264, 720, 1280).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED); setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val enc = MediaCodec.createEncoderByType(MimeTypes.VIDEO_H264); enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val mux = android.media.MediaMuxer(out.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var frame = 0; val info = MediaCodec.BufferInfo(); var done = false
        val deadline = System.currentTimeMillis() + 30_000
        while (!done && System.currentTimeMillis() < deadline) {
            if (frame <= 30) { val i = enc.dequeueInputBuffer(10_000); if (i >= 0) {
                if (frame == 30) enc.queueInputBuffer(i, 0, 0, frame * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM) else {
                    val img = enc.getInputImage(i)!!
                    img.planes.forEachIndexed { k, pl -> val v = (if (k == 0) 126 else 128).toByte(); val w = if (k == 0) img.width else img.width / 2; val h = if (k == 0) img.height else img.height / 2
                        for (y in 0 until h) for (x in 0 until w) pl.buffer.put(y * pl.rowStride + x * pl.pixelStride, v) }
                    enc.queueInputBuffer(i, 0, img.width * img.height * 3 / 2, frame * 33_333L, 0)
                }
                frame++ } }
            val o = enc.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.outputFormat); mux.start() }
            else if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) mux.writeSampleData(track, enc.getOutputBuffer(o)!!, info)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                enc.releaseOutputBuffer(o, false)
            }
        }
        enc.stop(); enc.release(); mux.stop(); mux.release()
        return out
    }

    @Test fun stillColourPath() {
        runCatching { val f = rawGrey(); log("raw-yuv-grey(Y126U128V128, no GL) rgb ${rgb(f)} | ${yuv(f)}") }.onFailure { log("raw-yuv-grey failed: $it") }

        log("input ${swatches.joinToString(" ") { "${(it shr 16) and 255},${(it shr 8) and 255},${it and 255}" }}; ideal BT.709 limited grey Y126 U128 V128")
        for ((name, orig, pres) in listOf(Triple("bare-default", false, false), Triple("bare-original", true, false), Triple("original+presentation", true, true))) {
            runCatching { val f = export(name, orig, pres); log("$name rgb ${rgb(f)} | ${yuv(f)}") }.onFailure { log("$name failed: $it") }
        }
    }
}
