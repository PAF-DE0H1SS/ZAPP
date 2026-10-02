package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable

/** Мгновенный срез по одному сетевому интерфейсу. */
@Serializable
data class InterfaceCounters(
    val name: String,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    /** Потери в приёме: признак перегруженной или нестабильной сети. */
    val droppedBytes: Long = 0L,
    /** Интерфейс активен: есть трафик или поднят. */
    val up: Boolean = true,
    /** Интерфейс служебный: `lo`, туннели, VPN. */
    val tunnelled: Boolean = false
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/** Снимок счётчиков всех интерфейсов в один момент времени. */
@Serializable
data class TrafficSample(
    /** Монотонное время опроса, миллисекунды. */
    val timestampMs: Long = 0L,
    val interfaces: List<InterfaceCounters> = emptyList()
) {
    fun rxBytes(): Long = interfaces.sumOf { it.rxBytes }

    fun txBytes(): Long = interfaces.sumOf { it.txBytes }

    fun totalBytes(): Long = interfaces.sumOf { it.totalBytes }

    fun interfaceByName(name: String): InterfaceCounters? = interfaces.firstOrNull { it.name == name }
}

/** Расчётная точка графика: скорость на интервале и накопленные итоги. */
@Serializable
data class TrafficPoint(
    val timestampMs: Long = 0L,
    val rxBitsPerSecond: Double = 0.0,
    val txBitsPerSecond: Double = 0.0,
    val totalBitsPerSecond: Double = 0.0,
    val totalBytes: Long = 0L
) {
    val active: Boolean get() = totalBitsPerSecond > 0.0
}

/** Разбивка трафика по приложению. */
@Serializable
data class AppTraffic(
    val packageName: String,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    /** Насколько активно приложение прямо сейчас, бит в секунду. */
    val bitsPerSecond: Double = 0.0
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/** Сводка, которую рисует экран мониторинга. */
@Serializable
data class TrafficSummary(
    val totalBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val currentBitsPerSecond: Double = 0.0,
    val peakBitsPerSecond: Double = 0.0,
    val averageBitsPerSecond: Double = 0.0,
    val activeSeconds: Long = 0L,
    val sessionStartedAtMs: Long = 0L,
    val byInterface: List<InterfaceTraffic> = emptyList(),
    val byApp: List<AppTraffic> = emptyList()
) {
    fun isIdle(): Boolean = currentBitsPerSecond <= 0.0

    fun topInterfaces(limit: Int = 5): List<InterfaceTraffic> =
        byInterface.sortedByDescending { it.totalBytes }.take(limit)

    fun topApps(limit: Int = 5): List<AppTraffic> =
        byApp.sortedByDescending { it.totalBytes }.take(limit)
}

/** Итог по интерфейсу за сессию наблюдения. */
@Serializable
data class InterfaceTraffic(
    val name: String,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L
) {
    val totalBytes: Long get() = rxBytes + txBytes
}
