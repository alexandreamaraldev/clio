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
                        val cy = img.height * (2 * i + 1) / 8; val cx = img.width / 2
                        fun at(pl: Int, x: Int, y: Int): Int { val p = img.planes[pl]; return p.buffer.get(y * p.rowStride + x * p.pixelStride).toInt() and 255 }
                        "Y${at(0, cx, cy)}U${at(1, cx / 2, cy / 2)}V${at(2, cx / 2, cy / 2)}"
                    }
                    img.close(); codec.releaseOutputBuffer(o, false); break
                }
                img?.close(); codec.releaseOutputBuffer(o, false)
            }
        }
        codec.stop(); codec.release(); ex.release()
        return "tags(range,std,transfer)=$tags $res"
    }

    @Test fun stillColourPath() {
        log("input ${swatches.joinToString(" ") { "${(it shr 16) and 255},${(it shr 8) and 255},${it and 255}" }}; ideal BT.709 limited grey Y126 U128 V128")
        for ((name, orig, pres) in listOf(Triple("bare-default", false, false), Triple("bare-original", true, false), Triple("original+presentation", true, true))) {
            runCatching { val f = export(name, orig, pres); log("$name rgb ${rgb(f)} | ${yuv(f)}") }.onFailure { log("$name failed: $it") }
        }
    }
}
