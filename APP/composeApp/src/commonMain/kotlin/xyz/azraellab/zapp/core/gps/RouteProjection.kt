package xyz.azraellab.zapp.core.gps

import xyz.azraellab.zapp.core.RoutePoint
import kotlin.math.cos
import kotlin.math.min

/**
 * Проекция маршрута на плоскость виджета для мини-карты.
 *
 * Эквидистантная проекция: долгота с поправкой на косинус средней
 * широты (иначе ломаная растягивается к полюсам), широта -- как есть.
 * Один общий масштаб по обоим осям сохраняет пропорции, bbox центрируется.
 *
 * Отдельный класс, а не код в Compose: вся геометрия чистая, поэтому
 * мини-карта проверяется тестом на десктопе.
 */
class RouteProjection internal constructor(
    private val latCenter: Double,
    private val lonCenter: Double,
    private val lonCos: Double,
    private val scale: Double,
    private val centerX: Float,
    private val centerY: Float
) {
    /** Координаты широты/долготы в пикселях виджета. */
    fun xy(latitude: Double, longitude: Double): Pair<Float, Float> {
        val x = centerX + ((longitude - lonCenter) * lonCos * scale).toFloat()
        val y = centerY - ((latitude - latCenter) * scale).toFloat()
        return x to y
    }

    /** Ломаная маршрута в пикселях, в порядке точек. */
    fun project(points: List<RoutePoint>): List<Pair<Float, Float>> =
        points.map { xy(it.latitude, it.longitude) }

    companion object {
        /** Минимальный размах, ниже которого ось считается вырожденной. */
        private const val EPS = 1e-9

        /**
         * Строит проекцию списка точек под размер виджета.
         *
         * Вырожденные случаи сводятся к центру: одна точка, вертикальная
         * линия (нулевой размах долготы) и горизонтальная -- масштаб
         * берётся по оставшейся оси, обе пустые -- масштаб ноль.
         */
        fun transform(points: List<RoutePoint>, width: Float, height: Float, pad: Float): RouteProjection {
            val w = (width - 2f * pad).coerceAtLeast(1f)
            val h = (height - 2f * pad).coerceAtLeast(1f)
            if (points.isEmpty()) {
                return RouteProjection(0.0, 0.0, 1.0, 0.0, width / 2f, height / 2f)
            }
            val latMin = points.minOf { it.latitude }
            val latMax = points.maxOf { it.latitude }
            val lonMin = points.minOf { it.longitude }
            val lonMax = points.maxOf { it.longitude }
            val latCenter = (latMin + latMax) / 2.0
            val lonCenter = (lonMin + lonMax) / 2.0
            val lonCos = cos(Math.toRadians(latCenter)).coerceAtLeast(0.01)
            val xSpan = (lonMax - lonMin) * lonCos
            val ySpan = latMax - latMin
            val sx = if (xSpan > EPS) w / xSpan else 0.0
            val sy = if (ySpan > EPS) h / ySpan else 0.0
            val scale = when {
                sx == 0.0 && sy == 0.0 -> 0.0
                sx == 0.0 -> sy
                sy == 0.0 -> sx
                else -> min(sx, sy)
            }
            return RouteProjection(latCenter, lonCenter, lonCos, scale, pad + w / 2f, pad + h / 2f)
        }
    }
}
