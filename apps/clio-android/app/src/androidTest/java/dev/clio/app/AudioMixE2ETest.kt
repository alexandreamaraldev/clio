package dev.clio.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.AudioLanes
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.Exporter
import dev.clio.app.engine.SfxLibrary
import dev.clio.app.ui.screens.CaptionStyle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.TrackKind
import java.io.File
import kotlin.math.abs

/**
 * Device export with overlapping audio lanes: music + narration + SFX + detached audio, all sounding
 * at once (exercises AudioExport sequences, silence gaps, per-clip gain and the mix limiter).
 */
@RunWith(AndroidJUnit4::class)
class AudioMixE2ETest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Test fun overlappingLanesExport() {
        val dst = File(ctx.cacheDir, "audio-e2e.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        val src = Uri.fromFile(dst).toString()

        val p = ClioProject("audio", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        val c = p.appendClip(v, "c", src, 6000u)
        p.setClipSourceInfo(c, 6000u, 1280u, 720u)
        p.detachAudio(c)                                                   // Áudio lane, 0–6 s
        p.setLaneClip(AudioLanes.MUSIC, "música", src, 0u, 6000u)          // Música, 0–6 s
        p.setClipAudio(p.tracks().first { it.name == AudioLanes.MUSIC }.clips[0].id, 1.8f, 500u, 500u, false)
        p.addVoiceover(src, 1000u, 3000u)                                  // Narração, 1–4 s
        val sfx = SfxLibrary.all.first()
        val sfxUri = Uri.fromFile(SfxLibrary.file(ctx, sfx)).toString()
        p.addAudioClip(AudioLanes.SFX, sfx.label, sfxUri, 1500u, sfx.durationMs.toULong())
        p.addAudioClip(AudioLanes.SFX, sfx.label, sfxUri, 1600u, sfx.durationMs.toULong()) // overlaps -> "Efeitos 2"
        val snap = p.snapshot()
        assertTrue(snap.tracks.count { it.kind == TrackKind.AUDIO && it.clips.isNotEmpty() } >= 5)

        val uri = runBlocking { withTimeout(180_000) { Exporter(ctx).export(snap, ExportSpec("audio", 720, 1280, 30), CaptionStyle.Clean) {} } }

        val m = MediaMetadataRetriever(); m.setDataSource(ctx, uri)
        val d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val hasAudio = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO); m.release()
        assertEquals("has audio track", "yes", hasAudio)
        assertTrue("duration $d", abs(d - snap.summary.durationMs.toLong()) <= 350)

        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        ex.selectTrack(track)
        val buf = java.nio.ByteBuffer.allocate(1 shl 16)
        var samples = 0
        while (ex.readSampleData(buf, 0) >= 0) { samples++; ex.advance() }
        ex.release()
        assertTrue("audio samples: $samples", samples > 100) // ~6 s of AAC frames
        android.util.Log.i("ClioQA", "CLIO-QA audio mix: PASS ${d}ms, $samples audio samples")
    }
}
