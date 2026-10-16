package xyz.azraellab.zapp.core.geo

import xyz.azraellab.zapp.core.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Парсеры Photon и OSRM: чистая часть GPS-маршрутов.
 *
 * Оба сервиса отдают JSON, который не меняется у них под ногами, поэтому
 * тесты гоняются на фиксированных ответах, без сети: если формат изменится,
 * упадёт здесь, а не на устройстве у пользователя.
 */
class GeoParseTest {

    // --- Photon: GeoJSON FeatureCollection ---

    private val photonAnswer = """
        {
          "features": [
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [37.6173, 55.7558]},
              "properties": {
                "name": "Красная площадь",
                "city": "Москва",
                "country": "Россия",
                "street": "Красная площадь",
                "state": "Москва"
              }
            },
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [139.6917, 35.6895]},
              "properties": {"name": "Tokyo Station", "country": "Japan"}
            },
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [999.0, 5.0]},
              "properties": {"name": "Вне земли"}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `photon собирает подпись адресом`() {
        val places = parsePhoton(photonAnswer)
        assertEquals(2, places.size, "запись с координатами вне земли отбрасывается")

        val first = places[0]
        assertEquals(55.7558, first.latitude, 0.0001)
        assertEquals(37.6173, first.longitude, 0.0001)
        assertEquals("Москва, Россия", first.label, "город сверху, страна снизу")
        assertTrue(first.details.contains("Красная площадь"), "улица уходит в details")
    }

    @Test
    fun `photon без города опускается на имя`() {
        val second = parsePhoton(photonAnswer)[1]
        assertEquals("Japan", second.label)
        assertEquals(35.6895, second.latitude, 0.0001)
    }

    @Test
    fun `photon переживает мусор и пустой ответ`() {
        assertTrue(parsePhoton("").isEmpty())
        assertTrue(parsePhoton("{не json}").isEmpty())
        assertTrue(parsePhoton("{}").isEmpty())
    }

    @Test
    fun `url кодирует пробелы и кириллицу`() {
        assertEquals("moscow", urlEncode("moscow"))
        assertEquals("a%20b", urlEncode("a b"))
        // Кириллица -- два байта в UTF-8, оба percent-encoded.
        assertEquals("%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0", urlEncode("Москва"))
        assertEquals("a%26b%3Dc", urlEncode("a&b=c"))
    }

    // --- OSRM: маршрут по дорогам ---

    private val osrmAnswer = """
        {
          "code": "Ok",
          "routes": [
            {
              "distance": 1234.5,
              "duration": 300.2,
              "geometry": {
                "type": "LineString",
                "coordinates": [[37.6, 55.7], [37.61, 55.71], [37.62, 55.72]]
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `osrm разбирает геометрию и считает скорости`() {
        val requested = listOf(
            RoutePoint(latitude = 55.7, longitude = 37.6, speedMps = 5.0),
            RoutePoint(latitude = 55.72, longitude = 37.62, speedMps = null)
        )
        val route = parseOsrm(osrmAnswer, requested)
        assertNotNull(route)
        assertEquals(3, route.points.size)
        assertEquals(1234.5, route.distanceMeters, 0.001)
        assertEquals(300.2, route.durationSeconds, 0.001)
        // Концы -- скорости из запроса, середина -- без скорости (профиль позже).
        assertEquals(5.0, route.points.first().speedMps!!, 0.0001)
        assertEquals(5.0, route.points.last().speedMps!!, 0.0001)
        assertNull(route.points[1].speedMps, "середина наследует скорость профиля")
    }

    @Test
    fun `osrm отказывает без дороги`() {
        val requested = listOf(
            RoutePoint(latitude = 0.0, longitude = 0.0),
            RoutePoint(latitude = 1.0, longitude = 1.0)
        )
        assertNull(parseOsrm("""{"code":"NoRoute"}""", requested))
        assertNull(parseOsrm("мусор", requested))
        assertNull(parseOsrm(osrmAnswer, requested.take(1)), "меньше двух точек -- не маршрут")
    }

    @Test
    fun `профиль движения кодируется в url и скорость по умолчанию`() {
        assertEquals("foot", TransportProfile.FOOT.osrmCode)
        assertEquals(1.4, TransportProfile.FOOT.defaultSpeedMps, 0.001)
        assertEquals(4.2, TransportProfile.CYCLING.defaultSpeedMps, 0.001)
        assertEquals(16.7, TransportProfile.DRIVING.defaultSpeedMps, 0.001)
        // Неизвестный код -- машина, а не падение.
        assertEquals(TransportProfile.DRIVING, TransportProfile.of(null))
        assertEquals(TransportProfile.FOOT, TransportProfile.of("FOOT"))
    }
}
