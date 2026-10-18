package xyz.azraellab.zapp.core.traffic

/** Итог трафика за период (день или набор дней). */
data class TrafficDay(
    val downBytes: Long = 0L,
    val upBytes: Long = 0L
) {
    val totalBytes: Long get() = downBytes + upBytes
}

/**
 * История трафика по дням: аккумулятор для «за сегодня / за неделю».
 *
 * День -- сутки UTC (индекс = мс / [DAY_MS]): границы простые и
 * детерминированные, без часовых поясов и календарных библиотек. Живёт в
 * памяти, сериализуется в компактный текст для файла настроек, поэтому
 * переживает перезапуск. Счётчики за сессию (TrafficMeter) отдельно:
 * здесь -- накопленные итоги по календарным дням.
 */
class TrafficHistory(
    private val days: MutableMap<Long, TrafficDay> = mutableMapOf()
) {

    /** Добавляет байты в день, к которому относится [atMs]. */
    fun add(atMs: Long, downBytes: Long, upBytes: Long) {
        // Отрицательный компонент -- аномалия (сброс счётчика), весь сэмпл
        // в мусор; нулевой -- считать нечего.
        if (downBytes < 0 || upBytes < 0 || (downBytes == 0L && upBytes == 0L)) return
        val idx = atMs / DAY_MS
        val cur = days[idx] ?: TrafficDay()
        days[idx] = TrafficDay(cur.downBytes + downBytes, cur.upBytes + upBytes)
    }

    /** Итог за сегодня (сутки, в которых лежит [nowMs]). */
    fun today(nowMs: Long): TrafficDay = sum(nowMs / DAY_MS, nowMs / DAY_MS)

    /** Итог за последние 7 суток включая сегодня. */
    fun thisWeek(nowMs: Long): TrafficDay = sum(nowMs / DAY_MS - 6, nowMs / DAY_MS)

    /** Все дни по порядку. */
    fun days(): List<TrafficDay> = days.entries.sortedBy { it.key }.map { it.value }

    /** Отбрасывает дни старше [keep] суток от [nowMs]. */
    fun prune(nowMs: Long, keep: Int) {
        val minIdx = nowMs / DAY_MS - keep
        days.keys.retainAll { it >= minIdx }
    }

    private fun sum(fromIdx: Long, toIdx: Long): TrafficDay {
        var down = 0L
        var up = 0L
        for (idx in fromIdx..toIdx) {
            val day = days[idx] ?: continue
            down += day.downBytes
            up += day.upBytes
        }
        return TrafficDay(down, up)
    }

    /** Компактный текст для файла: строки «день,вход,исход». */
    fun encode(): String = days.entries.sortedBy { it.key }
        .joinToString("\n") { "${it.key},${it.value.downBytes},${it.value.upBytes}" }

    companion object {
        const val DAY_MS = 86_400_000L

        /** Восстанавливает историю из текста; битые строки пропускаются. */
        fun decode(text: String): TrafficHistory {
            val map = mutableMapOf<Long, TrafficDay>()
            text.lineSequence().forEach { line ->
                val parts = line.split(',')
                if (parts.size == 3) {
                    val idx = parts[0].toLongOrNull()
                    val down = parts[1].toLongOrNull()
                    val up = parts[2].toLongOrNull()
                    if (idx != null && down != null && up != null) map[idx] = TrafficDay(down, up)
                }
            }
            return TrafficHistory(map)
        }
    }
}
