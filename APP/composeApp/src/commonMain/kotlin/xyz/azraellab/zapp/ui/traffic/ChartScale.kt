package xyz.azraellab.zapp.ui.traffic

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Подготовка точек графика.
 *
 * Вынесено из Composable намеренно: нормализация -- это арифметика, которую
 * имеет смысл проверять числами. Например, деление на ноль при нулевом трафике
 * или пустой истории не видно глазами на экране, но ломает всю разметку.
 */
object ChartScale {
    /**
     * Переводит значения в доли от 0 до 1.
     *
     * Верхняя граница берётся не из максимума данных, а из [headroom]-кратного
     * максимума, иначе график всегда упирается в потолок и пик не видно.
     * Нулевой максимум заменяется единицей: пустой график должен рисоваться
     * плоской линией у нижнего края, а не падать.
     */
    fun normalize(values: List<Double>, headroom: Double = 1.15): List<Float> {
        if (values.isEmpty()) return emptyList()
        val peak = max(values.maxOrNull() ?: 0.0, 0.0)
        val top = if (peak <= 0.0) 1.0 else peak * headroom
        return values.map { (it / top).coerceIn(0.0, 1.0).toFloat() }
    }

    /**
     * Столбцы, а не линия: график трафика -- это поток значений во времени,
     * где важна каждая точка, а не тренд между ними. Ширина столбца считается от
     * числа точек, чтобы столбцы не слипались.
     */
    fun barWidth(pointCount: Int, available: Float, maxBar: Float = 12f): Float {
        if (pointCount <= 0) return 0f
        val slot = available / pointCount
        return max(1f, minOf(slot * 0.72f, maxBar))
    }

    /** Округление до целых байт для подписи: дробные байты -- опечатка. */
    fun roundBytes(value: Double): Long = value.roundToLong()
}
