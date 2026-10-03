package xyz.azraellab.zapp.core.gps

import xyz.azraellab.zapp.core.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проигрывание маршрута: чистая математика, поэтому здесь же и живут тесты.
 *
 * Смысл проверок: маршрут доходит до конца, стоит на паузах, крутится по
 * кругу и не зависает на нулевой скорости.
 */
class RouteProgressTest {

    private fun point(lat: Double, lon: Double, speed: Double? = null, dwell: Int = 0) =
        RoutePoint(latitude = lat, longitude = lon, speedMps = speed, dwellSeconds = dwell)

    @Test
    fun `односекундный шаг двигает точку вдоль сегмента`() {
        // Сегмент ~0.001 градуса по широте ≈ 111 м; 10 м/с за секунду -- шаг 10 м.
        val route = RouteProgress(
            listOf(point(0.0, 0.0), point(0.001, 0.0)),
            loop = false,
            routeDwellSeconds = 0
        )

        val first = route.step(1.0, defaultSpeedMps = 10.0)
        assertNotNull(first)
        assertTrue(first.latitude > 0.0, "точка должна продвинуться на север")
        assertTrue(first.latitude < 0.001, "точка не должна проскочить цель")

        var result: RoutePoint? = first
        repeat(30) { result = route.step(1.0, defaultSpeedMps = 10.0) }
        assertNull(result, "маршрут без зацикливания должен закончиться")
    }

    @Test
    fun `зацикленный маршрут никогда не заканчивается`() {
        val route = RouteProgress(
            listOf(point(0.0, 0.0), point(0.001, 0.0)),
            loop = true,
            routeDwellSeconds = 0
        )
        repeat(500) {
            val p = route.step(1.0, defaultSpeedMps = 50.0)
            assertNotNull(p, "в кольце точка должна быть всегда, шаг $it")
        }
    }

    @Test
    fun `пауза в точке останавливает движение`() {
        val route = RouteProgress(
            listOf(point(0.0, 0.0, dwell = 3), point(0.001, 0.0)),
            loop = false,
            routeDwellSeconds = 0
        )
        // Первый шаг стоит: времени прошло 1 с, а паузы ещё 2 с.
        val during = route.step(1.0, defaultSpeedMps = 10.0)
        assertNotNull(during)
        assertEquals(0.0, during.latitude, 1e-12, "во время паузы точка не двигается")
        assertTrue(route.dwellLeft > 0.0, "пауза должна тикать")

        // После паузы движение возобновляется.
        route.step(1.0, defaultSpeedMps = 10.0)
        route.step(1.0, defaultSpeedMps = 10.0)
        val moved = route.step(1.0, defaultSpeedMps = 10.0)
        assertNotNull(moved)
        assertTrue(moved.latitude > 0.0, "после паузы маршрут продолжается")
    }

    @Test
    fun `нулевая скорость не зависает навсегда`() {
        val route = RouteProgress(
            listOf(point(0.0, 0.0), point(0.001, 0.0)),
            loop = false,
            routeDwellSeconds = 0
        )
        // defaultSpeed = 0 -> внутри действует минимальный запас, и шаг идёт.
        val p = route.step(1.0, defaultSpeedMps = 0.0)
        assertNotNull(p)
        assertTrue(p.latitude > 0.0, "даже при нулевой скорости маршрут не должен висеть")
    }

    @Test
    fun `пустой маршрут сразу заканчивается`() {
        val route = RouteProgress(emptyList(), loop = false, routeDwellSeconds = 0)
        assertNull(route.step(1.0, defaultSpeedMps = 10.0))
    }
}
