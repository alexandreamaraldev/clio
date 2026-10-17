package dev.clio.app.engine

import android.content.Context
import android.opengl.GLES30
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor

/**
 * Export retiming of a curved clip onto the output frame grid (replaces SpeedChangeEffect + FpsCap
 * for curved clips). Each input frame is placed at its curve time; every output slot (one per
 * 1/fps, from the clip start to the engine's clip end) shows the input frame nearest to it: fast
 * parts drop frames, slow parts hold (repeat) frames, so there are no pts gaps, and the last frame
 * is held to the engine's duration. The reported duration is the engine's, so later items start
 * exactly where the timeline says even when the video track is shorter than the probed span
 * (e.g. a 4.000 s video in a 4.023 s file).
 */
@UnstableApi
internal class CurveRetime(
    private val map: SpeedMap,
    fps: Float,
) : GlEffect {
    private val stepUs = 1_000_000.0 / fps
    private val durUs = (map.durationMs * 1000).toLong()

    override fun getDurationAfterEffectApplied(durationUs: Long): Long = durUs

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(Planner(stepUs, durUs) { map.timelineAt(it / 1000.0) * 1000 })

    /** Slot bookkeeping, GL-free (unit-tested). Times in µs. */
    internal class Planner(private val stepUs: Double, private val durUs: Long, private val tlOf: (Long) -> Double) {
        private var base = Long.MIN_VALUE
        private var k = 0L
        private var held = Double.NaN
        fun reset() { base = Long.MIN_VALUE; k = 0; held = Double.NaN }
        private fun slot(i: Long) = base + Math.round(i * stepUs)
        private val end get() = base + durUs
        /** A new input frame: returns the output pts at which the previously held frame is shown; the new one becomes held. */
        fun onFrame(pts: Long): List<Long> {
            if (base == Long.MIN_VALUE) { base = pts; held = pts.toDouble(); return emptyList() }
            val t = base + tlOf(pts - base)
            val mid = (held + t) / 2
            val out = ArrayList<Long>()
            while (slot(k) < mid && slot(k) < end) { out += slot(k); k++ }
            held = t
            return out
        }
        /** End of the clip: the held frame fills every remaining slot. */
        fun onEnd(): List<Long> {
            if (base == Long.MIN_VALUE) return emptyList()
            val out = ArrayList<Long>()
            while (slot(k) < end) { out += slot(k); k++ }
            return out
        }
    }

    private class Program(private val plan: Planner) : GlShaderProgram {
        private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
        private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
        private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }
        private var errorExecutor: Executor = Executor { it.run() }
        private class Slot(val tex: GlTextureInfo) { var outstanding = 0 }
        private val pool = ArrayList<Slot>()
        private var held: Slot? = null
        private var wantInput = false
        private var size = 0 to 0

        override fun setInputListener(l: GlShaderProgram.InputListener) { inputListener = l; signalReady() }
        override fun setOutputListener(l: GlShaderProgram.OutputListener) { outputListener = l }
        override fun setErrorListener(e: Executor, l: GlShaderProgram.ErrorListener) { errorExecutor = e; errorListener = l }

        private fun free(): Slot? = pool.firstOrNull { it !== held && it.outstanding == 0 }

        private fun signalReady() {
            if (free() != null || pool.size < POOL) { wantInput = false; inputListener.onReadyToAcceptInputFrame() } else wantInput = true
        }

        private fun emit(s: Slot, times: List<Long>) {
            for (t in times) { s.outstanding++; outputListener.onOutputFrameAvailable(s.tex, t) }
        }

        override fun queueInputFrame(gl: GlObjectsProvider, input: GlTextureInfo, ptsUs: Long) {
            try {
                held?.let { emit(it, plan.onFrame(ptsUs)) } ?: plan.onFrame(ptsUs)
                if (size != input.width to input.height) { // new size: drop idle textures
                    pool.removeAll { s -> (s !== held && s.outstanding == 0).also { if (it) del(s) } }
                    size = input.width to input.height
                }
                val dst = free()?.takeIf { it.tex.width == input.width && it.tex.height == input.height } ?: run {
                    val id = GlUtil.createTexture(input.width, input.height, false)
                    Slot(gl.createBuffersForTexture(id, input.width, input.height)).also { pool += it }
                }
                copy(input, dst.tex)
                held = dst
                inputListener.onInputFrameProcessed(input)
                signalReady()
            } catch (e: Exception) {
                errorExecutor.execute { errorListener.onError(androidx.media3.common.VideoFrameProcessingException.from(e)) }
            }
        }

        private fun copy(src: GlTextureInfo, dst: GlTextureInfo) {
            var srcFbo = src.fboId
            val made = srcFbo == C.INDEX_UNSET
            if (made) srcFbo = GlUtil.createFboForTexture(src.texId)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, dst.fboId)
            GLES30.glBlitFramebuffer(0, 0, src.width, src.height, 0, 0, dst.width, dst.height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (made) GlUtil.deleteFbo(srcFbo)
            GlUtil.checkGlError()
        }

        override fun releaseOutputFrame(tex: GlTextureInfo) {
            pool.firstOrNull { it.tex.texId == tex.texId && it.outstanding > 0 }?.let { it.outstanding-- }
            if (wantInput) signalReady()
        }

        override fun signalEndOfCurrentInputStream() {
            held?.let { emit(it, plan.onEnd()) }
            held = null
            plan.reset()
            outputListener.onCurrentOutputStreamEnded()
            if (wantInput) signalReady()
        }

        override fun flush() {
            plan.reset(); held = null
            pool.forEach { it.outstanding = 0 }
            inputListener.onFlush(); signalReady()
        }

        private fun del(s: Slot) { runCatching { GlUtil.deleteFbo(s.tex.fboId); GlUtil.deleteTexture(s.tex.texId) } }
        override fun release() { pool.forEach(::del); pool.clear(); held = null }

        companion object { const val POOL = 4 }
    }
}
