package xyz.azraellab.zapp.core.gps

import xyz.azraellab.zapp.core.RoutePoint
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Подбор точки маршрута под время прибытия.
 *
 * Сценарий: пользователь знает, что «прибудет через 20 минут», и хочет,
 * чтобы подмена показывала движение всё это время. Задача -- взять
 * ломаную маршрута и выдать позицию, которая соответствует оставшемуся
 * времени: равномерно по длине, потому что скорость по условию постоянна.
 *
 * Вся математика чистая: без сети, без Android, без состояния -- поэтому
 * она живёт здесь и проверяется в desktopTest.
 */
object RouteEta {

    /** Общая длина ломаной, метры. 0 для маршрута меньше чем из двух точек. */
    fun lengthMeters(points: List<RoutePoint>): Double {
        if (points.size < 2) return 0.0
        return points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
    }

    /**
     * Время прохождения при постоянной скорости, секунды.
     *
     * Скорость берётся из точек, где она задана, иначе -- [defaultSpeedMps].
     * Нулевая скорость не делит на ноль: маршрут с такой скоростью
     * непроходим, а деление на ноль дало бы Infinity в интерфейс.
     */
    fun durationSeconds(points: List<RoutePoint>, defaultSpeedMps: Double): Double {
        if (points.size < 2) return 0.0
        val speed = defaultSpeedMps.coerceAtLeast(MIN_SPEED_MPS)
        return points.zipWithNext().sumOf { (a, b) ->
            val segmentSpeed = (a.speedMps ?: speed).coerceAtLeast(MIN_SPEED_MPS)
            a.distanceTo(b) / segmentSpeed
        }
    }

    /**
     * Точка ломаной на доле [fraction] (0..1), от начала до конца.
     *
     * Доли вне диапазона зажимаются: «осталось минуты» не должно уводить
     * позицию за конец маршрута. Для пустого маршрута -- null.
     */
    fun pointAtFraction(points: List<RoutePoint>, fraction: Double): RoutePoint? {
        val pts = points
        if (pts.isEmpty()) return null
        if (pts.size == 1) return pts.first()
        val total = lengthMeters(pts)
        if (total <= 0.0) return pts.first()

        val target = fraction.coerceIn(0.0, 1.0) * total
        var passed = 0.0
        for (i in 0 until pts.lastIndex) {
            val from = pts[i]
            val to = pts[i + 1]
            val segment = from.distanceTo(to)
            if (passed + segment >= target || i == pts.lastIndex - 1) {
                if (segment <= 0.0) return to
                val into = ((target - passed) / segment).coerceIn(0.0, 1.0)
                return RoutePoint(
                    latitude = from.latitude + (to.latitude - from.latitude) * into,
                    longitude = from.longitude + (to.longitude - from.longitude) * into,
                    speedMps = from.speedMps
                )
            }
            passed += segment
        }
        return pts.last()
    }

    /**
     * Позиция, соответствующая [etaSeconds] оставшегося времени.
     *
     * null -- eta уже вышел: двигаться осталось некуда, и подставлять
     * «конец маршрута» было бы враньём (пользователь ещё в пути).
     */
    fun pointForEta(points: List<RoutePoint>, defaultSpeedMps: Double, etaSeconds: Double): RoutePoint? {
        if (points.isEmpty()) return null
        if (etaSeconds <= 0.0) return null
        val total = durationSeconds(points, defaultSpeedMps)
        if (total <= 0.0) return points.first()
        val elapsedFraction = 1.0 - (etaSeconds / total).coerceIn(0.0, 1.0)
        return pointAtFraction(points, elapsedFraction)
    }

    /**
     * Равномерная дискретизация ломаной: [count] точек через равные
     * интервалы длины, включая концы.
     *
     * Нужна там, где время задано, а путь -- нет: например, чтобы
     * разложить «ехать 20 минут» на шаги тика. Меньше двух точек --
     * список без изменений: интерполировать нечего.
     */
    fun sampleEvenly(points: List<RoutePoint>, count: Int): List<RoutePoint> {
        if (points.size < 2 || count < 2) return points
        val total = lengthMeters(points)
        if (total <= 0.0) return points
        return (0 until count).mapNotNull { i ->
            pointAtFraction(points, i.toDouble() / (count - 1))
        }
    }

    /**
     * Сколько шагов тика нужно, чтобы пройти маршрут за [seconds]
     * при шаге [tickSeconds]. Округление вверх: лучше сделать шаг
     * впритык, чем оборвать движение на середине сегмента.
     */
    fun tickCount(seconds: Double, tickSeconds: Double): Long {
        if (seconds <= 0.0 || tickSeconds <= 0.0) return 0L
        return ceil(seconds / tickSeconds).toLong().coerceAtLeast(1L)
    }

    /** Прогресс (0..1) по пройденному времени: для полосы в интерфейсе. */
    fun progress(elapsedSeconds: Double, totalSeconds: Double): Double =
        if (totalSeconds <= 0.0) 0.0 else (elapsedSeconds / totalSeconds).coerceIn(0.0, 1.0)

    /** Метры, пройденные за [seconds] при [speedMps] с джиттером процента. */
    fun distanceWithJitter(speedMps: Double, seconds: Double, jitterPercent: Int, jitter: () -> Double): Double {
        val base = speedMps.coerceAtLeast(0.0) * seconds
        if (jitterPercent <= 0) return base
        val delta = jitter().coerceIn(-1.0, 1.0) * (jitterPercent.coerceAtMost(100) / 100.0)
        return (base * (1.0 + delta)).coerceAtLeast(0.0)
    }

    private const val MIN_SPEED_MPS = 0.1

    /**
     * Расстояние в человекочитаемом виде: «740 m», «1.2 km».
     *
     * Единицы одни и тех же на всех языках -- как и у скорости в плашке
     *: сокращения это не переводимые слова, а обозначения величин.
     */
    fun formatDistance(meters: Double): String {
        val value = meters.coerceAtLeast(0.0)
        return if (value >= 1000.0) trimZero(value / 1000.0) + " km" else trimZero(value) + " m"
    }

    /** Один знак после запятой, хвостовой ноль отбрасывается: 1.0 -> 1, 1.5 -> 1.5. */
    private fun trimZero(value: Double): String {
        val rounded = (value * 10.0).toLong() / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    /**
     * Длительность в человекочитаемом виде: «1h 20m», «7m».
     *
     * Сокращения не переводятся, как и единицы скорости: это обозначения
     * величин, а не слова интерфейса.
     */
    fun formatDuration(seconds: Double): String {
        val total = seconds.roundToLong().coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${total}s"
        }
    }
}
