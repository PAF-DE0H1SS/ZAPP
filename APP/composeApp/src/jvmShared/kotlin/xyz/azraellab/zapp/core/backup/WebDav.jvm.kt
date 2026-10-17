package xyz.azraellab.zapp.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** PUT байтов; false -- сервер ответил не 2xx или сети нет. */
actual suspend fun webDavPut(url: String, auth: String, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return@withContext false
        try {
            conn.connectTimeout = WEBDAV_TIMEOUT_MS
            conn.readTimeout = WEBDAV_TIMEOUT_MS
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", auth)
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

/** GET байтов; null -- нет файла или сервер недоступен. */
actual suspend fun webDavGet(url: String, auth: String): ByteArray? =
    withContext(Dispatchers.IO) {
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return@withContext null
        try {
            conn.connectTimeout = WEBDAV_TIMEOUT_MS
            conn.readTimeout = WEBDAV_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", auth)
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

private const val WEBDAV_TIMEOUT_MS = 15_000
