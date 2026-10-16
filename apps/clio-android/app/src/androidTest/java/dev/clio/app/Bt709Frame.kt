package dev.clio.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Reads an exported frame as RGB by decoding Y'CbCr ourselves with the BT.709 limited-range matrix
 * (what Clio's exports are tagged with). MediaMetadataRetriever's own conversion on the CI emulator
 * lifts grey: an exact Y126/U128/V128 stream (encoded with MediaCodec, no GL) reads back as
 * 132,130,132 (see ExportColorDiagE2ETest), so it can't be used for ±3 colour checks.
 * Rotation metadata is applied, so (x, y) are in display coordinates.
 */
class Bt709Frame private constructor(val width: Int, val height: Int, private val y: ByteArray, private val u: ByteArray, private val v: ByteArray, private val cw: Int, private val rot: Int, private val codedW: Int, private val codedH: Int) {
    private fun toCoded(x: Int, yy: Int): Pair<Int, Int> = when (rot) {
        90 -> yy to (codedH - 1 - x)
        180 -> (codedW - 1 - x) to (codedH - 1 - yy)
        270 -> (codedW - 1 - yy) to x
        else -> x to yy
    }
    fun rgb(x: Int, yy: Int): IntArray {
        val (cx, cy) = toCoded(x, yy)
        val Y = (y[cy * codedW + cx].toInt() and 255) - 16.0
        val Cb = (u[(cy / 2) * cw + cx / 2].toInt() and 255) - 128.0
        val Cr = (v[(cy / 2) * cw + cx / 2].toInt() and 255) - 128.0
        val k = 255.0 / 219.0; val c = 255.0 / 224.0
        val r = k * Y + c * 1.5748 * Cr; val g = k * Y - c * 0.1873 * Cb - c * 0.4681 * Cr; val b = k * Y + c * 1.8556 * Cb
        return intArrayOf(r, g, b).map { Math.round(it).toInt().coerceIn(0, 255) }.toIntArray()
    }
    /** Mean RGB of a (2r)² patch centred on (x, y). */
    fun patch(x: Int, yy: Int, r: Int = 24): IntArray {
        val s = IntArray(3); var n = 0
        for (j in yy - r until yy + r) for (i in x - r until x + r) { val p = rgb(i, j); for (k in 0..2) s[k] += p[k]; n++ }
        return IntArray(3) { s[it] / n }
    }

    companion object {
        /** First decoded frame at or after [timeUs]. */
        fun at(ctx: Context, uri: Uri, timeUs: Long): Bt709Frame {
            val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            ex.selectTrack(t); val fmt = ex.getTrackFormat(t)
            val rot = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            ex.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            try {
                val info = MediaCodec.BufferInfo(); var eos = false
                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline) {
                    if (!eos) { val i = codec.dequeueInputBuffer(10_000); if (i >= 0) { val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() } } }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o < 0) continue
                    val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val img = codec.getOutputImage(o)
                    if (img != null && (info.presentationTimeUs >= timeUs || last)) {
                        val w = img.width; val h = img.height; val cw = w / 2
                        fun plane(k: Int, pw: Int, ph: Int) = ByteArray(pw * ph).also { a -> val p = img.planes[k]
                            for (yy in 0 until ph) for (xx in 0 until pw) a[yy * pw + xx] = p.buffer.get(yy * p.rowStride + xx * p.pixelStride) }
                        val f = Bt709Frame(if (rot % 180 == 0) w else h, if (rot % 180 == 0) h else w, plane(0, w, h), plane(1, cw, h / 2), plane(2, cw, h / 2), cw, rot, w, h)
                        img.close(); codec.releaseOutputBuffer(o, false); return f
                    }
                    img?.close(); codec.releaseOutputBuffer(o, false)
                    if (last) break
                }
                error("no frame at $timeUs")
            } finally { codec.stop(); codec.release(); ex.release() }
        }
    }
}
