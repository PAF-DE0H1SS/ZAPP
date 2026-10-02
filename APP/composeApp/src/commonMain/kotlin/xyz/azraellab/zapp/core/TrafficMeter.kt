package xyz.azraellab.zapp.core

/**
 * Счётчик трафика: хранит предыдущий срез и превращает его в скорость.
 *
 * Скорость нельзя взять из самого счётчика -- ядро отдаёт только накопленные
 * байты. Поэтому нужна разница между двумя опросами, делённая на интервал.
 * Отсюда две тонкости, которые и обрабатывает этот класс:
 *
 * 1. Счётчик может уменьшиться. Интерфейс пересоздали, или сработал 32-битный
 *    переполнение в счётчике ядра. Разница станет отрицательной, и если её
 *    не обнулить, на графике появится ложный ноль или всплеск.
 * 2. Первый срез базовый: скорости из него не существует, потому что не с чем
 *    сравнивать. Поэтому первый [update] всегда возвращает точку с нулём.
 */
class TrafficMeter(
    private val config: TrafficConfig = TrafficConfig()
) {
    private var previous: TrafficSample? = null
    private var previousTimestamp: Long = 0L

    private val rxTotal = mutableMapOf<String, Long>()
    private val txTotal = mutableMapOf<String, Long>()

    private var sessionStart: Long = 0L
    private var activeMs: Long = 0L
    private var peak: Double = 0.0
    private var speedSum: Double = 0.0
    private var speedCount: Int = 0

    private val history = ArrayDeque<TrafficPoint>(config.points)

    /**
     * Принимает новый срез и возвращает точку графика.
     *
     * Первый вызов устанавливает базу и возвращает нулевую точку.
     */
    fun update(sample: TrafficSample): TrafficPoint {
        val now = sample.timestampMs
        val prev = previous

        if (prev == null) {
            previous = sample
            previousTimestamp = now
            if (sessionStart == 0L) sessionStart = now
            accumulate(sample)
            val base = TrafficPoint(
                timestampMs = now,
                totalBytes = sample.totalBytes()
            )
            push(base)
            return base
        }

        val elapsedMs = (now - previousTimestamp).coerceAtLeast(1L)
        val seconds = elapsedMs / 1000.0

        val counted = if (config.activeInterfacesOnly) sample.interfaces.filter { it.up } else sample.interfaces

        var rxDelta = 0L
        var txDelta = 0L
        counted.forEach { current ->
            val before = prev.interfaceByName(current.name)
            rxDelta += positiveDelta(before?.rxBytes ?: 0L, current.rxBytes)
            txDelta += positiveDelta(before?.txBytes ?: 0L, current.txBytes)
        }

        val rxBits = rxDelta * 8.0 / seconds
        val txBits = txDelta * 8.0 / seconds
        val totalBits = rxBits + txBits

        accumulate(sessionDelta(sample, prev))

        val point = TrafficPoint(
            timestampMs = now,
            rxBitsPerSecond = rxBits,
            txBitsPerSecond = txBits,
            totalBitsPerSecond = totalBits,
            totalBytes = rxTotal.values.sum() + txTotal.values.sum()
        )

        if (point.active) {
            activeMs += elapsedMs
            speedSum += totalBits
            speedCount++
            if (totalBits > peak) peak = totalBits
        }

        previous = sample
        previousTimestamp = now
        push(point)
        return point
    }

    /** Текущий график, старые точки обрезаны до [TrafficConfig.points]. */
    fun history(): List<TrafficPoint> = history.toList()

    /** Сводка для экрана. */
    fun summary(byApp: List<AppTraffic> = emptyList()): TrafficSummary = TrafficSummary(
        totalBytes = rxTotal.values.sum() + txTotal.values.sum(),
        rxBytes = rxTotal.values.sum(),
        txBytes = txTotal.values.sum(),
        currentBitsPerSecond = history.lastOrNull()?.totalBitsPerSecond ?: 0.0,
        peakBitsPerSecond = peak,
        averageBitsPerSecond = if (speedCount > 0) speedSum / speedCount else 0.0,
        activeSeconds = activeMs / 1000,
        sessionStartedAtMs = sessionStart,
        byInterface = rxTotal.map { (name, rx) ->
            InterfaceTraffic(name, rx, txTotal[name] ?: 0L)
        }.filter { config.perInterface || (it.rxBytes > 0L || it.txBytes > 0L) },
        byApp = byApp
    )

    /** Сброс накопленных итогов, график тоже очищается. */
    fun reset() {
        rxTotal.clear()
        txTotal.clear()
        history.clear()
        previous = null
        previousTimestamp = 0L
        sessionStart = 0L
        activeMs = 0L
        peak = 0.0
        speedSum = 0.0
        speedCount = 0
    }

    /**
     * Разница счётчиков, приведённая к неотрицательному виду.
     *
     * Уменьшение означает, что счётчик перезапустили, а не что трафик
     * исчез. В этом случае отдаём 0 и начинаем отсчёт заново.
     */
    private fun positiveDelta(before: Long, current: Long): Long =
        if (current >= before) current - before else 0L

    private fun sessionDelta(current: TrafficSample, prev: TrafficSample): TrafficSample {
        val names = (prev.interfaces.map { it.name } + current.interfaces.map { it.name }).toSet()
        return TrafficSample(
            timestampMs = current.timestampMs,
            interfaces = names.map { name ->
                val now = current.interfaceByName(name)
                val before = prev.interfaceByName(name)
                InterfaceCounters(
                    name = name,
                    rxBytes = positiveDelta(before?.rxBytes ?: 0L, now?.rxBytes ?: 0L),
                    txBytes = positiveDelta(before?.txBytes ?: 0L, now?.txBytes ?: 0L),
                    up = now?.up ?: false,
                    tunnelled = now?.tunnelled ?: false
                )
            }
        )
    }

    private fun accumulate(sample: TrafficSample) {
        sample.interfaces.forEach {
            rxTotal[it.name] = (rxTotal[it.name] ?: 0L) + it.rxBytes
            txTotal[it.name] = (txTotal[it.name] ?: 0L) + it.txBytes
        }
    }

    private fun push(point: TrafficPoint) {
        history.addLast(point)
        val limit = config.points
        while (history.size > limit) history.removeFirst()
    }
}
