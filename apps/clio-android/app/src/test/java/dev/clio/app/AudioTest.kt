package dev.clio.app

import dev.clio.app.engine.AudioExport
import dev.clio.app.engine.AudioGain
import dev.clio.app.engine.VoiceoverRecorder
import dev.clio.app.engine.Waveforms
import dev.clio.app.engine.SfxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudioTest {
    @Test fun silenceWavHasExactLength() {
        val dir = Files.createTempDirectory("sil").toFile()
        val f = AudioExport.silenceWav(dir, 500)
        assertEquals(44L + 44_100 / 2 * 4, f.length())
        assertEquals("RIFF", String(f.readBytes().copyOfRange(0, 4)))
        dir.deleteRecursively()
    }

    @Test fun sfxAreBundledAndAttributed() {
        val dir = listOf(File("src/main/assets/sfx"), File("app/src/main/assets/sfx")).first { it.exists() }
        SfxLibrary.all.forEach {
            assertTrue(it.id, File(dir, "${it.id}.ogg").length() > 0)
            assertTrue(it.id, File(dir, "${it.id}.ogg.attribution").readText().contains("CC0-1.0"))
        }
    }

    @Test fun silenceCacheRewritesTruncatedFile() {
        val dir = Files.createTempDirectory("sil").toFile()
        File(dir, "silence-100.wav").writeBytes(ByteArray(10))
        assertEquals(44L + 4410 * 4, AudioExport.silenceWav(dir, 100).length())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        dir.deleteRecursively()
    }

    @Test fun softLimiter() {
        assertEquals(0.5f, AudioGain.limit(0.5f), 0f)
        assertEquals(-0.8f, AudioGain.limit(-0.8f), 1e-6f)
        for (x in listOf(0.9f, 1.5f, 2f, 10f)) { val y = AudioGain.limit(x); assertTrue("$x", y in 0.8f..1f); assertEquals(-y, AudioGain.limit(-x), 0f) }
        assertTrue(AudioGain.limit(1.2f) < AudioGain.limit(1.8f))
        assertEquals(1f, AudioGain.preview(1.7f), 0f)
    }

    @Test fun voiceoverOffset() {
        assertEquals(120L, VoiceoverRecorder.offset(5_000, 5_120))
        assertEquals(0L, VoiceoverRecorder.offset(5_000, 4_900))
        assertEquals(1_000L, VoiceoverRecorder.offset(0, 9_000))
    }

    @Test fun peaksDiskCacheRoundTrip() {
        val dir = Files.createTempDirectory("pk").toFile()
        val f = File(dir, "a.peaks")
        Waveforms.writePeaks(dir, f, floatArrayOf(0.1f, 0.9f))
        assertTrue(Waveforms.readPeaks(f)!!.contentEquals(floatArrayOf(0.1f, 0.9f)))
        assertEquals(null, Waveforms.readPeaks(File(dir, "missing")))
        f.writeBytes(f.readBytes().copyOf(8)) // truncated
        assertEquals(null, Waveforms.readPeaks(f))
        dir.deleteRecursively()
    }
}
