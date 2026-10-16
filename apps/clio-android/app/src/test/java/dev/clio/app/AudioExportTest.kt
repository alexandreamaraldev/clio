package dev.clio.app

import app.cash.paparazzi.Paparazzi
import dev.clio.app.engine.AudioExport
import dev.clio.app.engine.SfxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.clio_ffi.Clip
import uniffi.clio_ffi.ProjectSummary
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.Track
import uniffi.clio_ffi.TrackKind
import java.io.File

/**
 * Multi-lane audio export plan ([AudioExport.sequences]) on synthetic lanes. The Paparazzi rule is
 * only used for a real Android Context/Uri on the JVM; nothing is rendered.
 */
class AudioExportTest {
    @get:Rule val paparazzi = Paparazzi()

    private val rate = 44_100L
    private fun clip(id: Long, start: Long, dur: Long, inMs: Long = 0, speed: Float = 1f) =
        Clip(id.toULong(), "c$id", "file:///a$id.m4a", start.toULong(), dur.toULong(), inMs.toULong(), 0uL, 0u, 0u, speed, 1f, 0uL, 0uL, false, null,
            uniffi.clio_ffi.LayerTransform(0f, 0f, 1f, 0f, 1f), dev.clio.app.engine.Layers.NO_TRANSITION)

    private fun snap(vararg lanes: List<Clip>) = Snapshot(
        ProjectSummary("p", 1080u, 1920u, 10_000uL, lanes.size.toUInt(), 0u),
        lanes.mapIndexed { i, l -> Track((100 + i).toULong(), TrackKind.AUDIO, "L$i", l) } +
            Track(1uL, TrackKind.VIDEO, "V", listOf(clip(1, 0, 10_000))),
        emptyList(), emptyList(), null, false, false, false, false,
    )

    /** One planned item: silence of n frames, or a clip span [fromMs, toMs) in its source. */
    private sealed interface Item { data class Gap(val frames: Long) : Item; data class Span(val uri: String, val fromMs: Long, val toMs: Long) : Item }

    private val silenceDir = java.nio.file.Files.createTempDirectory("clio").resolve("silence").toFile()
    @org.junit.After fun cleanSilence() { silenceDir.parentFile?.deleteRecursively() }

    private fun plan(s: Snapshot, totalMs: Long): List<List<Item>> =
        AudioExport.sequences(paparazzi.context, s, totalMs, silenceDir) { _, _ -> emptyList() }.map { seq ->
            seq.editedMediaItems.map { e ->
                val uri = e.mediaItem.localConfiguration!!.uri
                if (uri.path!!.contains("/silence/")) {
                    val f = File(uri.path!!)
                    assertEquals("silence WAV size", 0L, (f.length() - 44) % 4)
                    Item.Gap((f.length() - 44) / 4)
                } else {
                    assertTrue("audio-only item", e.removeVideo)
                    val cc = e.mediaItem.clippingConfiguration
                    Item.Span(uri.toString(), cc.startPositionMs, cc.endPositionMs)
                }
            }
        }

    private fun frames(ms: Long) = rate * ms / 1000

    /** Sample offset (44.1 kHz) where each clip starts in the rendered lane. */
    private fun starts(items: List<Item>): List<Long> {
        var at = 0L
        val out = mutableListOf<Long>()
        items.forEach { when (it) { is Item.Gap -> at += it.frames; is Item.Span -> { out += at; at += frames(it.toMs - it.fromMs) } } }
        return out
    }
    private fun lengthFrames(items: List<Item>) = items.sumOf { when (it) { is Item.Gap -> it.frames; is Item.Span -> frames(it.toMs - it.fromMs) } }

    @Test fun gapsArePaddedWithExactSilence() {
        val p = plan(snap(listOf(clip(10, 1000, 2000), clip(11, 4500, 1000))), 10_000)
        assertEquals(1, p.size)
        assertEquals(listOf(Item.Gap(frames(1000)), Item.Span("file:///a10.m4a", 0, 2000), Item.Gap(frames(1500)), Item.Span("file:///a11.m4a", 0, 1000)), p[0])
        assertEquals(listOf(44_100L, 198_450L), starts(p[0]))
        assertEquals(frames(5500), lengthFrames(p[0])) // ends at the last clip; no trailing pad
    }

    @Test fun oneSequencePerLaneEachKeepsItsOwnTimeline() {
        val p = plan(snap(listOf(clip(10, 0, 1000)), listOf(clip(20, 250, 500), clip(21, 3000, 700)), emptyList()), 10_000)
        assertEquals("empty lanes are skipped", 2, p.size)
        assertEquals(listOf(0L), starts(p[0]))
        assertEquals(listOf(frames(250), frames(3000)), starts(p[1]))
        assertEquals(frames(3700), lengthFrames(p[1]))
    }

    @Test fun audioIsCutAtTheVideoEnd() {
        val p = plan(snap(listOf(clip(10, 1000, 4000), clip(11, 6000, 1000))), 3000)
        assertEquals(listOf(Item.Gap(frames(1000)), Item.Span("file:///a10.m4a", 0, 2000)), p[0])
        assertEquals("output duration = video duration", frames(3000), lengthFrames(p[0]))
        assertTrue(plan(snap(listOf(clip(10, 3000, 500))), 3000).isEmpty())
    }

    @Test fun inPointAndSpeedMapToSourceRange() {
        val p = plan(snap(listOf(clip(10, 500, 1000, inMs = 2000, speed = 2f))), 10_000)
        assertEquals(listOf(Item.Gap(frames(500)), Item.Span("file:///a10.m4a", 2000, 4000)), p[0])
    }

    @Test fun overlappingClipsOnOneLaneAreTrimmedNotStacked() {
        // Lanes should never overlap (engine invariant), but the plan must still stay on the timeline.
        val p = plan(snap(listOf(clip(10, 0, 2000), clip(11, 1500, 1000))), 10_000)
        assertEquals(listOf(Item.Span("file:///a10.m4a", 0, 2000), Item.Span("file:///a11.m4a", 500, 1000)), p[0])
        assertEquals(listOf(0L, frames(2000)), starts(p[0]))
    }

    @Test fun sfxAttributionIsComplete() {
        val root = listOf(File("../../.."), File("../.."), File(".")).first { File(it, "ATTRIBUTION.md").exists() }
        val attribution = File(root, "ATTRIBUTION.md").readText()
        val dir = File(root, "apps/clio-android/app/src/main/assets/sfx")
        assertTrue(File(dir, "LICENSE-Kenney-CC0.txt").readText().contains("Kenney"))
        val bundled = dir.listFiles()!!.filter { it.name.endsWith(".ogg") }.map { it.name.removeSuffix(".ogg") }.sorted()
        assertEquals("every bundled file is in the library and vice versa", SfxLibrary.all.map { it.id }.sorted(), bundled)
        bundled.forEach { id ->
            assertTrue("$id in ATTRIBUTION.md", attribution.contains("assets/sfx/$id.ogg"))
            val side = File(dir, "$id.ogg.attribution").readText()
            listOf("author:", "source:", "license:      CC0-1.0").forEach { assertTrue("$id sidecar has $it", side.contains(it)) }
        }
        assertTrue(SfxLibrary.credits.any { it.author.contains("Kenney") && it.license.contains("CC0") && it.url.startsWith("https://") })
    }
}
