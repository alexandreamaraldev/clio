package dev.clio.app

import dev.clio.app.engine.LookPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kotlin preset list == Rust FILTERS, and every preset ships a LUT asset. */
class LookPresetsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "crates/ffi-mobile/src/look.rs").isFile }

    @Test fun matchesEngine() {
        val src = File(root, "crates/ffi-mobile/src/look.rs").readText()
        val block = src.substringAfter("pub const FILTERS").substringBefore("];")
        val rust = Regex("""\("([^"]+)",\s*"([^"]+)"\)""").findAll(block).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(rust, LookPresets.all)
    }

    @Test fun everyPresetHasAnAsset() {
        for ((id, _) in LookPresets.all) assertTrue(id, File(root, "apps/clio-android/app/src/main/assets/luts/$id.cube").isFile)
    }
}
