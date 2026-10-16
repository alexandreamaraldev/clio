package dev.clio.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-device speech models: downloaded on first use (not bundled) from the pinned catalogue in
 * the engine (URL + SHA-256 + size per file), verified, then moved into place.
 */
class ModelManager(context: Context) {
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun installed(id: String) = runCatching { uniffi.clio_ffi.speechModelInstalled(modelsDir.path, id) }.getOrDefault(false)

    suspend fun ensure(id: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val m = uniffi.clio_ffi.speechModels().first { it.id == id }
        val dir = File(modelsDir, id).apply { mkdirs() }
        val total = m.sizeBytes.toLong().coerceAtLeast(1)
        var done = 0L
        for (f in m.files) {
            val dst = File(dir, f.name)
            if (dst.length() == f.size.toLong()) { done += f.size.toLong(); continue }
            val part = File(dir, f.name + ".part")
            val md = MessageDigest.getInstance("SHA-256")
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout = 15_000; readTimeout = 30_000 }
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        onProgress(done.toFloat() / total)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != f.sha256) { part.delete(); error("checksum mismatch for ${f.name}") }
            part.renameTo(dst)
        }
        onProgress(1f)
    }
}
