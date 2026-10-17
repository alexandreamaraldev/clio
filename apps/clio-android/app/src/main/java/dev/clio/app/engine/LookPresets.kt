package dev.clio.app.engine

/**
 * Bundled filter presets (id → pt-BR name), mirroring `FILTERS` in crates/ffi-mobile/src/look.rs
 * (checked by LookPresetsTest, which also checks every id has assets/luts/<id>.cube). Kept in
 * Kotlin so the UI and screenshot tests don't need the native engine.
 */
object LookPresets {
    val all: List<Pair<String, String>> = listOf(
        "quente" to "Quente",
        "frio" to "Frio",
        "vivido" to "Vívido",
        "cinema" to "Cinema",
        "vintage" to "Vintage",
        "desbotado" to "Desbotado",
        "pb" to "P&B",
    )
}
