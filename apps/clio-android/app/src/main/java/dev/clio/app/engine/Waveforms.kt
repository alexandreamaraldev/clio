package dev.clio.app.engine

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * Real waveforms for audio clips: decode (mono 16 kHz, [AudioDecoder]), pre-reduce to 100 peaks/s in
 * Kotlin, then let the engine ([uniffi.clio_ffi.waveformPeaks]) shape one bar per 50 ms. Cached in
 * memory per source range; the timeline reads [peaks] and falls back to a placeholder until ready.
 */
object Waveforms {
    val peaks = mutableStateMapOf<String, List<Float>>()
    private val pending = mutableSetOf<String>()

    fun key(source: String?, inMs: Long, spanMs: Long) = "$source|$inMs|$spanMs"

    fun request(context: Context, scope: CoroutineScope, clips: List<uniffi.clio_ffi.Clip>) {
        for (c in clips) {
            val span = Mix.spanMs(c.durationMs.toLong(), c.speed)
            val k = key(c.source, c.inMs.toLong(), span)
            if (k in peaks || !pending.add(k)) continue
            scope.launch(Dispatchers.IO) {
                val p = runCatching { compute(context, c.source, c.inMs.toLong(), span, c.durationMs.toLong()) }.getOrNull()
                launch(Dispatchers.Main) { pending.remove(k); if (p != null) peaks[k] = p }
            }
        }
    }

    private fun compute(context: Context, source: String, inMs: Long, spanMs: Long, durMs: Long): List<Float>? {
        val f = File.createTempFile("wave", ".f32", context.cacheDir)
        try {
            val n = AudioDecoder.decodeToFile(context, Uri.parse(source), f, inMs, spanMs)
            if (n <= 0) return null
            val coarse = ArrayList<Float>((n / 160 + 1).toInt())
            RandomAccessFile(f, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var m = 0f; var i = 0
                while (buf.hasRemaining()) { m = maxOf(m, abs(buf.get())); if (++i == 160) { coarse += m; m = 0f; i = 0 } }
                if (i > 0) coarse += m
            }
            return uniffi.clio_ffi.waveformPeaks(coarse, (durMs / 50).coerceIn(8, 4000).toUInt())
        } finally { f.delete() }
    }
}
