package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable

/**
 * Настройки подмены геолокации.
 *
 * Есть два принципиально разных сценария: точка на карте и маршрут. Для
 * точки достаточно координат; для маршрута нужен список точек, который
 * приложение проигрывает с заданной скоростью. [GpsMode] выбирает, что из
 * этого используется, чтобы не держать в форме поля, которые сейчас не
 * имеют смысла.
 */
@Serializable
data class GpsConfig(
    val enabled: Boolean = false,
    val mode: String = GpsMode.FIXED.code,

    // --- Точка ---
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,

    // --- Скорость и движение ---
    /** Скорость в метрах в секунду. Ноль -- стоять на месте. */
    val speedMps: Double = 0.0,
    /** Случайный разброс скорости, проценты. */
    val speedJitterPercent: Int = 0,
    /** Случайный разброс координат, метры. */
    val positionJitterMeters: Double = 0.0,
    /** Дрейф координат по направлению движения. */
    val driftMetersPerSecond: Double = 0.0,

    // --- Маршрут ---
    val route: String = "",
    /** Проигрывать маршрут по кругу. */
    val routeLoop: Boolean = false,
    /** Пауза на концах маршрута, секунды. */
    val routeDwellSeconds: Int = 0,

    // --- Точность и провайдер ---
    /** Заявленная точность, метры. */
    val accuracyMeters: Double = 5.0,
    /** Сколько точек сглаживать перед выдачей. */
    val smoothingSamples: Int = 1,
    /** Отдавать фиктивное время фиксации, секунды назад. */
    val mockElapsedSeconds: Int = 2,
    /** Переустанавливать провайдера после смены режима. */
    val reinstallProvider: Boolean = true,
    /** Разрешить геолокацию только для выбранных приложений. */
    val perApp: Boolean = false,
    val apps: String = ""
) {
    fun appList(): List<String> = apps.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun routePoints(): List<RoutePoint> = GpsRouteCodec.parse(route)

    val target: GpsTarget
        get() = when (GpsMode.of(mode)) {
            GpsMode.FIXED -> GpsTarget.Fixed(latitude, longitude)
            GpsMode.ROUTE -> GpsTarget.Route(routePoints())
            // Координаты VPN -- единственная точка, которой ещё нет: её
            // даёт не конфиг, а геолокация адреса шлюза, поэтому движок
            // разрешает её сам при старте.
            GpsMode.VPN_LOCATION -> GpsTarget.VpnLocation
            GpsMode.DISABLED -> GpsTarget.Off
        }
}

/** Сценарий подмены. */
enum class GpsMode(val code: String, val ru: String, val en: String) {
    DISABLED("disabled", "Выключено", "Disabled"),
    FIXED("fixed", "Точка", "Fixed point"),
    ROUTE("route", "Маршрут", "Route"),

    /**
     * Координаты там, где находится шлюз VPN.
     *
     * Сценарий: туннель уходит в другой город или страну, а приложения
     * карт должны показывать ту же точку, иначе локация и трафик
     * противоречат друг другу. Точка вычисляется при старте по адресу
     * шлюза и дальше ведёт себя как обычная фиксированная.
     */
    VPN_LOCATION("vpn", "Координаты VPN", "VPN location");

    companion object {
        fun of(code: String?): GpsMode =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: DISABLED
    }
}

/** Точка маршрута. */
@Serializable
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    /** Скорость в этой точке; null -- взять общую [GpsConfig.speedMps]. */
    val speedMps: Double? = null,
    /** Задержка перед переходом к следующей точке, секунды. */
    val dwellSeconds: Int = 0
) {
    fun distanceTo(other: RoutePoint): Double {
        val r = 6_371_000.0
        val dLat = (other.latitude - latitude) * Math.PI / 180.0
        val dLon = (other.longitude - longitude) * Math.PI / 180.0
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(latitude * Math.PI / 180.0) * Math.cos(other.latitude * Math.PI / 180.0) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}

/** Что именно подменяет приложение. */
sealed interface GpsTarget {
    data object Off : GpsTarget
    data class Fixed(val latitude: Double, val longitude: Double) : GpsTarget
    data class Route(val points: List<RoutePoint>) : GpsTarget

    /** Точка ещё не разрешена: движок найдёт её по адресу шлюза. */
    data object VpnLocation : GpsTarget
}

/** Разбор и запись строки маршрута. */
object GpsRouteCodec {
    /**
     * Формат строки: `широта,долгота[,скорость[,пауза]]`, точки через `;`.
     * Разделители пробелы и переносы строк тоже допускаются.
     */
    fun parse(raw: String): List<RoutePoint> = raw
        .split(';', '\n')
        .mapNotNull { chunk -> parsePoint(chunk) }
        .toList()

    private fun parsePoint(chunk: String): RoutePoint? {
        val parts = chunk.split(',', ';').map { it.trim() }
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return RoutePoint(
            latitude = lat,
            longitude = lon,
            speedMps = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
            dwellSeconds = parts.getOrNull(3)?.toIntOrNull() ?: 0
        )
    }

    fun write(points: List<RoutePoint>): String = points.joinToString(";") { p ->
        buildString {
            append(p.latitude)
            append(',')
            append(p.longitude)
            p.speedMps?.let {
                append(',')
                append(it)
            }
            if (p.dwellSeconds != 0) {
                if (p.speedMps == null) append(',')
                append(',')
                append(p.dwellSeconds)
            }
        }
    }

    fun isValid(points: List<RoutePoint>): Boolean = points.size >= 2
}
