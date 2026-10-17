package dev.clio.app.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ColorLut
import uniffi.clio_ffi.ClipGrade
import uniffi.clio_ffi.ColorGrade

/**
 * Phase 4 look. A clip's [ColorGrade] (bundled LUT preset × intensity + adjustments) is baked
 * on the CPU into ONE 3D LUT, and that LUT is the only colour effect in both the ExoPlayer preview
 * and the Transformer export ([GradeLut]), so they match. Thumbnails and export layer bitmaps use
 * the same baked LUT on the CPU ([Grades.applyTo]).
 */
object Grades {
    /** Baked LUT size (33³ is the usual quality/size trade-off; 144 KB as ARGB). */
    const val N = 33

    val NEUTRAL = ColorGrade("", 1f, 0f, 0f, 0f, 0f, 0f, 0f)

    fun isNeutral(g: ColorGrade): Boolean =
        (g.filter.isEmpty() || g.intensity <= 0f) && g.brightness == 0f && g.contrast == 0f && g.saturation == 0f &&
            g.temperature == 0f && g.highlights == 0f && g.shadows == 0f

    fun byClip(list: List<ClipGrade>): Map<Long, ColorGrade> = list.associate { it.clipId.toLong() to it.grade }

    /** A parsed Adobe .cube 3D LUT; [rgb] is r-fastest, as in the file. */
    class Cube(val n: Int, val rgb: FloatArray) {
        /** Trilinear lookup; inputs and outputs 0..1, written into [out]. */
        fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
            val m = n - 1
            val fr = r.coerceIn(0f, 1f) * m; val fg = g.coerceIn(0f, 1f) * m; val fb = b.coerceIn(0f, 1f) * m
            val r0 = fr.toInt().coerceAtMost(m - 1); val g0 = fg.toInt().coerceAtMost(m - 1); val b0 = fb.toInt().coerceAtMost(m - 1)
            val dr = fr - r0; val dg = fg - g0; val db = fb - b0
            for (ch in 0..2) {
                fun v(ri: Int, gi: Int, bi: Int) = rgb[((bi * n + gi) * n + ri) * 3 + ch]
                val c00 = v(r0, g0, b0) * (1 - dr) + v(r0 + 1, g0, b0) * dr
                val c10 = v(r0, g0 + 1, b0) * (1 - dr) + v(r0 + 1, g0 + 1, b0) * dr
                val c01 = v(r0, g0, b0 + 1) * (1 - dr) + v(r0 + 1, g0, b0 + 1) * dr
                val c11 = v(r0, g0 + 1, b0 + 1) * (1 - dr) + v(r0 + 1, g0 + 1, b0 + 1) * dr
                out[ch] = (c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db
            }
        }

        companion object {
            private val SPACES = Regex("\\s+")
            fun parse(text: String): Cube {
                var n = 0; val vals = ArrayList<Float>()
                text.lineSequence().forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty() || l.startsWith("#") || l.startsWith("TITLE") || l.startsWith("DOMAIN_")) return@forEach
                    if (l.startsWith("LUT_3D_SIZE")) { n = l.substringAfter("LUT_3D_SIZE").trim().toInt(); return@forEach }
                    l.split(SPACES).forEach { vals += it.toFloat() }
                }
                require(n >= 2 && vals.size == n * n * n * 3) { "bad .cube: size $n, ${vals.size} values" }
                return Cube(n, vals.toFloatArray())
            }
        }
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Grade one colour (0..1, in place): filter mixed by intensity, then the adjustments. */
    fun grade(c: FloatArray, g: ColorGrade, filter: Cube?, tmp: FloatArray = FloatArray(3)) {
        if (filter != null && g.intensity > 0f) {
            filter.sample(c[0], c[1], c[2], tmp)
            val k = g.intensity.coerceIn(0f, 1f)
            for (i in 0..2) c[i] += (tmp[i] - c[i]) * k
        }
        // Temperature: amber <-> blue shift.
        c[0] += 0.10f * g.temperature; c[1] += 0.02f * g.temperature; c[2] -= 0.10f * g.temperature
        // Brightness, then contrast about mid-grey.
        val ck = 1f + 0.8f * g.contrast
        for (i in 0..2) c[i] = (c[i] + 0.3f * g.brightness - 0.5f) * ck + 0.5f
        // Highlights / shadows: luma-weighted lift/pull of the top and bottom ranges.
        val l0 = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
        val dl = 0.3f * g.highlights * smooth(0.45f, 1f, l0) + 0.3f * g.shadows * (1f - smooth(0f, 0.55f, l0))
        for (i in 0..2) c[i] += dl
        // Saturation about luma.
        val l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
        val sk = 1f + g.saturation
        for (i in 0..2) c[i] = (l + (c[i] - l) * sk).coerceIn(0f, 1f)
    }

    /** Bakes [g] into an N³ LUT laid out as Media3's plane: index = b + N·(g + N·r), ARGB. */
    fun bake(g: ColorGrade, filter: Cube?, n: Int = N): IntArray {
        val out = IntArray(n * n * n); val c = FloatArray(3); val tmp = FloatArray(3); val m = (n - 1).toFloat()
        for (r in 0 until n) for (gi in 0 until n) for (b in 0 until n) {
            c[0] = r / m; c[1] = gi / m; c[2] = b / m
            grade(c, g, filter, tmp)
            out[b + n * (gi + n * r)] = (0xFF shl 24) or ((c[0] * 255 + .5f).toInt() shl 16) or ((c[1] * 255 + .5f).toInt() shl 8) or (c[2] * 255 + .5f).toInt()
        }
        return out
    }

    /** Applies a baked plane to ARGB pixels in place (nearest grid point per channel blend: trilinear). */
    fun applyTo(px: IntArray, plane: IntArray, n: Int = N) {
        val m = n - 1
        fun ch(v: Int, s: Int) = (v shr s) and 0xFF
        for (i in px.indices) {
            val p = px[i]
            val fr = ch(p, 16) * m / 255f; val fg = ch(p, 8) * m / 255f; val fb = ch(p, 0) * m / 255f
            val r0 = fr.toInt().coerceAtMost(m - 1); val g0 = fg.toInt().coerceAtMost(m - 1); val b0 = fb.toInt().coerceAtMost(m - 1)
            val dr = fr - r0; val dg = fg - g0; val db = fb - b0
            var o = p and (0xFF shl 24)
            for (s in intArrayOf(16, 8, 0)) {
                fun v(r: Int, g: Int, b: Int) = ch(plane[b + n * (g + n * r)], s).toFloat()
                val c00 = v(r0, g0, b0) * (1 - dr) + v(r0 + 1, g0, b0) * dr
                val c10 = v(r0, g0 + 1, b0) * (1 - dr) + v(r0 + 1, g0 + 1, b0) * dr
                val c01 = v(r0, g0, b0 + 1) * (1 - dr) + v(r0 + 1, g0, b0 + 1) * dr
                val c11 = v(r0, g0 + 1, b0 + 1) * (1 - dr) + v(r0 + 1, g0 + 1, b0 + 1) * dr
                val x = ((c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db + .5f).toInt().coerceIn(0, 255)
                o = o or (x shl s)
            }
            px[i] = o
        }
    }

    /** Returns a graded copy of [src] (ARGB_8888). */
    fun applyTo(src: Bitmap, plane: IntArray): Bitmap {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        applyTo(px, plane)
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return b
    }

    @Volatile private var shared: Library? = null
    /** One process-wide library: the controller, the panels and export share cubes and baked LUTs. */
    fun library(context: Context): Library = shared ?: synchronized(this) { shared ?: Library(context.applicationContext).also { shared = it } }

    /** Loads bundled presets from assets/luts and memoises baked LUTs per grade. */
    class Library(private val context: Context) {
        private val cubes = HashMap<String, Cube?>()
        private val baked = object : LinkedHashMap<ColorGrade, IntArray>(16, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<ColorGrade, IntArray>) = size > 24
        }
        @Synchronized fun cube(id: String): Cube? = if (id.isEmpty()) null else cubes.getOrPut(id) {
            runCatching { context.assets.open("luts/$id.cube").bufferedReader().use { Cube.parse(it.readText()) } }
                .onFailure { android.util.Log.w("Clio", "missing LUT $id", it) }.getOrNull()
        }
        @Synchronized fun plane(g: ColorGrade): IntArray = baked.getOrPut(g) { bake(g, cube(g.filter)) }
        val identity: IntArray by lazy { bake(NEUTRAL, null) }
    }
}

/**
 * The one colour effect for preview and export: a 3D LUT whose content comes from [plane]
 * (called on the GL thread per frame). The texture is re-uploaded only when the array changes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GradeLut(private val plane: (presentationTimeUs: Long) -> IntArray) : ColorLut {
    private var uploaded: IntArray? = null
    private var tex = -1

    override fun getLutTextureId(presentationTimeUs: Long): Int {
        val p = plane(presentationTimeUs)
        if (p !== uploaded || tex < 0) {
            if (tex >= 0) runCatching { GlUtil.deleteTexture(tex) }
            val n = Grades.N
            val bmp = Bitmap.createBitmap(p, n, n * n, Bitmap.Config.ARGB_8888)
            tex = GlUtil.createTexture(bmp); bmp.recycle()
            uploaded = p
        }
        return tex
    }

    override fun getLength(presentationTimeUs: Long): Int = Grades.N

    override fun release() { if (tex >= 0) GlUtil.deleteTexture(tex); tex = -1; uploaded = null }
}
