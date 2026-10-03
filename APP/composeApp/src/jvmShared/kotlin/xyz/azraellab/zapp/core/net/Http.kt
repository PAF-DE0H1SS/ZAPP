package xyz.azraellab.zapp.core.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * Один GET-запрос наружу.
 *
 * Общий для Android и десктопа, потому что обе платформы -- JVM: здесь
 * нет ни Context, ни платформенных различий, только HttpURLConnection.
 *
 * Таймауты жёсткие во всех трёх точках: обновления и геолокация -- фоновые
 * операции, и зависший запрос не должен держать корутину вечно.
 */
fun httpGet(url: String, timeoutMs: Int = HTTP_TIMEOUT_MS): String? {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
        ?: return null
    return try {
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "zapp")
        val code = connection.responseCode
        if (code !in 200..299) return null
        connection.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

/** Таймаут по умолчанию: сервер обновлений обязан ответить быстрее. */
const val HTTP_TIMEOUT_MS: Int = 8_000
