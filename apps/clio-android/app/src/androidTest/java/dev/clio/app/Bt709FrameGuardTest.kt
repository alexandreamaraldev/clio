package dev.clio.app

import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Guard for our own measuring tool: qa/run-device-qa.sh writes raw Y'CbCr planes with known BT.709
 * limited-range values (computed from the RGB targets below with the forward BT.709 matrix), encodes
 * them with ffmpeg tagged bt709/tv, and a full-range (pc) twin. Bt709Frame must read the known RGB
 * back within ±2 and refuse the stream whose tags don't match. The colour patches tell BT.601 from
 * BT.709 (e.g. 200,80,60 reads 190,68,61 with the 601 matrix) and full from limited range (grey
 * 128 would read 126), so a wrong matrix or range in Bt709Frame fails here.
 */
@RunWith(AndroidJUnit4::class)
class Bt709FrameGuardTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    /** 3x2 grid of 416x352 patches at (col*416, row*352) in 1280x720; RGB target -> (Y, Cb, Cr) the script writes. */
    private val patches = listOf(
        intArrayOf(128, 128, 128) to intArrayOf(126, 128, 128),
        intArrayOf(200, 80, 60) to intArrayOf(105, 107, 182),
        intArrayOf(50, 90, 180) to intArrayOf(92, 172, 107),
        intArrayOf(235, 235, 235) to intArrayOf(218, 128, 128),
        intArrayOf(30, 30, 30) to intArrayOf(42, 128, 128),
        intArrayOf(60, 200, 90) to intArrayOf(155, 94, 71),
    )

    private fun fixture(name: String): Uri {
        val dst = File(ctx.cacheDir, name)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/$name"))
            .use { i -> dst.outputStream().use { i.copyTo(it) } }
        assertTrue("fixture missing: qa/run-device-qa.sh pushes /data/local/tmp/$name", dst.length() > 1_000)
        return Uri.fromFile(dst)
    }

    @Test fun readsKnownBt709LimitedValues() {
        val f = Bt709Frame.at(ctx, fixture("clio-bt709-tv.mp4"), 500_000)
        assertEquals("size", 1280 to 720, f.width to f.height)
        assertEquals("matrix tag", MediaFormat.COLOR_STANDARD_BT709, f.colorStandard)
        assertEquals("range tag", MediaFormat.COLOR_RANGE_LIMITED, f.colorRange)
        val fails = mutableListOf<String>()
        patches.forEachIndexed { i, (want, yuv) ->
            val cx = (i % 3) * 416 + 208; val cy = (i / 3) * 352 + 176
            val got = f.patch(cx, cy, 24)
            val err = (0..2).maxOf { kotlin.math.abs(got[it] - want[it]) }
            val line = "CLIO-QA bt709-guard patch$i yuv=${yuv.joinToString(",")}: got ${got.joinToString(",")} want ${want.joinToString(",")} err $err"
            android.util.Log.i("ClioQA", line)
            if (err > 2) fails += line
        }
        assertTrue("Bt709Frame misreads known BT.709 limited values:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun refusesStreamNotTaggedLimited() {
        val uri = fixture("clio-bt709-pc.mp4")
        val tags = Bt709Frame.at(ctx, uri, 500_000, requireTags = false)
        android.util.Log.i("ClioQA", "CLIO-QA bt709-guard pc twin tags: standard=${tags.colorStandard} range=${tags.colorRange}")
        assertEquals("pc twin must carry a full-range tag (fixture check)", MediaFormat.COLOR_RANGE_FULL, tags.colorRange)
        try { Bt709Frame.at(ctx, uri, 500_000); fail("Bt709Frame decoded a full-range-tagged stream as limited") }
        catch (e: IllegalStateException) { assertTrue(e.message!!, e.message!!.contains("limited range")) }
    }
}
