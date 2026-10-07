package xyz.azraellab.zapp.core.net

/**
 * Счётчики туннеля из clash_api sing-box'а.
 *
 * Единственный источник трафика, не зависящий ни от root, ни от прав на
 * /proc: сам box владеет tun и отдаёт накопленные байты на
 * `127.0.0.1:<port>/traffic`. Парсер -- чистая функция (тестируется),
 * сам запрос живёт в jvmShared.
 */
data class ClashTraffic(val up: Long, val down: Long)

/** Разбор ответа clash_api; null, если тело непохоже на трафик. */
object ClashStats {

    /** Накопительные байты up/down из тела ответа `/traffic`. */
    fun parse(body: String): ClashTraffic? {
        val up = field(body, "up") ?: return null
        val down = field(body, "down") ?: return null
        if (up < 0 || down < 0) return null
        return ClashTraffic(up, down)
    }

    /** Значение числового поля: `"up": 123` или `"up":123`. */
    private fun field(body: String, key: String): Long? {
        val marker = "\"$key\""
        val idx = body.indexOf(marker)
        if (idx < 0) return null
        val rest = body.substring(idx + marker.length)
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        var start = colon + 1
        while (start < rest.length && rest[start].isWhitespace()) start++
        val comma = rest.indexOf(',', start)
        val brace = rest.indexOf('}', start)
        val end = when {
            comma >= 0 && brace >= 0 -> minOf(comma, brace)
            comma >= 0 -> comma
            brace >= 0 -> brace
            else -> rest.length
        }
        if (end <= start) return null
        return rest.substring(start, end).trim().toLongOrNull()
    }
}

/**
 * Текущий счётчик туннеля из clash_api sing-box'а.
 *
 * null, если api выключен, box не запущен или не отвечает: тикер в этом
 * случае падает на интерфейсные счётчики.
 */
expect suspend fun fetchClashTraffic(port: Int, secret: String, timeoutMs: Int): ClashTraffic?