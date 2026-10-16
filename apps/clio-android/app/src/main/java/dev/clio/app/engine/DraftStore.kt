package dev.clio.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class DraftMeta(val id: String, val title: String, val editedAt: Long, val durationMs: Long, val thumbSource: String?, val thumbAtMs: Long)

/** Drafts on disk: one engine draft JSON per project (`filesDir/drafts/<id>.json`), written atomically. */
class DraftStore(context: Context) {
    private val dir = File(context.filesDir, "drafts").apply { mkdirs() }

    companion object { fun newId(): String = UUID.randomUUID().toString() }

    /** Write to a temp file, fsync, then atomically replace — a crash never leaves a torn draft. */
    fun saveBlocking(id: String, json: String) {
        val tmp = File(dir, "$id.json.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), File(dir, "$id.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete(); throw e
        }
    }

    suspend fun save(id: String, json: String) = withContext(Dispatchers.IO) { saveBlocking(id, json) }

    suspend fun load(id: String): String? = withContext(Dispatchers.IO) { File(dir, "$id.json").takeIf { it.exists() }?.readText() }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { File(dir, "$id.json").delete() }

    suspend fun list(): List<DraftMeta> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching {
                val st = JSONObject(f.readText()).getJSONObject("state")
                val tracks = st.getJSONArray("tracks")
                var dur = 0L; var thumb: String? = null; var thumbAt = 0L
                for (i in 0 until tracks.length()) {
                    val clips = tracks.getJSONObject(i).getJSONArray("clips")
                    if (clips.length() == 0) continue
                    val last = clips.getJSONObject(clips.length() - 1)
                    dur = maxOf(dur, last.getLong("start_ms") + last.getLong("duration_ms"))
                    if (thumb == null && tracks.getJSONObject(i).getString("kind") == "Video") {
                        val c0 = clips.getJSONObject(0); thumb = c0.getString("source"); thumbAt = c0.getLong("in_ms") + 500
                    }
                }
                DraftMeta(f.nameWithoutExtension, st.getJSONObject("project").getString("name"), f.lastModified(), dur, thumb, thumbAt)
            }.getOrNull()
        }.sortedByDescending { it.editedAt }
    }
}
