package xyz.azraellab.zapp.core

/** Насколько свежа подписка по времени последнего обновления. */
enum class SourceFreshness {
    /** Обновлялась недавно -- всё в порядке. */
    FRESH,

    /** Давно не обновлялась: три периода автообновления позади. */
    STALE,

    /** Ещё ни разу не обновлялась. */
    NEVER
}

/**
 * Сколько периодов автообновления считается устареванием.
 *
 * Три -- с запасом: цикл обновления проскакивает период только если
 * приложение не работало, и одиночный пропуск на паузе ноутбука не
 * должен пугать красной плашкой.
 */
const val SOURCE_STALE_PERIODS = 3

/**
 * Свежесть подписки: [refreshSeconds] периодов с последнего обновления.
 *
 * Выключенный автообновитель (`refreshSeconds <= 0`) -- FRESH: человек
 * сам решает, когда обновлять, и приложение не должно навязывать счёт.
 */
fun sourceFreshness(nowMs: Long, lastRefreshAtMs: Long, refreshSeconds: Int): SourceFreshness = when {
    lastRefreshAtMs == 0L -> SourceFreshness.NEVER
    refreshSeconds <= 0 -> SourceFreshness.FRESH
    nowMs - lastRefreshAtMs >= refreshSeconds * SOURCE_STALE_PERIODS * 1000L -> SourceFreshness.STALE
    else -> SourceFreshness.FRESH
}

/**
 * Возраст обновления компактной строкой: «45m», «3h», «2d».
 *
 * «—» для источника, который ещё не обновлялся. Единицы латиницей
 * коротко и без перевода: это техническая подпись, а не фраза.
 */
fun sourceAgeText(nowMs: Long, lastRefreshAtMs: Long): String {
    if (lastRefreshAtMs == 0L) return "—"
    val minutes = ((nowMs - lastRefreshAtMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        else -> "${minutes / (60 * 24)}d"
    }
}
