package xyz.azraellab.zapp.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.net.httpGetText

/**
 * Определяет регион по своему IP через ipwho.is: без ключа, одним GET,
 * отдаёт ISO-код страны сразу.
 *
 * null, если сети нет, сервис ответил отказом или код неизвестен реестру:
 * вызывающий оставляет регион нетронутым -- нейтральный регион лучше
 * угаданного не в ту сторону.
 */
suspend fun detectRegion(): String? {
    val body = httpGetText("https://ipwho.is/") ?: return null
    return parseCountryCode(body)
}

private val REGION_JSON = Json { ignoreUnknownKeys = true }

/** Чистый разбор ответа ipwho.is в ISO-код; null -- ответ не годится. */
fun parseCountryCode(body: String): String? = runCatching {
    val root = REGION_JSON.parseToJsonElement(body).jsonObject
    // success == false -- сервис понял запрос, но IP не определился.
    val success = root["success"]?.jsonPrimitive?.contentOrNullRegion()
    if (success == "false") return null

    fun field(name: String): String = root[name]?.jsonPrimitive?.contentOrNullRegion() ?: ""
    val code = field("country_code").trim().uppercase()
    // Код обязан жить в реестре: dropdown умеет показать только известные
    // страны, неизвестный код превратился бы в пустой пункт.
    if (code.length != 2) return null
    if (RegionRegistry.byCode(code) == null) return null
    code
}.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullRegion(): String? =
    runCatching { content }.getOrNull()
