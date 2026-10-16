package dev.clio.app

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
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Music + voiceover + SFX overlapping over a muted video, exported for real, then the output AAC is
 * decoded and its RMS checked per window: sound where (and only where) the lanes have clips.
 * Tones are synthetic WAVs written at test time.
 *
 *   0 ─ music ─ 2000                       (440 Hz)
 *        1000 ─ voiceover ─ 3000           (660 Hz)
 *             1500 SFX 1800                (asset)
 *                                 3000 ─ silence ─ 6000
 */
@RunWith(AndroidJUnit4::class)
class AudioMixRmsE2ETest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun log(s: String) { println(s); android.util.Log.i("ClioQA", s) }

    private fun tone(name: String, hz: Double, ms: Long, amp: Double = 0.3): String {
        val rate = 44_100; val frames = rate * ms / 1000; val data = frames * 4
        val f = File(ctx.cacheDir, "$name.wav")
        DataOutputStream(f.outputStream().buffered()).use { o ->
            fun le32(v: Long) { for (i in 0 until 4) o.write((v shr (8 * i)).toInt() and 255) }
            fun le16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
            o.writeBytes("RIFF"); le32(36 + data); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(2); le32(rate.toLong()); le32(rate * 4L); le16(4); le16(16)
            o.writeBytes("data"); le32(data)
            for (i in 0 until frames) { val s = (amp * 32767 * sin(2 * PI * hz * i / rate)).toInt(); le16(s); le16(s) }
        }
        return Uri.fromFile(f).toString()
    }

    private fun testVideo(): String {
        val dst = File(ctx.cacheDir, "audio-rms.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(dst).toString()
    }

    @Test fun overlappingLanesSoundOnlyInTheirWindows() {
        val p = ClioProject("rms", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        val c = p.appendClip(v, "c", testVideo(), 6000u); p.setClipSourceInfo(c, 6000u, 1280u, 720u)
        p.setClipAudio(c, 1f, 0u, 0u, true) // the video's own 440 Hz track is muted
        p.setLaneClip(AudioLanes.MUSIC, "música", tone("music", 440.0, 2000), 0u, 2000u)
        p.addVoiceover(tone("vo", 660.0, 2000), 1000u, 2000u)
        val sfx = SfxLibrary.all.first { it.durationMs >= 250 }
        p.addAudioClip(AudioLanes.SFX, sfx.label, Uri.fromFile(SfxLibrary.file(ctx, sfx)).toString(), 1500u, sfx.durationMs.toULong())
        val snap = p.snapshot()

        val t0 = android.os.SystemClock.elapsedRealtime()
        val uri = runBlocking { withTimeout(180_000) { Exporter(ctx).export(snap, ExportSpec("rms", 720, 1280, 30), CaptionStyle.Clean) {} } }
        val wall = android.os.SystemClock.elapsedRealtime() - t0
        val info = ExportProbe.info(ctx, uri)
        log("CLIO-QA audio-rms: $info in ${wall}ms")
        assertTrue("audio track", info.hasAudio)
        assertEquals(720 to 1280, info.width to info.height)
        assertTrue("duration ${info.durationMs}", abs(info.durationMs - 6000) <= 350)
        assertTrue("audio duration ${info.audioDurationMs}", abs(info.audioDurationMs - 6000) <= 400)

        val rms = ExportProbe.audioRms(ctx, uri, 100)
        log("CLIO-QA audio-rms windows(100ms): " + rms.joinToString(" ") { "%.3f".format(it) })
        fun avg(fromMs: Int, toMs: Int) = (fromMs / 100 until toMs / 100).map { rms.getOrElse(it) { 0f } }.average().toFloat()
        val music = avg(200, 900); val both = avg(1100, 1400); val vo = avg(2200, 2900); val tail = avg(3300, 5800)
        log("CLIO-QA audio-rms music=$music music+vo=$both vo=$vo tail=$tail")
        assertTrue("music window silent: $music", music > 0.05f)
        assertTrue("music+voiceover window silent: $both", both > 0.05f)
        assertTrue("voiceover-only window silent: $vo", vo > 0.05f)
        assertTrue("sound after every lane ended (muted video leaking?): $tail", tail < 0.01f)
        assertTrue("decoded ~6 s of audio, got ${rms.size * 100} ms", rms.size in 55..65)
    }
}
