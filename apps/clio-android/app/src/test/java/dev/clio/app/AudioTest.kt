package dev.clio.app

import dev.clio.app.engine.AudioExport
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
}
