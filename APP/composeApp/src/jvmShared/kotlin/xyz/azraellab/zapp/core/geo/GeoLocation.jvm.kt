package xyz.azraellab.zapp.core.geo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.net.httpGet

/**
 * Геолокация через ipwho.is: HTTPS, без ключа и регистрации, отдаёт
 * широту/долготу/город/страну одним запросом.
 *
 * Запрос идёт в IO-диспетчере: сетевой вызов из UI-корутины заблокировал бы
 * кадр, а здесь он ещё и по своей природе фоновый.
 */
actual suspend fun geolocate(address: String): GeoPoint? = withContext(Dispatchers.IO) {
    if (address.isBlank()) return@withContext null
    val body = httpGet("https://ipwho.is/$address") ?: return@withContext null
    parseGeo(body)
}

private val GEO_JSON = Json { ignoreUnknownKeys = true }

private fun parseGeo(body: String): GeoPoint? = runCatching {
    val root = GEO_JSON.parseToJsonElement(body).jsonObject
    // success == false -- сервис понял запрос, но адрес не определился.
    val success = root["success"]?.jsonPrimitive?.contentOrNullCompat()
    if (success == "false") return null

    fun field(name: String): String = root[name]?.jsonPrimitive?.contentOrNullCompat() ?: ""
    val lat = field("latitude").toDoubleOrNull() ?: return null
    val lon = field("longitude").toDoubleOrNull() ?: return null
    GeoPoint(latitude = lat, longitude = lon, city = field("city"), country = field("country"))
}.getOrNull()

/** Явный доступ к content без выброса, если вместо строки пришёл объект. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullCompat(): String? =
    runCatching { content }.getOrNull()
