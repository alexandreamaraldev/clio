package dev.clio.app.engine

import android.content.Context
import dev.clio.app.ui.theme.ThemeMode
import java.time.LocalDate

/** Local engagement state: onboarding, daily editing streak, drafts. */
class AppStore(context: Context) {
    private val p = context.getSharedPreferences("clio", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) { p.edit().putBoolean("onboarded", v).apply() }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(p.getString("theme", "System")!!) }.getOrDefault(ThemeMode.System)
        set(v) { p.edit().putString("theme", v.name).apply() }

    val streak: Int get() = p.getInt("streak", 0)

    /** Call when the user opens the editor; counts consecutive days. */
    fun touchStreak(today: LocalDate = LocalDate.now()) {
        val last = p.getString("streak_day", null)?.let(LocalDate::parse)
        val next = when (last) {
            today -> streak
            today.minusDays(1) -> streak + 1
            else -> 1
        }
        p.edit().putInt("streak", next).putString("streak_day", today.toString()).apply()
    }
}
