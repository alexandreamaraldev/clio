package dev.clio.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaInfo(val uri: String, val name: String, val durationMs: Long, val width: Int, val height: Int)

/** Real media probing + thumbnails (MediaMetadataRetriever), cached in memory. */
const val IMAGE_DURATION_MS = 3000L

class Media(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun probe(uri: Uri): MediaInfo? = withContext(Dispatchers.IO) {
        if (MediaKind.isImage(context, uri.toString())) return@withContext runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            MediaInfo(uri.toString(), displayName(uri), IMAGE_DURATION_MS, o.outWidth, o.outHeight)
        }.getOrNull()
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                val (vw, vh) = if (rot % 180 != 0) h to w else w to h
                MediaInfo(uri.toString(), displayName(uri), d, vw, vh)
            } finally { r.release() }
        }.getOrNull()
    }

    /** Audio file length (music track). */
    suspend fun audioDurationMs(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(context, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } finally { r.release() }
        }.getOrDefault(0L)
    }

    fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.')?.takeUnless { it.isBlank() || it.all(Char::isDigit) }
            ?: if (MediaKind.isImage(context, uri.toString())) "Foto" else "Vídeo"

    /** Frame at [timeMs], scaled so the longer side is [maxPx]. */
    suspend fun thumbnail(uri: String, timeMs: Long, maxPx: Int = 256): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$uri@${timeMs / 250}@$maxPx"
        cache.get(key)?.let { return@withContext it.asImageBitmap() }
        if (MediaKind.isImage(context, uri)) return@withContext runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val b = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
            b?.also { cache.put(key, it) }?.asImageBitmap()
        }.getOrNull()
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(uri))
                val b = if (android.os.Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx)
                else r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                b?.also { cache.put(key, it) }?.asImageBitmap()
            } finally { r.release() }
        }.getOrNull()
    }
}
