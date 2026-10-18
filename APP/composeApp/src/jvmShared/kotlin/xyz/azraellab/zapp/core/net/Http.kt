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
fun httpGet(
    url: String,
    timeoutMs: Int = HTTP_TIMEOUT_MS,
    headers: Map<String, String> = emptyMap()
): String? {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
        ?: return null
    return try {
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "zapp")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
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

/** POST JSON-тела; null -- сети нет или сервер ответил не 2xx. */
fun httpPost(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    timeoutMs: Int = HTTP_TIMEOUT_MS
): String? {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
        ?: return null
    return try {
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", "zapp")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) return null
        connection.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

/**
 * GET байтами -- для архивов, где тело не текст.
 *
 * Таймаут чтения длиннее текстового: архив ядра -- десятки мегабайт,
 * и медленная сеть не должна рвать загрузку посреди файла.
 */
fun httpGetRaw(
    url: String,
    readTimeoutMs: Int = 120_000
): ByteArray? {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
        ?: return null
    return try {
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "zapp")
        val code = connection.responseCode
        if (code !in 200..299) return null
        connection.inputStream.use { it.readBytes() }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}
