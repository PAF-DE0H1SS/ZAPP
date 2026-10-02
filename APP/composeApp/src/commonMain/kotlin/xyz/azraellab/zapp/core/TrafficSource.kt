package xyz.azraellab.zapp.core

/**
 * Источник системных счётчиков трафика.
 *
 * Объявление живёт в common, чтобы вызывающий код не знал, читаем мы
 * `/proc/net/dev` на десктопе или получаем цифры из нативного слоя на Android.
 * Монотонное время выбирает тоже платформа, потому что на Android системное
 * время прыгает при переводе часов, а для графика это ломает интервалы.
 */
interface TrafficSource {
    /** Монотонное время, миллисекунды. */
    fun nowMs(): Long

    /** Срез счётчиков всех сетевых интерфейсов. */
    fun read(nowMs: Long): TrafficSample

    /** Текущая скорость потребления батареи, проценты в минуту; null если неизвестно. */
    fun batteryDrainPercentPerMinute(): Double?
}

/** Создаёт источник для текущей платформы. */
expect fun createTrafficSource(): TrafficSource

/** Платформенные возможности мониторинга. */
object TrafficCaps {
    /** Разбивка по приложениям доступна не везде: на десктопе её просто нет. */
    const val PER_APP: Boolean = true
    const val PER_INTERFACE: Boolean = true
    const val PEAK_TRACKING: Boolean = true
    const val HISTORY: Boolean = true
}
