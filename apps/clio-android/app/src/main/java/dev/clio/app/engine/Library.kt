package dev.clio.app.engine

import androidx.compose.ui.graphics.Color
import dev.clio.app.ui.screens.Draft

/** Drafts on disk → Home cards. */
suspend fun loadDraftCards(store: DraftStore): List<Draft> = store.list().map { m ->
    val rel = relativePt(m.editedAt)
    val s = m.durationMs / 1000
    Draft(m.id, m.title, rel, "%d:%02d".format(s / 60, s % 60), Color.Unspecified, m.thumbSource, m.thumbAtMs)
}

/** "agora", "há 5 min", "há 2 h", "ontem", "há 3 dias", "12 de out." */
fun relativePt(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t).coerceAtLeast(0) / 60_000
    return when {
        m < 1 -> "agora"
        m < 60 -> "há $m min"
        m < 24 * 60 -> "há ${m / 60} h"
        m < 48 * 60 -> "ontem"
        m < 7 * 24 * 60 -> "há ${m / (24 * 60)} dias"
        else -> java.text.SimpleDateFormat("d 'de' MMM", java.util.Locale("pt", "BR")).format(java.util.Date(t))
    }
}
