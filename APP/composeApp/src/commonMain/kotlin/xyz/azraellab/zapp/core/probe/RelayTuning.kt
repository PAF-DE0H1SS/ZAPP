package xyz.azraellab.zapp.core.probe

import kotlinx.serialization.Serializable

/**
 * Параметры разрыва ClientHello в локальном SOCKS-релее (noroot-обход).
 *
 * Общие для обоих демонов: релей один, и оба -- zapret, и goodbyedpi --
 * держат его вместе, поэтому и тюнинг у них общий. Хранится в основном
 * конфиге: подобранный пробами разрез должен переживать перезапуск,
 * иначе «подбор» превращается в игру на одну сессию.
 *
 * [cuts] -- сколько разрезов делается на первом TLS-записи (0 --
 * прокси без вмешательства, контрольный кандидат), [delayMs] -- пауза
 * между сегментами: мала -- DPI успевает склеить, велика -- ломается
 * латентность.
 */
@Serializable
data class RelayTuning(
    val delayMs: Int = 10,
    val cuts: Int = 1
) {
    /** Стабильный идентификатор для кандидатов и журнала. */
    val id: String get() = "$cuts-$delayMs"

    /** Значения в рабочих границах: проба могла записать что угодно. */
    fun normalized(): RelayTuning = RelayTuning(
        delayMs = delayMs.coerceIn(0, 500),
        cuts = cuts.coerceIn(0, 3)
    )

    companion object {
        /**
         * Разбирает [id] вида "cuts-delayMs" ("1-10"); null -- не наш формат.
         *
         * Кандидаты в UI хранятся строкой: так их можно показать и
         * вернуть из состояния, а не таскать объект по StateFlow.
         */
        fun of(id: String): RelayTuning? {
            val parts = id.trim().split('-')
            if (parts.size != 2) return null
            val cuts = parts[0].toIntOrNull() ?: return null
            val delay = parts[1].toIntOrNull() ?: return null
            if (delay < 0 || cuts < 0) return null
            return RelayTuning(delayMs = delay, cuts = cuts).normalized()
        }
    }
}
