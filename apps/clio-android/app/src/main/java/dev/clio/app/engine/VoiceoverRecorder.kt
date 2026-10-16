package dev.clio.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File

/** Records narration from the mic to AAC/M4A in app storage (kept with the drafts that reference it). */
class VoiceoverRecorder(private val context: Context) {
    /** A finished take: [durationMs] is the real length of the m4a; [offsetMs] is how long after
     *  start() the first audio frame landed (encoder warm-up), added to the timeline start. */
    data class Take(val file: File, val durationMs: Long, val offsetMs: Long) { val uri: String get() = Uri.fromFile(file).toString() }

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    val isRecording: Boolean get() = recorder != null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (recorder != null || !hasPermission()) return false
        val dir = File(context.filesDir, "voiceover").apply { mkdirs() }
        val f = File(dir, "narracao-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(f.path)
            r.prepare()
            startedAt = SystemClock.elapsedRealtime()
            r.start()
            recorder = r; file = f
            true
        } catch (e: Exception) { r.release(); f.delete(); false }
    }

    /** Stops; returns the take, or null if it failed or was too short. */
    fun stop(): Take? {
        val r = recorder ?: return null
        val f = file
        val wallMs = SystemClock.elapsedRealtime() - startedAt
        recorder = null; file = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val ms = f?.takeIf { ok }?.let(::durationOf) ?: 0L
        if (f == null || ms < MIN_MS) { f?.delete(); return null }
        return Take(f, ms, offset(wallMs, ms))
    }

    private fun durationOf(f: File): Long = runCatching {
        val m = MediaMetadataRetriever()
        try { m.setDataSource(f.path); m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } finally { m.release() }
    }.getOrDefault(0L)

    companion object {
        private const val MIN_MS = 300L
        /** Wall time not covered by audio is the warm-up before the first frame (capped at 1 s). */
        fun offset(wallMs: Long, fileMs: Long): Long = (wallMs - fileMs).coerceIn(0, 1_000)
    }
}
