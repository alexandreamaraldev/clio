package dev.clio.app.engine

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Real waveforms for audio clips: decode (mono 16 kHz f32, [AudioDecoder]) to a temp file and let the
 * engine stream it into one bar per 50 ms ([uniffi.clio_ffi.waveformPeaksFile]; no sample array crosses
 * the FFI). Peaks are cached on disk and in memory, keyed by clip id + a version of its source range.
 * Each key has its own Compose state, so a finished waveform only recomposes its own clip.
 */
object Waveforms {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val states = ConcurrentHashMap<String, MutableState<FloatArray?>>()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun key(id: Long, source: String?, inMs: Long, spanMs: Long) = "$id:${source.hashCode()}:$inMs:$spanMs"
    private fun key(c: uniffi.clio_ffi.Clip) = key(c.id.toLong(), c.source, c.inMs.toLong(), Mix.spanMs(c.durationMs.toLong(), c.speed))

    /** Observable peaks for one clip (null until ready). */
    fun state(key: String): MutableState<FloatArray?> = states.getOrPut(key) { mutableStateOf(null) }

    fun request(context: Context, clips: List<uniffi.clio_ffi.Clip>) {
        val app = context.applicationContext
        for (c in clips) {
            val k = key(c)
            if (state(k).value != null || !pending.add(k)) continue
            scope.launch {
                try {
                    val p = gate.withPermit { runCatching { load(app, k, c) }.getOrNull() }
                    if (p != null) withContext(Dispatchers.Main) { state(k).value = p }
                } finally { pending.remove(k) }
            }
        }
    }

    private fun load(context: Context, k: String, c: uniffi.clio_ffi.Clip): FloatArray? {
        val dir = File(context.cacheDir, "waveforms").apply { mkdirs() }
        val cached = File(dir, sha(k) + ".peaks")
        readPeaks(cached)?.let { return it }
        val span = Mix.spanMs(c.durationMs.toLong(), c.speed)
        val pcm = File.createTempFile("wave", ".f32", context.cacheDir)
        try {
            if (AudioDecoder.decodeToFile(context, Uri.parse(c.source), pcm, c.inMs.toLong(), span) <= 0) return null
            val peaks = uniffi.clio_ffi.waveformPeaksFile(pcm.path, (c.durationMs.toLong() / 50).coerceIn(8, 4000).toUInt()).toFloatArray()
            writePeaks(dir, cached, peaks)
            return peaks
        } finally { pcm.delete() }
    }

    internal fun readPeaks(f: File): FloatArray? = runCatching {
        DataInputStream(f.inputStream().buffered()).use { i -> FloatArray(i.readInt()) { i.readFloat() } }
    }.getOrNull()

    internal fun writePeaks(dir: File, f: File, p: FloatArray) {
        val tmp = File.createTempFile("peaks", ".tmp", dir)
        DataOutputStream(tmp.outputStream().buffered()).use { o -> o.writeInt(p.size); p.forEach(o::writeFloat) }
        if (!tmp.renameTo(f)) tmp.delete()
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
