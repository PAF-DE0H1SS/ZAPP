package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable

/**
 * Настройки мониторинга трафика.
 *
 * Сам мониторинг всегда считает системные счётчики, а эти настройки решают,
 * что именно показывать и как часто опрашивать источник. Важный момент:
 * опрос слишком часто не даёт выигрыша, потому что системные счётчики
 * обновляются раз в секунду, но и слишком редкий опрос делает график рваным.
 */
@Serializable
data class TrafficConfig(
    val enabled: Boolean = true,

    // --- Что считать ---
    /** Считать весь трафик устройства. */
    val total: Boolean = true,
    /** Считать только трафик интерфейсов, которые сейчас используются. */
    val activeInterfacesOnly: Boolean = true,
    /** Показывать разбивку по интерфейсам. */
    val perInterface: Boolean = true,
    /** Показывать разбивку по приложениям; доступно не на всех платформах. */
    val perApp: Boolean = false,
    /** Не считать локальный трафик в пределах одного узла. */
    val excludeLoopback: Boolean = true,

    // --- Как часто ---
    /** Период опроса счётчиков, миллисекунды. */
    val intervalMs: Int = 1000,
    /** Сколько точек держать в графике. */
    val historyPoints: Int = 120,

    // --- Пороги ---
    /** Подсвечивать скорость выше порога, бит в секунду. */
    val speedAlertBitsPerSecond: Long = 0L,
    /** Предупреждать о превышении месячного лимита, байт; 0 -- выключено. */
    val monthlyLimitBytes: Long = 0L,
    /** День месяца, с которого лимит считается заново. */
    val limitResetDay: Int = 1,

    // --- Единицы ---
    val unitSystem: String = TrafficUnit.BINARY.code,
    val baseUnit: String = TrafficBaseUnit.BITS.code
) {
    /** Период в секундах с защитой от нуля и от absurdly малых значений. */
    val intervalSeconds: Double get() = intervalMs.coerceIn(200, 60_000) / 1000.0

    val points: Int get() = historyPoints.coerceIn(10, 2000)

    fun limitEnabled(): Boolean = monthlyLimitBytes > 0
}

/** Семантическая единица измерения. */
enum class TrafficUnit(val code: String, val ru: String, val en: String) {
    BINARY("binary", "Бинарная (KiB/MiB)", "Binary (KiB/MiB)"),
    DECIMAL("decimal", "Десятичная (kB/MB)", "Decimal (kB/MB)");

    companion object {
        fun of(code: String?): TrafficUnit =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: BINARY
    }
}

/** Базовое значение, от которого считается скорость. */
enum class TrafficBaseUnit(val code: String, val ru: String, val en: String) {
    BITS("bits", "бит/с", "bit/s"),
    BYTES("bytes", "байт/с", "byte/s");

    companion object {
        fun of(code: String?): TrafficBaseUnit =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: BITS
    }
}

/**
 * Форматирование трафика.
 *
 * Логика вынесена отдельно от Compose, потому что её есть смысл тестировать
 * числами: 1023 байта должны печататься как `1023 B`, а не как `1.0 KiB` --
 * округление до килобайт на трёхзначном числе всегда врёт.
 */
object TrafficFormat {
    private const val KIB = 1024.0
    private const val MIB = KIB * 1024.0
    private const val GIB = MIB * 1024.0
    private const val TIB = GIB * 1024.0

    private const val KB = 1000.0
    private const val MB = KB * 1000.0
    private const val GB = MB * 1000.0
    private const val TB = GB * 1000.0

    /** Целочисленный шаг: при 1023 байтах оставляем `B`. */
    private fun step(value: Double, binary: Boolean): Pair<Double, String> = when {
        binary && value >= TIB -> value / TIB to "TiB"
        !binary && value >= TB -> value / TB to "TB"
        binary && value >= GIB -> value / GIB to "GiB"
        !binary && value >= GB -> value / GB to "GB"
        binary && value >= MIB -> value / MIB to "MiB"
        !binary && value >= MB -> value / MB to "MB"
        binary && value >= KIB -> value / KIB to "KiB"
        !binary && value >= KB -> value / KB to "kB"
        else -> value to "B"
    }

    /** Сколько знаков оставить после запятой. */
    private fun decimals(scaled: Double): Int = if (scaled >= 100) 0 else if (scaled >= 10) 1 else 2

    fun bytes(value: Long, config: TrafficConfig = TrafficConfig()): String {
        val binary = TrafficUnit.of(config.unitSystem) == TrafficUnit.BINARY
        val (scaled, suffix) = step(value.toDouble(), binary)
        val text = if (scaled >= 100 && suffix == "B") scaled.toLong().toString()
        else String.format("%.${decimals(scaled)}f", scaled).trimEnd('0').trimEnd('.') + " " + suffix
        return if (scaled >= 100 && suffix == "B") "$text $suffix" else text
    }

    fun speed(bitsPerSecond: Double, config: TrafficConfig = TrafficConfig()): String {
        val binary = TrafficUnit.of(config.unitSystem) == TrafficUnit.BINARY
        val perSecond = TrafficBaseUnit.of(config.baseUnit) == TrafficBaseUnit.BITS
        val raw = if (perSecond) bitsPerSecond else bitsPerSecond / 8.0
        val (scaled, suffix) = step(raw, binary)
        val unit = if (perSecond) suffix else suffix.replace("i", "")
        val text = String.format("%.${decimals(scaled)}f", scaled).trimEnd('0').trimEnd('.')
        return "$text $unit/s"
    }

    fun duration(millis: Long): String {
        val total = millis.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }
}
