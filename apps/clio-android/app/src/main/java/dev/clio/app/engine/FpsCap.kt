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
internal class FpsCap(targetFps: Float) : GlEffect {
    private val stepUs = (1_000_000.0 / targetFps).toLong()

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        private var nextUs = Long.MIN_VALUE

        override fun queueInputFrame(gl: GlObjectsProvider, tex: GlTextureInfo, ptsUs: Long) {
            if (keep(ptsUs)) super.queueInputFrame(gl, tex, ptsUs)
            else { inputListener.onInputFrameProcessed(tex); inputListener.onReadyToAcceptInputFrame() }
        }
        override fun signalEndOfCurrentInputStream() { super.signalEndOfCurrentInputStream(); nextUs = Long.MIN_VALUE }
        override fun flush() { super.flush(); nextUs = Long.MIN_VALUE }

        private fun keep(ptsUs: Long): Boolean = Decimator.keep(ptsUs, nextUs, stepUs).also { if (it.first) nextUs = it.second }.first
    }

    companion object {
        /** Sources up to 5% above the target (e.g. VFR ~30 exported at 30) are left alone. */
        fun needed(sourceFps: Float?, targetFps: Float): Boolean = sourceFps == null || sourceFps > targetFps * 1.05f

        /** Average frame rate from the first ~2 s of real sample times (VFR-safe), or null. */
        fun sourceFps(context: Context, uri: android.net.Uri): Float? = runCatching {
            val e = android.media.MediaExtractor()
            try {
                e.setDataSource(context, uri, null)
                val t = (0 until e.trackCount).first { e.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                e.selectTrack(t)
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
         * (keep, next slot). Slots are [stepUs] apart; a frame within half a step of
         * the next slot is kept, so jittery VFR footage at ~the target rate passes untouched.
         */
        fun keep(ptsUs: Long, nextUs: Long, stepUs: Long): Pair<Boolean, Long> {
            if (nextUs == Long.MIN_VALUE) return true to ptsUs + stepUs
            val half = stepUs / 2
            if (ptsUs < nextUs - half) return false to nextUs
            // Late frames pull the grid with them (VFR drift), so a slightly slow source never stutters.
            var n = maxOf(nextUs + stepUs, ptsUs + half)
            while (n - half <= ptsUs) n += stepUs
            return true to n
        }
    }
}
