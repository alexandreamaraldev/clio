package dev.clio.app.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Picks the preview LUT per frame from its presentation time, so the frames right after a cut
 * use the next clip's grade (the UI tick lags the decoder by several frames).
 *
 * ExoPlayer feeds effects renderer-time stamps (`bufferPresentationTimeUs`, no adjustment in
 * Media3 1.4) that run continuously across playlist items: pts = base + playlist source time,
 * where playlist source time sums each item's clipped source span. [base] is learned from the
 * first frame after every seek ([seeked]); a discontinuity without a seek re-anchors on the
 * player position ([approxUs]). Pure logic: unit-tested in PreviewGradesTest.
 */
class GradeSchedule(private val approxUs: () -> Long) {
    /** Item start times in playlist source µs, and each item's baked LUT. */
    @Volatile private var plan: Pair<LongArray, Array<IntArray>> = LongArray(0) to emptyArray()
    @Volatile private var pendingAnchorUs: Long? = null
    private var base = Long.MIN_VALUE
    private var lastPts = Long.MIN_VALUE

    fun publish(startsUs: LongArray, planes: Array<IntArray>) { plan = startsUs to planes }

    /** The player was just seeked to [playlistUs]; the next frame is that position. */
    fun seeked(playlistUs: Long) { pendingAnchorUs = playlistUs }

    @Synchronized fun planeAt(ptsUs: Long, fallback: IntArray): IntArray {
        pendingAnchorUs?.let { base = ptsUs - it; pendingAnchorUs = null }
        if (base == Long.MIN_VALUE || (lastPts != Long.MIN_VALUE && (ptsUs < lastPts - 1_000 || ptsUs > lastPts + 2_000_000))) base = ptsUs - approxUs()
        lastPts = ptsUs
        val (starts, planes) = plan
        if (planes.isEmpty()) return fallback
        val t = ptsUs - base
        var i = starts.indexOfLast { it <= t }
        if (i < 0) i = 0
        return planes.getOrElse(i) { fallback }
    }
}

/**
 * HDR (PQ/HLG) detection: Media3 1.4's ColorLut is SDR-only. [isHdr] opens a MediaExtractor, so
 * call it off the main thread (import / [prefetch]); the UI reads [cached], per URI.
 */
object HdrProbe {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Known result, or null while not probed yet (treated as SDR until [prefetch] finishes). */
    fun cached(source: String): Boolean? = cache[source]
    /** Probes [sources] on Dispatchers.IO; true when any new result arrived. */
    suspend fun prefetch(context: Context, sources: Collection<String>): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var fresh = false
        for (s in sources) if (!cache.containsKey(s)) { isHdr(context, s); fresh = true }
        fresh
    }
    @androidx.annotation.WorkerThread
    fun isHdr(context: Context, source: String): Boolean = cache.getOrPut(source) {
        runCatching {
            val e = MediaExtractor()
            try {
                if (source.startsWith("/")) e.setDataSource(source) else e.setDataSource(context, Uri.parse(source), null)
                (0 until e.trackCount).map { e.getTrackFormat(it) }.any { f ->
                    f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && f.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                        f.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let { it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG }
                }
            } finally { e.release() }
        }.getOrDefault(false)
    }

    /** Matches Exporter: API 29+ tone-maps the whole export to SDR; below that grades are dropped. */
    val PREVIEW_NOTICE: String get() = if (android.os.Build.VERSION.SDK_INT >= 29)
        "Vídeo HDR: filtros e ajustes não aparecem na prévia; na exportação o vídeo é convertido para SDR."
    else "Vídeo HDR: filtros e ajustes não aparecem na prévia e são ignorados na exportação neste aparelho."
}
