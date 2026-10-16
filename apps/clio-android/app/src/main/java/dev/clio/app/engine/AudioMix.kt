package dev.clio.app.engine

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import uniffi.clio_ffi.Clip
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.TrackKind
import kotlin.math.abs
import kotlin.math.tanh

/** Audio lane names shared by the controller and the engine calls. */
object AudioLanes {
    const val MUSIC = "Música"
    const val SFX = "Efeitos"
}

/** One gain rule for preview and export (unit-tested). */
object AudioGain {
    /** A clip is silent when muted or when its sound was detached to an audio clip. */
    fun silent(c: Clip) = c.muted || c.detachedTo != null
    fun at(c: Clip, tMs: Long) = Mix.gain(tMs, c.durationMs.toLong(), c.volume, c.fadeInMs.toLong(), c.fadeOutMs.toLong(), silent(c))
    /** ExoPlayer can only attenuate, so preview caps at unity; export applies the full gain through [limit]. */
    fun preview(g: Float) = g.coerceIn(0f, 1f)
    /** Soft limiter for normalised samples (applied once on the export mix): linear up to the knee, then a tanh shoulder that never exceeds 1. */
    fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE))
        return if (x < 0) -y else y
    }
    private const val KNEE = 0.8f
}

/**
 * Live preview of every audio-track clip (music, detached audio, voiceover, SFX), slaved to the
 * timeline clock. Only clips near the playhead hold an ExoPlayer (codec instances are limited on
 * phones), small drift is absorbed by nudging speed ±2 %, larger drift re-seeks (debounced, never
 * while buffering).
 */
class AudioMix(private val context: Context) {
    private class Voice(var clip: Clip, val player: ExoPlayer) { var lastSeek = 0L }
    private var clips: List<Clip> = emptyList()
    private val voices = HashMap<ULong, Voice>()

    fun clipsOf(s: Snapshot): List<Clip> = s.tracks.filter { it.kind == TrackKind.AUDIO }.flatMap { it.clips }

    fun setClips(next: List<Clip>) {
        clips = next
        val byId = next.associateBy { it.id }
        voices.entries.removeAll { (id, v) ->
            val c = byId[id]
            val stale = c == null || c.source != v.clip.source || c.inMs != v.clip.inMs || c.durationMs != v.clip.durationMs || c.speed != v.clip.speed || c.startMs != v.clip.startMs
            if (stale) v.player.release() else v.clip = c!! // volume/fade/mute edits apply to the live player
            stale
        }
        lastWindow = Long.MIN_VALUE
    }

    private fun voice(c: Clip): Voice = voices.getOrPut(c.id) {
        Voice(c, ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.Builder().setUri(c.source).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.inMs.toLong()).setEndPositionMs(c.inMs.toLong() + Mix.spanMs(c.durationMs.toLong(), c.speed)).build()).build())
            playbackParameters = PlaybackParameters(c.speed)
            prepare()
        })
    }

    private var lastWindow = Long.MIN_VALUE

    /** Keep players for clips around the playhead, release the rest. Clips sounding right now are
     *  always kept (never silently dropped); only upcoming ones are limited to [MAX_PLAYERS]. */
    private fun window(tMs: Long, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastWindow < WINDOW_EVERY_MS) return
        lastWindow = now
        fun active(c: Clip) = tMs >= c.startMs.toLong() && tMs < (c.startMs + c.durationMs).toLong()
        val (live, rest) = clips.partition(::active)
        if (live.size > MAX_PLAYERS) android.util.Log.w("AudioMix", "${live.size} audio clips overlap at ${tMs}ms; preview may hit codec limits")
        val near = live + rest.filter { tMs + AHEAD_MS >= it.startMs.toLong() && tMs - BEHIND_MS < (it.startMs + it.durationMs).toLong() }
            .sortedBy { abs(it.startMs.toLong() - tMs) }.take((MAX_PLAYERS - live.size).coerceAtLeast(0))
        val keep = near.map { it.id }.toSet()
        voices.entries.removeAll { (id, v) -> (id !in keep).also { if (it) v.player.release() } }
        near.forEach { voice(it) }
    }

    /** Per frame while playing (and once on pause). */
    fun tick(tMs: Long, playing: Boolean) {
        window(tMs)
        val now = SystemClock.elapsedRealtime()
        // Several lanes at once: ~-3 dB each so the sum rarely clips (export uses a limiter on the mix).
        val sounding = voices.values.count { val l = tMs - it.clip.startMs.toLong(); l >= 0 && l < it.clip.durationMs.toLong() }
        val lane = if (sounding > 1) LANE_TRIM else 1f
        for (v in voices.values) {
            val c = v.clip
            val p = v.player
            val local = tMs - c.startMs.toLong()
            val inside = local >= 0 && local < c.durationMs.toLong()
            if (!playing || !inside) { if (p.playWhenReady) p.pause(); continue }
            p.volume = AudioGain.preview(AudioGain.at(c, local) * lane)
            val target = Mix.spanMs(local, c.speed)
            if (!p.playWhenReady) { p.seekTo(target); v.lastSeek = now; p.play(); continue }
            if (p.playbackState == Player.STATE_BUFFERING || now - v.lastSeek < DEBOUNCE_MS) continue
            val drift = p.currentPosition - target
            when {
                abs(drift) > SEEK_MS -> { p.seekTo(target); v.lastSeek = now; nudge(p, c.speed, 0) }
                abs(drift) > NUDGE_MS -> nudge(p, c.speed, if (drift > 0) -1 else 1)
                else -> nudge(p, c.speed, 0)
            }
        }
    }

    private fun nudge(p: ExoPlayer, speed: Float, dir: Int) {
        val s = speed * (1f + 0.02f * dir)
        if (p.playbackParameters.speed != s) p.playbackParameters = PlaybackParameters(s)
    }

    fun seek(tMs: Long) {
        window(tMs, force = true)
        for (v in voices.values) {
            v.player.pause()
            v.player.seekTo(Mix.spanMs((tMs - v.clip.startMs.toLong()).coerceIn(0, v.clip.durationMs.toLong()), v.clip.speed))
        }
    }

    /** One-shot preview of a sound (SFX library tap). */
    private var oneShot: ExoPlayer? = null
    fun audition(uri: String) {
        val p = oneShot ?: ExoPlayer.Builder(context).build().also { oneShot = it }
        p.setMediaItem(MediaItem.fromUri(uri)); p.prepare(); p.play()
    }

    fun release() { voices.values.forEach { it.player.release() }; voices.clear(); oneShot?.release(); oneShot = null }

    private companion object {
        const val AHEAD_MS = 3_000L
        const val BEHIND_MS = 500L
        const val MAX_PLAYERS = 6
        const val WINDOW_EVERY_MS = 250L
        const val LANE_TRIM = 0.708f // -3 dB
        const val DEBOUNCE_MS = 400L
        const val SEEK_MS = 250L
        const val NUDGE_MS = 40L
    }
}
