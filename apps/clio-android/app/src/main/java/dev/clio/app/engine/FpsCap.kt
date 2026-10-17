package dev.clio.app.engine

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram

/**
 * Caps the frame rate by timestamp: a frame passes once it reaches the next slot on the target
 * grid. Media3 1.4's FrameDropEffect does not decimate 30 to 24 fps: the default program queues
 * every frame, and the simple one rounds 30/24 down to keep 1 in 1. This one also handles VFR input.
 */
@UnstableApi
/** [smooth]: variable-rate input (speed curves) — never leave a gap the input didn't have (see [Decimator.Smooth]). */
internal class FpsCap(targetFps: Float, private val smooth: Boolean = false) : GlEffect {
    private val stepUs = (1_000_000.0 / targetFps).toLong()

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        private var nextUs = Long.MIN_VALUE
        private val sm = Decimator.Smooth(stepUs)

        override fun queueInputFrame(gl: GlObjectsProvider, tex: GlTextureInfo, ptsUs: Long) {
            if (keep(ptsUs)) super.queueInputFrame(gl, tex, ptsUs)
            else { inputListener.onInputFrameProcessed(tex); inputListener.onReadyToAcceptInputFrame() }
        }
        override fun signalEndOfCurrentInputStream() { super.signalEndOfCurrentInputStream(); nextUs = Long.MIN_VALUE; sm.reset() }
        override fun flush() { super.flush(); nextUs = Long.MIN_VALUE; sm.reset() }

        private fun keep(ptsUs: Long): Boolean = if (smooth) sm.keep(ptsUs) else Decimator.keep(ptsUs, nextUs, stepUs).also { if (it.first) nextUs = it.second }.first
    }

    companion object {
        /** Sources up to 5% above the target (e.g. VFR ~30 exported at 30) are left alone. */
        /** [speed] multiplies the delivered rate: 30 fps at 2x arrives as 60 fps of output time. */
        fun needed(sourceFps: Float?, targetFps: Float, speed: Float = 1f): Boolean = sourceFps == null || sourceFps * speed > targetFps * 1.05f

        /** Average frame rate over ~2 s of real sample times from [fromUs] (the trim in-point) (VFR-safe), or null. */
        fun sourceFps(context: Context, uri: android.net.Uri, fromUs: Long = 0): Float? = runCatching {
            val e = android.media.MediaExtractor()
            try {
                e.setDataSource(context, uri, null)
                val t = (0 until e.trackCount).first { e.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                e.selectTrack(t)
                if (fromUs > 0) e.seekTo(fromUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var n = 0; var first = -1L; var last = -1L
                while (n < 120) {
                    val ts = e.sampleTime; if (ts < 0) break
                    if (first < 0) first = ts; last = maxOf(last, ts); n++
                    if (last - first > 2_000_000) break
                    e.advance()
                }
                if (n > 1 && last > first) (n - 1) * 1_000_000f / (last - first) else null
            } finally { e.release() }
        }.getOrNull()
    }

    internal object Decimator {
        /**
         * For variable-rate input (a speed ramp crosses the target rate): keep the frame nearest each
         * slot, and also keep a frame whenever dropping it would leave a gap > 1.4 steps (the fixed
         * grid beats against inputs just above the target and drops every few frames).
         */
        class Smooth(private val stepUs: Long) {
            private var slot = Long.MIN_VALUE; private var last = Long.MIN_VALUE; private var prev = Long.MIN_VALUE
            fun reset() { slot = Long.MIN_VALUE; last = Long.MIN_VALUE; prev = Long.MIN_VALUE }
            fun keep(pts: Long): Boolean {
                if (slot == Long.MIN_VALUE) { slot = pts + stepUs; last = pts; prev = pts; return true }
                val s = (pts - prev).coerceAtLeast(1); prev = pts
                val keep = pts >= slot - s / 2 || (pts - last) + s > stepUs * 7 / 5
                if (keep) {
                    slot = maxOf(slot + stepUs, pts + stepUs / 2)
                    while (slot - s / 2 <= pts) slot += stepUs
                    last = pts
                }
                return keep
            }
        }

        /**
         * (keep, next slot). Slots are [stepUs] apart; a frame within a quarter step of
         * the next slot is kept, so jitter doesn't drop on-grid frames (half a step would
         * let 60 -> 30 keep neighbouring frames and stutter).
         */
        fun keep(ptsUs: Long, nextUs: Long, stepUs: Long): Pair<Boolean, Long> {
            if (nextUs == Long.MIN_VALUE) return true to ptsUs + stepUs
            val tol = stepUs / 4
            if (ptsUs < nextUs - tol) return false to nextUs
            // Fixed grid (sources near the target are skipped by [needed], so drift doesn't matter);
            // a gap in the source jumps the grid forward.
            var n = nextUs + stepUs
            while (n - tol <= ptsUs) n += stepUs
            return true to n
        }
    }
}
