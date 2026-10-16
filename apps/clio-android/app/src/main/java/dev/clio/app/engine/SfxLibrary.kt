package dev.clio.app.engine

import android.content.Context
import java.io.File

/**
 * Bundled sound effects (app assets/sfx). Every file is CC0 (Kenney "Interface Sounds"); sources and
 * licences are listed in ATTRIBUTION.md, in a sidecar next to each file, and in the credits sheet.
 */
object SfxLibrary {
    data class Sfx(val id: String, val label: String, val durationMs: Long)
    data class Credit(val title: String, val author: String, val license: String, val url: String)

    val all = listOf(
        Sfx("confirmation_001", "Sucesso", 290), Sfx("select_003", "Seleção", 383), Sfx("switch_002", "Deslizar", 611),
        Sfx("pluck_001", "Toque", 102), Sfx("bong_001", "Gongo", 123), Sfx("glass_001", "Vidro", 278),
        Sfx("drop_002", "Gota", 191), Sfx("maximize_003", "Subida", 212), Sfx("scratch_002", "Arranhão", 139),
        Sfx("toggle_001", "Clique", 139),
    )

    val credits = listOf(Credit("Interface Sounds", "Kenney (www.kenney.nl)", "CC0 1.0 (domínio público)", "https://kenney.nl/assets/interface-sounds"))

    /** Copies the asset to app storage once and returns it (file URIs work for ExoPlayer, Transformer and MediaExtractor). */
    fun file(context: Context, s: Sfx): File {
        val f = File(File(context.filesDir, "sfx").apply { mkdirs() }, "${s.id}.ogg")
        if (!f.exists() || f.length() == 0L) context.assets.open("sfx/${s.id}.ogg").use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }
}
