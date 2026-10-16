package dev.clio.app.engine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import uniffi.clio_ffi.Clip
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.TrackKind
import java.io.DataOutputStream
import java.io.File

/**
 * Export side of [AudioMix]: one Transformer sequence per audio lane. Clips keep their timeline
 * position by padding the gaps with generated silence (Media3 1.4 sequences have no gap API).
 * Everything is cut at the video's end so audio never lengthens the export.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object AudioExport {
    fun sequences(context: Context, snap: Snapshot, totalMs: Long, chain: (Clip, Float) -> List<AudioProcessor>): List<EditedMediaItemSequence> =
        snap.tracks.filter { it.kind == TrackKind.AUDIO }.mapNotNull { t ->
            val items = mutableListOf<EditedMediaItem>()
            var cursor = 0L
            for (c in t.clips.sortedBy { it.startMs }) {
                val start = maxOf(c.startMs.toLong(), cursor)
                if (start >= totalMs) break
                val skip = start - c.startMs.toLong()
                val len = minOf(c.durationMs.toLong() - skip, totalMs - start)
                if (len <= 0) continue
                if (start > cursor) items += silence(context, start - cursor)
                val from = c.inMs.toLong() + Mix.spanMs(skip, c.speed)
                val mi = MediaItem.Builder().setUri(c.source).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(from).setEndPositionMs(from + Mix.spanMs(len, c.speed)).build()).build()
                items += EditedMediaItem.Builder(mi).setRemoveVideo(true).setEffects(Effects(chain(c, c.speed), ImmutableList.of())).build()
                cursor = start + len
            }
            if (items.isEmpty()) null else EditedMediaItemSequence(items)
        }

    private fun silence(context: Context, ms: Long): EditedMediaItem {
        val f = silenceWav(File(context.cacheDir, "silence").apply { mkdirs() }, ms)
        return EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(f))).build()
    }

    /** 44.1 kHz stereo 16-bit silent WAV of [ms] (cached by length). */
    internal fun silenceWav(dir: File, ms: Long): File {
        val f = File(dir, "silence-$ms.wav")
        if (f.exists()) return f
        val frames = RATE * ms / 1000
        val data = (frames * 4).toInt()
        DataOutputStream(f.outputStream().buffered()).use { o ->
            fun le32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v ushr 24) }
            fun le16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
            o.writeBytes("RIFF"); le32(36 + data); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(2); le32(RATE.toInt()); le32(RATE.toInt() * 4); le16(4); le16(16)
            o.writeBytes("data"); le32(data)
            val zeros = ByteArray(8192)
            var left = data
            while (left > 0) { val n = minOf(left, zeros.size); o.write(zeros, 0, n); left -= n }
        }
        return f
    }

    private const val RATE = 44_100L
}
