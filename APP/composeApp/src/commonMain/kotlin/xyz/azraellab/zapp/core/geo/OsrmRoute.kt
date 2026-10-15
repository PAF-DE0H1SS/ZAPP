package xyz.azraellab.zapp.core.geo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.RoutePoint
import xyz.azraellab.zapp.core.net.httpGetText

/**
 * Маршрут, построенный внешним сервисом.
 *
 * [points] -- ломаная по дорогам (плотная: OSRM отдаёт геометрию с
 * шагом в несколько десятков метров), [distanceMeters] и [durationSeconds] --
 * то, что сам сервис насчитал: их показывают пользователю, а не
 * пересчитывают, потому что сервис знает про дороги, развязки и
 * повороты через разворот, а ломаная -- нет.
 */
data class Route(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

/** Профиль движения для OSRM и его разумная скорость, м/с. */
enum class TransportProfile(
    val osrmCode: String,
    val defaultSpeedMps: Double,
    val ru: String,
    val en: String
) {
    FOOT("foot", 1.4, "Пешком", "Foot"),
    CYCLING("cycling", 4.2, "На велосипеде", "Bicycle"),
    DRIVING("driving", 16.7, "На машине", "Car");

    companion object {
        fun of(code: String?): TransportProfile =
            entries.firstOrNull { it.name.equals(code, ignoreCase = true) } ?: DRIVING
    }
}

private val OSRM_JSON = Json { ignoreUnknownKeys = true }

/** Публичный инстанс OSRM: без ключа и регистрации, для разовых запросов. */
private const val OSRM_URL = "https://router.project-osrm.org/route/v1/"

/**
 * Строит маршрут по дорогам между точками.
 *
 * null, если сети нет, сервис ответил ошибкой или маршрут не найден
 * (остров, закрытая территория): вызывающий оставляет старые точки
 * и показывает свою строку -- молча подменять маршрут на пустой нельзя.
 */
suspend fun osrmRoute(
    profile: TransportProfile,
    points: List<RoutePoint>
): Route? {
    if (points.size < 2) return null
    // OSRM принимает пары `долгота,широта`, склеенные через `;`.
    val path = points.joinToString(";") { "${it.longitude},${it.latitude}" }
    val url = "$OSRM_URL${profile.osrmCode}/$path?overview=full&geometries=geojson"
    val body = httpGetText(url) ?: return null
    return parseOsrm(body, points)
}

/**
 * Разбор ответа OSRM. Чистая функция: проверяется фиксированным ответом.
 *
 * `code != "Ok"` -- сервис разобрал запрос и отказал (нет дороги);
 * это отличается от отсутствия сети, но результат один -- null.
 */
fun parseOsrm(body: String, requested: List<RoutePoint>): Route? {
    if (requested.size < 2) return null
    return runCatching {
        val root = OSRM_JSON.parseToJsonElement(body).jsonObject
        if (root["code"]?.jsonPrimitive?.content != "Ok") return null

        val routes = root["routes"]?.jsonArray ?: return null
        val best = routes.firstOrNull()?.jsonObject ?: return null

        val distance = best["distance"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val duration = best["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val geometry = best["geometry"]?.jsonObject ?: return null
        val coords = geometry["coordinates"]?.jsonArray ?: return null

        val points = coords.mapNotNull { item ->
            val pair = item.jsonArray
            val lon = pair[0].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            val lat = pair[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            RoutePoint(latitude = lat, longitude = lon)
        }
        if (points.size < 2) return null

        // Скорости кусков маршрута: концы -- скорости введённых точек,
        // середина -- профиль. Без этого весь путь ехал бы с последней
        // введённой скоростью, включая пешие участки.
        Route(
            points = withSpeeds(points, requested, requested.first().speedMps),
            distanceMeters = distance,
            durationSeconds = duration
        )
    }.getOrNull()
}

/** Раскладывает скорости: концы -- из запроса, середина -- профильная. */
private fun withSpeeds(
    points: List<RoutePoint>,
    requested: List<RoutePoint>,
    fallbackSpeed: Double?
): List<RoutePoint> {
    val firstSpeed = requested.first().speedMps ?: fallbackSpeed
    val lastSpeed = requested.last().speedMps ?: firstSpeed
    return points.mapIndexed { index, point ->
        when (index) {
            0 -> point.copy(speedMps = firstSpeed)
            points.lastIndex -> point.copy(speedMps = lastSpeed)
            else -> point
        }
    }
}
