package xyz.azraellab.zapp.core

/**
 * Мост Tor, разобранный из строки профиля.
 *
 * Профиль -- это список строк вида `Bridge <transport> <host>:<port> <fp> ...`
 * (или без префикса Bridge и без транспорта -- vanilla). Для вкладки Tor
 * нужен сам список мостов, а не только профиль целиком: пользователь
 * должен видеть, какими адресами идёт трафик.
 */
data class TorBridge(
    /** Название транспорта ("obfs4", "webtunnel"...); пусто -- vanilla/TCP. */
    val transport: String,
    val host: String,
    val port: Int,
) {
    /** Отображаемый адрес: IPv6 оборачивается в скобки, как в torrc. */
    val endpoint: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

/**
 * Разбор bridge-строк профиля в [TorBridge].
 *
 * Только парсинг, без сети и состояния: одна и та же строка всегда даёт
 * один и тот же результат. Строки, которые tor принял бы, но в списке
 * показать нечего (URL вместо адреса, мусор), пропускаются -- ложная
 * строка в списке хуже, чем отсутствующая.
 */
object TorBridges {

    /** Транспорты, которые клиент умеет поднять своим бинарём lyrebird. */
    private val TRANSPORTS = setOf(
        "obfs4", "snowflake", "webtunnel", "scramblesuit", "meek_lite", "meek", "moat"
    )

    fun parse(rawConfig: String): List<TorBridge> {
        val out = ArrayList<TorBridge>()
        for (raw in rawConfig.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val body = if (line.startsWith("Bridge ", ignoreCase = true)) {
                line.substring(7).trim()
            } else {
                line
            }
            val tokens = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val first = tokens[0].lowercase()
            val hasTransport = first in TRANSPORTS
            val rest = if (hasTransport) tokens.drop(1) else tokens
            val endpoint = rest.firstOrNull() ?: continue
            val (host, port) = splitEndpoint(endpoint) ?: continue
            out += TorBridge(if (hasTransport) first else "", host, port)
        }
        return out
    }

    /**
     * `host:443` или `[::1]:443` -> адрес и порт; null -- это не адрес
     * (URL, кривая строка), он в списке не нужен.
     */
    private fun splitEndpoint(token: String): Pair<String, Int>? {
        if (token.startsWith("[")) {
            val close = token.indexOf("]:")
            if (close <= 1) return null
            val host = token.substring(1, close)
            val port = token.substring(close + 2).toIntOrNull() ?: return null
            if (port !in 1..65535 || host.isEmpty()) return null
            return host to port
        }
        val idx = token.lastIndexOf(':')
        if (idx <= 0 || idx != token.indexOf(':')) return null
        val port = token.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val host = token.substring(0, idx)
        if (host.isEmpty()) return null
        return host to port
    }
}
