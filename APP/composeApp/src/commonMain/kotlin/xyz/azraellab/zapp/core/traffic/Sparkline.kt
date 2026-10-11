package xyz.azraellab.zapp.core.traffic

import kotlin.math.max

/**
 * Математика спарклайна скорости.
 *
 * Отдельный модуль, а не код прямо в Canvas -- по образцу `core/orb/OrbMath`:
 * нормализация, прореживание и защита от краёв проверяются числами в
 * dev-тестах, а не глазами на устройстве. Canvas лишь соединяет готовые
 * точки линиями.
 *
 * Вход -- история точек [xyz.azraellab.zapp.core.TrafficPoint] (или любая
 * последовательность чисел через селектор), выход -- точки в системе
 * координат рисунка: x растёт вправо, y вверх (0 -- низ, 1 -- верх).
 * Инверсия оси Y -- забота Canvas, здесь координаты честные.
 */

/** Одна точка спарклайна в нормализованных координатах. */
data class SparkPoint(val x: Float, val y: Float)

/**
 * Прореживает историю до [samples] точек, сохраняя хвост.
 *
 * Спарклайн -- это «что было недавно», поэтому при нехватке места
 * выбрасываются старые отсчёты, а не новые: опоздавший кусок сети не должен
 * вытеснять то, что юзер смотрит прямо сейчас.
 *
 * Шаг прореживания берётся как ceil(n/samples): это даёт результат не больше
 * samples точек без «хвостика» из одной точки (который выглядел бы как
 * обрыв). Порядок сохраняется, последняя точка -- всегда последняя.
 */
fun downsample(values: List<Double>, samples: Int): List<Double> {
    if (samples <= 0) return emptyList()
    if (values.size <= samples) return values
    val step = kotlin.math.ceil(values.size / samples.toDouble()).toInt()
    val thinned = values.chunked(step).map { it.last() }
    return thinned.takeLast(samples)
}

/**
 * Нормализует значения к шкале 0..1 по пиковому значению.
 *
 * Все нули -> пустой список: пустой график честнее, чем линия на нуле,
 * которая выглядит как «нет данных». Отрицательные значения не существуют
 * (скорость не бывает меньше нуля), но защита на месте: nan/inf и минус
 * прижимаются к границам, чтобы Canvas не получил отравленную координату.
 */
fun normalize(values: List<Double>): List<Double> {
    val peak = values.maxOrNull() ?: return emptyList()
    if (peak <= 0.0 || !peak.isFinite()) return emptyList()
    return values.map { (it / peak).coerceIn(0.0, 1.0) }
}

/**
 * Строит точки спарклайна из истории.
 *
 * [values] -- последовательность (обычно rx или tx бит/с), [samples] --
 * сколько точек максимум рисовать, [width] и [height] -- размеры холста.
 * x распределяется равномерно от 0 до width, y = значение * height.
 *
 * Одиночная точка рисуется как горизонтальный штрих от центра: одна точка
 * Canvas и так показал бы, но пустой список -- нет, поэтому различаем
 * «данных нет» (пусто) и «один отсчёт» (одна точка).
 */
fun sparklinePoints(
    values: List<Double>,
    samples: Int,
    width: Float,
    height: Float
): List<SparkPoint> {
    if (width <= 0f || height <= 0f) return emptyList()
    val thinned = normalize(downsample(values, samples))
    if (thinned.isEmpty()) return emptyList()
    if (thinned.size == 1) {
        return listOf(SparkPoint(width / 2f, thinned.first().toFloat() * height))
    }
    val last = (thinned.size - 1).toFloat()
    return thinned.mapIndexed { i, v ->
        SparkPoint(
            x = width * (i.toFloat() / last),
            y = v.toFloat() * height
        )
    }
}

/**
 * Хребет одной линии (rx или tx) -- два числа для пары линий на одном холсте.
 *
 * Пик берётся общий для обеих линий: тогда пропорция «скачок скачком, но
 * маленький» видна честно, а не сплющена индивидуальной нормализацией.
 */
fun sparklinePair(
    rx: List<Double>,
    tx: List<Double>,
    samples: Int,
    width: Float,
    height: Float
): Pair<List<SparkPoint>, List<SparkPoint>> {
    val shared = max(rx.maxOrNull() ?: 0.0, tx.maxOrNull() ?: 0.0)
    if (shared <= 0.0 || !shared.isFinite() || width <= 0f || height <= 0f) {
        return emptyList<SparkPoint>() to emptyList()
    }
    fun line(values: List<Double>): List<SparkPoint> {
        val thinned = downsample(values, samples)
        if (thinned.isEmpty()) return emptyList()
        if (thinned.size == 1) {
            return listOf(SparkPoint(width / 2f, (thinned.first() / shared).toFloat() * height))
        }
        val last = (thinned.size - 1).toFloat()
        return thinned.mapIndexed { i, v ->
            SparkPoint(
                x = width * (i.toFloat() / last),
                y = (v / shared).coerceIn(0.0, 1.0).toFloat() * height
            )
        }
    }
    return line(rx) to line(tx)
}
