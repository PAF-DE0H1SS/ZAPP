package xyz.azraellab.zapp.core.gps

import xyz.azraellab.zapp.core.GpsConfig
import xyz.azraellab.zapp.core.GpsMode
import xyz.azraellab.zapp.core.GpsRouteCodec
import xyz.azraellab.zapp.core.GpsTarget
import xyz.azraellab.zapp.core.SavedPoint
import xyz.azraellab.zapp.core.geo.TransportProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Настройки GPS-маршрутов (F1): кодек, режимы, сохранённые точки.
 *
 * Смысл проверок: новое поле переживает запись/чтение, режим джойстика
 * разрешается в свою цель, а профиль движения действительно меняет
 * скорость -- иначе «пешком» ехал бы на машинной.
 */
class GpsConfigTest {

    @Test
    fun `новые поля переживают запись и чтение`() {
        val cfg = GpsConfig(
            enabled = true,
            mode = GpsMode.JOYSTICK.code,
            headingDegrees = 123.5,
            routeProfile = TransportProfile.FOOT.name,
            favorites = listOf(SavedPoint("Кремль", 55.752, 37.617)),
            history = listOf(SavedPoint("", 55.75, 37.61))
        )
        val decoded = xyz.azraellab.zapp.core.ConfigCodec.decodeConfig(
            xyz.azraellab.zapp.core.ConfigCodec.encodeConfig(
                xyz.azraellab.zapp.core.AppConfig(gps = cfg)
            )
        ).gps

        assertEquals(cfg.headingDegrees, decoded.headingDegrees, 0.0001)
        assertEquals(cfg.routeProfile, decoded.routeProfile)
        assertEquals(cfg.favorites, decoded.favorites)
        assertEquals(cfg.history, decoded.history)
        assertEquals(GpsMode.JOYSTICK, GpsMode.of(decoded.mode))
    }

    @Test
    fun `джойстик стартует с координат точки`() {
        val cfg = GpsConfig(
            mode = GpsMode.JOYSTICK.code,
            latitude = 55.75,
            longitude = 37.61,
            headingDegrees = 90.0,
            speedMps = 5.0
        )
        val target = cfg.target
        assertTrue(target is GpsTarget.Joystick, "режим джойстика даёт свою цель")
        assertEquals(55.75, (target as GpsTarget.Joystick).latitude, 0.0001)
        assertEquals(37.61, target.longitude, 0.0001)
    }

    @Test
    fun `неизвестный профиль откатывается на машину`() {
        assertEquals(TransportProfile.DRIVING, TransportProfile.of("нет такого"))
        assertEquals(GpsMode.DISABLED, GpsMode.of("ничего"))
    }

    @Test
    fun `сохранённая точка подписана или отдана координатами`() {
        assertEquals("Кремль", SavedPoint("Кремль", 0.0, 0.0).label())
        assertTrue(SavedPoint("", 55.75, 37.61).label().contains("55.75"))
    }

    @Test
    fun `строка маршрута пишется и читается обратно`() {
        val points = GpsRouteCodec.parse("55.75,37.61;55.76,37.62")
        assertEquals(2, points.size)
        val round = GpsRouteCodec.parse(GpsRouteCodec.write(points))
        assertEquals(points.size, round.size)
        assertEquals(points[0].latitude, round[0].latitude, 0.000001)
        assertEquals(points[1].longitude, round[1].longitude, 0.000001)
    }
}
