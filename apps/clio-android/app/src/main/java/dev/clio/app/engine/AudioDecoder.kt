package dev.clio.app.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the audio of a media range to mono 16 kHz float PCM (Whisper's input) with the platform
 * MediaCodec decoders, downmixing and linearly resampling on the fly, and streams it to [out] as
 * raw f32 little-endian (no boxed arrays cross the FFI). Handles 16-bit and float decoder output.
 * Returns the number of samples written.
 */
object AudioDecoder {
    fun decodeToFile(context: Context, uri: Uri, out: File, startMs: Long = 0, durationMs: Long = Long.MAX_VALUE, onProgress: (Float) -> Unit = {}): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return 0
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val endUs = if (durationMs == Long.MAX_VALUE) Long.MAX_VALUE else (startMs + durationMs) * 1000
            val spanUs = if (durationMs == Long.MAX_VALUE) fmt.getLongOr(MediaFormat.KEY_DURATION, 0) else durationMs * 1000
            if (startMs > 0) ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            c.configure(fmt, null, null, 0)
            c.start()

            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var phase = 0.0
            var prev = 0f
            var written = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
                val ob = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                fun emit(v: Float) { ob.clear(); ob.putFloat(v); os.write(ob.array(), 0, 4); written++ }
                while (!outputDone) {
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (n < 0 || ex.sampleTime > endUs) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                if (spanUs > 0) onProgress(((ex.sampleTime - startMs * 1000).toFloat() / spanUs).coerceIn(0f, 1f))
                                ex.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val keep = info.presentationTimeUs >= startMs * 1000 && info.presentationTimeUs <= endUs
                        if (keep && info.size > 0) {
                            val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val fb = if (floatPcm) buf.asFloatBuffer() else null
                            val sb = if (floatPcm) null else buf.asShortBuffer()
                            val frames = (fb?.remaining() ?: sb!!.remaining()) / channels
                            val step = srcRate / 16_000.0
                            for (fr in 0 until frames) {
                                var acc = 0f
                                for (ch in 0 until channels) {
                                    val k = fr * channels + ch
                                    acc += fb?.get(k) ?: (sb!!.get(k) / 32768f)
                                }
                                val cur = acc / channels
                                while (phase <= 1.0) { emit(prev + (cur - prev) * phase.toFloat()); phase += step }
                                phase -= 1.0
                                prev = cur
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return written
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun MediaFormat.getLongOr(key: String, d: Long) = if (containsKey(key)) getLong(key) else d
}
