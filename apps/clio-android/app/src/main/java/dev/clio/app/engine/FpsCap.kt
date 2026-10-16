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

    internal object Decimator {
        /** (keep, next slot). A 1 ms tolerance stops timestamp jitter from dropping on-grid frames. */
        fun keep(ptsUs: Long, nextUs: Long, stepUs: Long): Pair<Boolean, Long> {
            if (nextUs == Long.MIN_VALUE) return true to ptsUs + stepUs
            if (ptsUs + 1_000 < nextUs) return false to nextUs
            var n = nextUs + stepUs
            while (n <= ptsUs) n += stepUs
            return true to n
        }
    }
}
