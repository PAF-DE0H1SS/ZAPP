package xyz.azraellab.zapp.core.gps

import kotlin.math.min
import kotlin.random.Random

/**
 * Мягкое движение без резких скачков (анти rubber-banding).
 *
 * Три независимых рычага, все объявлены в [xyz.azraellab.zapp.core.GpsConfig]:
 * джиттер скорости (speedJitterPercent), дрейф координат
 * (driftMetersPerSecond по курсу) и плавный разгон/торможение вдоль
 * маршрута. Вся математика чистая -- живёт здесь и тестится на десктопе.
 */
object RubberBand {

    /** Разгон на первых метрах маршрута. */
    const val ACCEL_METERS = 25.0

    /** Торможение на последних метрах маршрута. */
    const val DECEL_METERS = 25.0

    /**
     * Скорость с разбросом ±[percent] процентов.
     *
     * Ноль и отрицательное разброса означают точную скорость; результат
     * не уходит в минус. Мгновенный «0» допустим: в маршруте его
     * подхватывает минимальная скорость шага, у джойстика -- одно
     * касание на секунду.
     */
    fun jitterSpeed(baseMps: Double, percent: Int, random: Random = Random.Default): Double {
        if (percent <= 0 || baseMps <= 0.0) return baseMps
        val p = (percent.coerceAtMost(100)) / 100.0
        val factor = 1.0 + random.nextDouble(-p, p)
        return (baseMps * factor).coerceAtLeast(0.0)
    }

    /**
     * Профиль скорости 0..1 вдоль маршрута: трапеция.
     *
     * Первые [accelMeters] -- разгон с нуля, последние [decelMeters] --
     * торможение. [passedMeters] -- сколько пройдено, [totalMeters] --
     * длина маршрута одним проходом.
     *
     * Пол -- чтобы старт не завис на нулевой скорости: дискретный такт
     * с множителем 0 никогда не сдвинет точку с места, а значит профиль
     * никогда бы не начал расти. Короткий маршрут не должен ехать
     * ползком: разгон и торможение ограничены четвертью длины.
     */
    fun speedProfile(passedMeters: Double, totalMeters: Double, accelMeters: Double, decelMeters: Double): Double {
        if (totalMeters <= 0.0) return 1.0
        val quarter = totalMeters * MAX_SHARE
        val accel = accelMeters.coerceAtMost(quarter)
        val decel = decelMeters.coerceAtMost(quarter)
        val up = if (accel <= 0.0) 1.0 else passedMeters / accel
        val down = if (decel <= 0.0) 1.0 else (totalMeters - passedMeters) / decel
        return min(1.0, min(up, down)).coerceAtLeast(MIN_FACTOR)
    }

    private const val MAX_SHARE = 0.25

    /** Пол профиля: старт не должен зависнуть на нулевой скорости. */
    const val MIN_FACTOR = 0.1
}

/**
 * Накопительный дрейф координат.
 *
 * Каждый такт прибавляет [xyz.azraellab.zapp.core.GpsConfig.driftMetersPerSecond]
 * к общему сдвигу: двигается не только маршрут, но и «неподвижная» точка --
 * идеально статичная позиция не бывает настоящей. Сдвиг применяется по
 * курсу отдельным шагом поверх основной координаты.
 */
class DriftAccumulator {

    /** Сколько метров накоплено. */
    var meters: Double = 0.0
        private set

    fun tick(driftMps: Double, dtSeconds: Double) {
        if (dtSeconds > 0.0) meters += driftMps * dtSeconds
    }

    fun reset() {
        meters = 0.0
    }
}
