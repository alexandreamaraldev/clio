package dev.clio.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clio.app.engine.ExportSpec
import dev.clio.app.engine.Exporter
import dev.clio.app.engine.StyledText
import dev.clio.app.ui.screens.CaptionStyle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.clio_ffi.Caption
import uniffi.clio_ffi.CaptionWord
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.TextAnim
import uniffi.clio_ffi.TrackKind
import java.io.File
import kotlin.math.abs

/**
 * Styled text + word-by-word captions through the real Media3 export. Fixtures are generated here:
 * a flat grey still (so text pixels are unambiguous) and the CI test video (for audio/fps).
 */
@RunWith(AndroidJUnit4::class)
class TextCaptionsExportE2ETest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun log(s: String) { println(s); android.util.Log.i("ClioQA", s) }

    private fun greyStill(): String {
        val f = File(ctx.cacheDir, "grey.png")
        Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(64, 64, 64)) }
            .let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return Uri.fromFile(f).toString()
    }

    private fun testVideo(): String {
        val dst = File(ctx.cacheDir, "text-e2e.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/clio-test.mp4"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(dst).toString()
    }

    private fun w(s: Long, e: Long, t: String) = CaptionWord(s.toULong(), e.toULong(), t)
    private val words = listOf(w(0, 800, "Olá,"), w(800, 1600, "atenção"), w(1600, 3000, "você!"))
    private val caption = Caption(0uL, 3000uL, "Olá, atenção você!", words)

    private fun ClioProject.styledText(startMs: Long, durMs: Long, bg: Long, anim: TextAnim) {
        val t = addTrack(TrackKind.TEXT, "T")
        val id = insertClip(t, "Ação já", "", startMs.toULong(), durMs.toULong())
        setTextStyle(id, StyledText.defaultStyle.copy(background = bg.toUInt(), size = 0.08f, animIn = anim, animOut = TextAnim.NONE, shadow = false))
    }

    private fun export(p: ClioProject, style: CaptionStyle, label: String): Pair<Uri, Long> {
        val snap = p.snapshot()
        val uri = runBlocking { withTimeout(180_000) { Exporter(ctx).export(snap, ExportSpec(label, 720, 1280, 30), style) {} } }
        return uri to snap.summary.durationMs.toLong()
    }

    /** Fraction of pixels in rows [y0, y1) that are near [rgb]. */
    private fun share(b: Bitmap, rgb: Int, y0: Int, y1: Int, tol: Int = 40): Float {
        var n = 0; var hit = 0
        for (y in y0 until y1 step 4) for (x in 0 until b.width step 4) { n++; if (ExportProbe.near(b, x, y, rgb, tol)) hit++ }
        return hit.toFloat() / n
    }

    @Test fun styledTextAndHighlightCaptionsArePainted() {
        val p = ClioProject("text", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        p.appendClip(v, "grey", greyStill(), 3000u)
        p.styledText(0, 3000, 0xFF00C800, TextAnim.NONE)
        p.setCaptions(listOf(caption))
        val (uri, expected) = export(p, CaptionStyle.Highlight, "text-highlight")
        val info = ExportProbe.info(ctx, uri)
        log("CLIO-QA text-e2e highlight: $info")
        assertEquals(720 to 1280, info.width to info.height)
        assertTrue("duration ${info.durationMs} vs $expected", abs(info.durationMs - expected) <= 350)
        assertEquals("fps", 30f, info.fps, 1.5f)

        val accent = 0xF2683C; val green = 0x00C800
        for ((at, word) in listOf(400L to "Olá,", 1200L to "atenção", 2400L to "você!")) {
            val f = ExportProbe.frame(ctx, uri, at)
            File(ctx.getExternalFilesDir(null), "text-$at.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val h = f.height
            val greenMid = share(f, green, h * 3 / 8, h * 5 / 8)
            val accentLow = share(f, accent, h * 3 / 4, h)
            val accentTop = share(f, accent, 0, h / 2)
            log("CLIO-QA text-e2e frame ${at}ms ($word): green(mid)=$greenMid accent(low)=$accentLow accent(top)=$accentTop")
            assertTrue("styled text box missing at $at ms: $greenMid", greenMid > 0.01f)
            assertTrue("active-word pill missing at $at ms: $accentLow", accentLow > 0.002f)
            assertTrue("caption pill drawn outside the caption line at $at ms", accentTop < 0.0005f)
        }
    }

    @Test fun videoWithAnimatedTextAndKaraokeKeepsStreams() {
        val p = ClioProject("text-video", 1080u, 1920u)
        val v = p.addTrack(TrackKind.VIDEO, "V")
        val c = p.appendClip(v, "c", testVideo(), 6000u); p.setClipSourceInfo(c, 6000u, 1280u, 720u)
        p.styledText(500, 2500, 0x99000000, TextAnim.POP)
        p.setCaptions(listOf(caption, Caption(3200uL, 5500uL, "coração português", listOf(w(3200, 4200, "coração"), w(4200, 5500, "português")))))
        val (uri, expected) = export(p, CaptionStyle.Karaoke, "text-karaoke")
        val info = ExportProbe.info(ctx, uri)
        log("CLIO-QA text-e2e karaoke: $info")
        assertEquals(720 to 1280, info.width to info.height)
        assertTrue("duration ${info.durationMs} vs $expected", abs(info.durationMs - expected) <= 350)
        assertEquals("fps", 30f, info.fps, 1.5f)
        assertTrue("audio track", info.hasAudio)
        assertTrue("audio duration ${info.audioDurationMs}", abs(info.audioDurationMs - info.durationMs) <= 400)
    }
}
