package xyz.azraellab.zapp.core.geo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.net.httpGetText

/**
 * Место, найденное по тексту или по координатам.
 *
 * [label] -- одна строка для списка («Москва, Россия»), [details] --
 * то, что показывается под ней (улица, район). Пустые поля допустимы:
 * Photon не для каждой находки отдаёт адрес полностью.
 */
data class Place(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val details: String = ""
)

private val PHOTON_JSON = Json { ignoreUnknownKeys = true }

/** Базовый адрес Photon: без ключа, без регистрации, без лимита на ключ. */
private const val PHOTON_URL = "https://photon.komoot.io/api/"

/**
 * Поиск места по тексту: «Кремль», «Tokyo station».
 *
 * null, если сети нет или сервис не ответил: вызывающий показывает свою
 * строку. Пустой список -- ответ получен, но ничего не нашлось: это не
 * ошибка, а ввод пользователя. Развод этих двух случаев нужен интерфейсу:
 * «ничего не нашлось» -- не повод писать про сеть.
 */
suspend fun photonSearch(query: String, limit: Int = 6): List<Place>? {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val url = PHOTON_URL + "?q=" + urlEncode(q) + "&limit=" + limit.coerceIn(1, 20)
    val body = httpGetText(url) ?: return null
    return parsePhoton(body)
}

/**
 * Обратный геокодинг: координаты -> адрес.
 *
 * Нужен «истории» и журналу: строка «55.7558, 37.6173» ничего не говорит,
 * а «Красная площадь, Москва» -- говорит. null, если адрес не нашёлся.
 */
suspend fun photonReverse(latitude: Double, longitude: Double): Place? {
    val url = PHOTON_URL + "?lon=" + longitude + "&lat=" + latitude + "&limit=1"
    val body = httpGetText(url) ?: return null
    return parsePhoton(body).firstOrNull()
}

/**
 * Разбор ответа Photon. Чистая функция -- парсер проверяется тестом
 * на фиксированном ответе, без сети.
 *
 * Формат -- GeoJSON FeatureCollection: `features[].geometry.coordinates`
 * (долгота, широта -- в этом порядке, GeoJSON), подписи в properties.
 */
fun parsePhoton(body: String): List<Place> = runCatching {
    val root = PHOTON_JSON.parseToJsonElement(body).jsonObject
    val features = root["features"]?.jsonArray ?: return emptyList()
    features.mapNotNull { feature ->
        val obj = feature.jsonObject
        val coords = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
        val lon = coords[0].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
        val lat = coords[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null

        val props = obj["properties"]?.jsonObject
        fun field(name: String): String =
            props?.get(name)?.jsonPrimitive?.content?.trim().orEmpty()

        // Город сверху, страна снизу: так подпись читается как адрес,
        // а не как набор случайных слов. То, чего нет, не склеивается
        // с лишним разделителем.
        val label = listOf(field("city"), field("country"))
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .ifEmpty { listOf(field("name"), field("state")).filter { it.isNotEmpty() }.joinToString(", ") }
            .ifEmpty { field("name") }

        val details = listOf(field("street"), field("district"), field("state"))
            .filter { it.isNotEmpty() && it != label }
            .joinToString(", ")

        Place(latitude = lat, longitude = lon, label = label.ifEmpty { "$lat, $lon" }, details = details)
    }
}.getOrNull() ?: emptyList()

/** Percent-кодирование без лишних зависимостей: Photon принимает UTF-8. */
internal fun urlEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c.isLetterOrDigit() && c.code < 0x80) {
            append(c)
        } else {
            append('%')
            append(HEX[b shr 4])
            append(HEX[b and 0x0F])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
