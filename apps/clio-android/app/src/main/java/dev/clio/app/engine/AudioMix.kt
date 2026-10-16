package dev.clio.app.engine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import uniffi.clio_ffi.Clip
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.TrackKind
import kotlin.math.abs

/**
 * Live preview of every audio-track clip (music, detached audio, voiceover, SFX). Each clip gets its
 * own ExoPlayer clipped to its source range and slaved to the timeline clock: it plays only while the
 * playhead is inside the clip, re-seeks on drift, and follows the clip's gain/fade envelope.
 */
class AudioMix(private val context: Context) {
    private class Voice(val clip: Clip, val player: ExoPlayer)
    private var voices: List<Voice> = emptyList()

    /** Audio clips of a snapshot, all lanes. */
    fun clipsOf(s: Snapshot): List<Clip> = s.tracks.filter { it.kind == TrackKind.AUDIO }.flatMap { it.clips }

    /** Reconciles players with the timeline; players are reused while the clip's source range is unchanged. */
    fun setClips(clips: List<Clip>) {
        val old = voices.associateBy { it.clip.id }
        val next = clips.map { c ->
            val o = old[c.id]
            if (o != null && o.clip.source == c.source && o.clip.inMs == c.inMs && o.clip.durationMs == c.durationMs && o.clip.speed == c.speed) Voice(c, o.player)
            else Voice(c, ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.Builder().setUri(c.source).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.inMs.toLong()).setEndPositionMs(c.inMs.toLong() + Mix.spanMs(c.durationMs.toLong(), c.speed)).build()).build())
                playbackParameters = PlaybackParameters(c.speed)
                prepare()
            })
        }
        val kept = next.map { it.player }.toSet()
        voices.forEach { if (it.player !in kept) it.player.release() }
        voices = next
    }

    /** Per frame while playing (and once on pause): start/stop/seek each voice and apply its gain. */
    fun tick(tMs: Long, playing: Boolean) {
        for (v in voices) {
            val c = v.clip
            val local = tMs - c.startMs.toLong()
            val inside = local >= 0 && local < c.durationMs.toLong()
            if (!playing || !inside) { if (v.player.playWhenReady) v.player.pause(); continue }
            val target = Mix.spanMs(local, c.speed)
            if (!v.player.playWhenReady) { v.player.seekTo(target); v.player.play() }
            else if (abs(v.player.currentPosition - target) > DRIFT_MS) v.player.seekTo(target)
            v.player.volume = Mix.gain(local, c.durationMs.toLong(), c.volume, c.fadeInMs.toLong(), c.fadeOutMs.toLong(), c.muted).coerceAtMost(1f)
        }
    }

    /** Scrub: park every voice at the matching source position. */
    fun seek(tMs: Long) {
        for (v in voices) {
            v.player.pause()
            val local = (tMs - v.clip.startMs.toLong()).coerceIn(0, v.clip.durationMs.toLong())
            v.player.seekTo(Mix.spanMs(local, v.clip.speed))
        }
    }

    fun release() { voices.forEach { it.player.release() }; voices = emptyList() }

    private companion object { const val DRIFT_MS = 200L }
}
