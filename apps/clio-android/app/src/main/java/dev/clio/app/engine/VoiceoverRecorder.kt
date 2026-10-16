package dev.clio.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File

/** Records narration from the mic to AAC/M4A in app storage (kept with the drafts that reference it). */
class VoiceoverRecorder(private val context: Context) {
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
            r.start()
            recorder = r; file = f; startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) { r.release(); f.delete(); false }
    }

    /** Stops; returns the recording and its length, or null if it failed or was too short. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val f = file
        val ms = SystemClock.elapsedRealtime() - startedAt
        recorder = null; file = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || f == null || ms < MIN_MS) { f?.delete(); return null }
        return f to ms
    }

    fun cancel() { stop()?.first?.delete() }

    private companion object { const val MIN_MS = 300L }
}
