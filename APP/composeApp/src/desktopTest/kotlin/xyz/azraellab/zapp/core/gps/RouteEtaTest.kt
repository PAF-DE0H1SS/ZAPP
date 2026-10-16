package xyz.azraellab.zapp.core.gps

import xyz.azraellab.zapp.core.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ETA маршрута: чистая математика без сети и Android.
 *
 * Смысл проверок: подбор точки следует за оставшимся временем, длина и
 * время считаются в метрах и секундах, а края зажимаются -- выйти за
 * конец маршрута нельзя.
 */
class RouteEtaTest {

    private fun point(lat: Double, lon: Double, speed: Double? = null) =
        RoutePoint(latitude = lat, longitude = lon, speedMps = speed)

    /** ~0.001 градуса по широте = 111 м. */
    private val straight = listOf(point(0.0, 0.0, 10.0), point(0.001, 0.0, 10.0))

    @Test
    fun `длина ломаной в метрах`() {
        assertEquals(0.0, RouteEta.lengthMeters(emptyList()), 0.001)
        assertEquals(0.0, RouteEta.lengthMeters(straight.take(1)), 0.001)
        assertEquals(111.0, RouteEta.lengthMeters(straight), 1.0)
    }

    @Test
    fun `время проходится по скорости сегмента`() {
        // 111 м при 10 м/с ≈ 11.1 с.
        assertEquals(11.1, RouteEta.durationSeconds(straight, defaultSpeedMps = 1.0), 0.1)

        // Нулевая скорость не даёт деления на ноль.
        val zero = listOf(point(0.0, 0.0, 0.0), point(0.001, 0.0, 0.0))
        assertTrue(RouteEta.durationSeconds(zero, defaultSpeedMps = 10.0) > 0.0)
    }

    @Test
    fun `точка на доле маршрута`() {
        val mid = RouteEta.pointAtFraction(straight, 0.5)
        assertNotNull(mid)
        assertEquals(0.0005, mid.latitude, 0.0001)

        // Доли вне диапазона зажимаются к концам.
        assertEquals(0.0, RouteEta.pointAtFraction(straight, -1.0)!!.latitude, 0.0001)
        assertEquals(0.001, RouteEta.pointAtFraction(straight, 5.0)!!.latitude, 0.0001)
        assertNull(RouteEta.pointAtFraction(emptyList(), 0.5))
    }

    @Test
    fun `позиция по оставшемуся eta`() {
        // Полное время ≈ 11.1 с: осталось всё время -- начало, осталось 0 -- конец.
        val start = RouteEta.pointForEta(straight, 10.0, 11.1)
        assertNotNull(start)
        assertEquals(0.0, start.latitude, 0.001)

        val end = RouteEta.pointForEta(straight, 10.0, 0.1)
        assertNotNull(end)
        assertEquals(0.001, end.latitude, 0.001)

        // eta уже вышел -- позиции нет.
        assertNull(RouteEta.pointForEta(straight, 10.0, 0.0))
        assertNull(RouteEta.pointForEta(emptyList(), 10.0, 5.0))
    }

    @Test
    fun `равномерная выборка включает концы`() {
        val sample = RouteEta.sampleEvenly(straight, 3)
        assertEquals(3, sample.size)
        assertEquals(0.0, sample.first().latitude, 0.0001)
        assertEquals(0.001, sample.last().latitude, 0.0001)
        assertEquals(0.0005, sample[1].latitude, 0.0001)

        // Меньше двух точек -- без изменений.
        assertEquals(straight, RouteEta.sampleEvenly(straight, 1))
    }

    @Test
    fun `шагов тика хватает до конца`() {
        assertEquals(6L, RouteEta.tickCount(11.1, 2.0))
        assertEquals(0L, RouteEta.tickCount(0.0, 1.0))
        assertEquals(0L, RouteEta.tickCount(5.0, 0.0))
    }

    @Test
    fun `прогресс зажат между нулем и единицей`() {
        assertEquals(0.5, RouteEta.progress(5.0, 10.0), 0.001)
        assertEquals(1.0, RouteEta.progress(50.0, 10.0), 0.001)
        assertEquals(0.0, RouteEta.progress(-1.0, 10.0), 0.001)
        assertEquals(0.0, RouteEta.progress(5.0, 0.0), 0.001)
    }

    @Test
    fun `человекочитаемая длительность`() {
        assertEquals("30s", RouteEta.formatDuration(30.0))
        assertEquals("7m", RouteEta.formatDuration(7 * 60.0))
        assertEquals("1h 20m", RouteEta.formatDuration(4800.0))
        assertEquals("0s", RouteEta.formatDuration(-5.0))
    }

    @Test
    fun `расстояние в метрах и километрах`() {
        assertEquals("0 m", RouteEta.formatDistance(-1.0))
        assertEquals("740 m", RouteEta.formatDistance(740.0))
        assertEquals("1.2 km", RouteEta.formatDistance(1200.0))
        assertEquals("12 km", RouteEta.formatDistance(12000.0))
    }

    @Test
    fun `джиттер не уводит дистанцию в минус`() {
        assertEquals(100.0, RouteEta.distanceWithJitter(10.0, 10.0, 0) { 1.0 }, 0.001)
        val jittered = RouteEta.distanceWithJitter(10.0, 10.0, 20) { 1.0 }
        assertEquals(120.0, jittered, 0.001)
        val negative = RouteEta.distanceWithJitter(10.0, 10.0, 200) { -1.0 }
        assertTrue(negative >= 0.0, "наверх зажатия нет, но в минус дистанция не уходит")
    }
}
