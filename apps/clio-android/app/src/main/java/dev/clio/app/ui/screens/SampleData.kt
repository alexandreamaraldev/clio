package dev.clio.app.ui.screens

import androidx.compose.ui.graphics.Color

data class Draft(val id: String, val title: String, val edited: String, val duration: String, val tint: Color, val thumbSource: String? = null, val thumbAtMs: Long = 0)
data class Template(val title: String, val uses: String, val tint: Color)
data class UiClip(
    val label: String, val startS: Float, val lenS: Float, val id: Long = label.hashCode().toLong(), val source: String? = null, val inS: Float = 0f,
    val sourceS: Float = 0f, val speed: Float = 1f, val volume: Float = 1f, val fadeInS: Float = 0f, val fadeOutS: Float = 0f, val muted: Boolean = false,
)
data class UiTrack(val name: String, val kind: String, val clips: List<UiClip>)

object Samples {
    val drafts = listOf(
        Draft("s", "Lisboa, outono", "há 2 h", "0:42", Color(0xFF3A3532)),
        Draft("s", "Café da manhã", "ontem", "0:18", Color(0xFF2E3336)),
        Draft("s", "Trilha na serra", "3 dias", "1:12", Color(0xFF2C3330)),
        Draft("s", "Unboxing", "1 sem", "0:31", Color(0xFF34313A)),
    )
    val templates = listOf(
        Template("Recap minimal", "12 mil usos", Color(0xFF2A2826)),
        Template("Viagem", "8,4 mil", Color(0xFF262A2C)),
        Template("Produto", "5,1 mil", Color(0xFF2B2A2E)),
        Template("Vlog diário", "21 mil", Color(0xFF282B28)),
    )
    val tracks = listOf(
        UiTrack("Vídeo", "video", listOf(UiClip("Praia", 0f, 4.2f), UiClip("Rua", 4.2f, 3.1f), UiClip("Pôr do sol", 7.3f, 5f))),
        UiTrack("Texto", "text", listOf(UiClip("Aa  Lisboa", 0.6f, 3f), UiClip("Aa  fim", 9.5f, 2.5f))),
        UiTrack("Efeito", "fx", listOf(UiClip("Grão suave", 4.2f, 3.1f))),
        UiTrack("Música", "audio", listOf(UiClip("Lo-fi morning", 0f, 12.3f))),
    )
}
