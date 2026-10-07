package xyz.azraellab.zapp.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Запрос счётчиков к запущенному sing-box.
 *
 * loopback доступен приложению всегда: это его собственный процесс, tun его
 * не касается. Ответ отдаёт накопленные байты с момента старта box'а --
 * скорость считается дельтами в тикере.
 *
 * Голый сокет вместо `httpGet`: clash_api слушает только `http://`, а с
 * targetSdk 28+ cleartext запрещён политикой сети (network security config)
 * и для loopback -- `HttpURLConnection` в приложении падал на этой проверке
 * молча, часами не доходя до таймаута. Политика к голым сокетам не относится,
 * поэтому здесь и сокет, а не HTTP-клиент. Ответ `/traffic` -- это стрим
 * JSON-событий; хватает одного события и отдаём парсеру.
 */
actual suspend fun fetchClashTraffic(
    port: Int,
    secret: String,
    timeoutMs: Int
): ClashTraffic? = withContext(Dispatchers.IO) {
    val p = port.coerceIn(1, 65535)
    try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", p), timeoutMs)
            socket.soTimeout = timeoutMs
            val request = buildString {
                append("GET /traffic HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$p\r\n")
                if (secret.isNotBlank()) append("Authorization: Bearer $secret\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                val text = buffer.toString()
                // Стрим событий `{"up":N,"down":N}`: хватило одного, чтобы
                // парсеру не попали следующие события (между ними -- пауза).
                if (text.indexOf("\"up\"") >= 0 && text.indexOf("\"down\"") >= 0) break
            }
            ClashStats.parse(buffer.toString())
        }
    } catch (_: Exception) {
        null
    }
}