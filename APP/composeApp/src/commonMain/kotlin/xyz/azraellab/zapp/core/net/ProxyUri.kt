package xyz.azraellab.zapp.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Разбор и сборка ссылок конфигов: vless, vmess, trojan, ss, hysteria2.
 *
 * Ссылка -- основной формат обмена: её копируют из приложений, бросают в
 * чат и кладут в подписку. Поэтому разбор здесь живёт в common-коде и не
 * зависит от `java.net.URI`: тот, например, не умеет `vmess://`, где после
 * схемы лежит не authority, а base64 с JSON внутри.
 *
 * Правило разбора одно: не терять. Всё, что не разложено по полям, остаётся
 * в `params` и возвращается в ссылку при обратной сборке.
 */
object ProxyUri {

    private val KNOWN_KEYS = setOf(
        "security", "type", "sni", "peer", "path", "host", "alpn", "fp", "flow"
    )

    /** Разбор JSON в vmess-ссылке: чужие поля не ошибка, а норма. */
    private val VMESS_JSON = Json { ignoreUnknownKeys = true }

    private const val HEX = "0123456789ABCDEF"

    /** Разбирает одну ссылку. `null` -- не ссылка или неизвестная схема. */
    fun parse(line: String): ProxyConfig? {
        val text = line.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = ProxyScheme.from(text.substring(0, schemeEnd)) ?: return null
        val rest = text.substring(schemeEnd + 3)

        val (body, fragment) = splitOnce(rest, '#')
        val (authority, query) = splitOnce(body, '?')
        val params = parseQuery(query)

        return when (scheme) {
            ProxyScheme.VMESS -> parseVmess(text, params)
            ProxyScheme.SHADOWSOCKS -> parseShadowsocks(authority, params, fragment, text)
            ProxyScheme.HTTP -> {
                // Обычная ссылка на подписку -- не прокси: у прокси всегда
                // есть пользователь. Без userinfo пускаем дальше -- вызывающий
                // разберёт строку как URL подписки.
                if ('@' !in authority) null
                else parseAuthorityScheme(scheme, authority, params, fragment, text)
            }
            else -> parseAuthorityScheme(scheme, authority, params, fragment, text)
        }
    }

    /**
     * Разбирает текст целиком: подписку, вставленный список, буфер обмена.
     *
     * Подписки бывают двух видов: построчный список ссылок и весь список,
     * закодированный в base64. Встречаются и строки, каждая из которых сама
     * закодирована в base64, -- разбор идёт с запасом на оба случая.
     * Дубликаты отбрасываются по ссылке, порядок сохраняется.
     */
    fun parseAll(text: String): List<ProxyConfig> {
        val result = LinkedHashMap<String, ProxyConfig>()
        collect(text, result)

        if (result.isEmpty()) {
            decodeWholeAsBase64(text)?.let { body ->
                if (body != text) collect(body, result)
            }
        }

        return result.values.toList()
    }

    /**
     * Проходит по строкам, попутно распаковывая закодированные.
     *
     * Рекурсия нужна ровно один раз: строка подписки сама может быть
     * base64 от целого списка, и разбирать такой список надо строками,
     * а не одной ссылкой -- иначе вторая и следующие ссылки превратятся
     * в хвост имени первой.
     */
    private fun collect(text: String, into: MutableMap<String, ProxyConfig>) {
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val candidate = decodeLineIfEncoded(line)
            if (candidate.contains('\n')) {
                collect(candidate, into)
                continue
            }
            parse(candidate)?.let { into.putIfAbsent(it.raw, it) }
        }
    }

    /** Собирает ссылку обратно. Типы, которых нет в полях, уходят из `params`. */
    fun write(config: ProxyConfig): String = when (config.scheme) {
        ProxyScheme.VMESS -> "vmess://" + encodeVmess(config)
        ProxyScheme.SHADOWSOCKS -> writeShadowsocks(config)
        else -> writeAuthorityScheme(config)
    }

    // --- Общие части URL ---

    private fun parseAuthorityScheme(
        scheme: ProxyScheme,
        authority: String,
        params: Map<String, String>,
        fragment: String,
        raw: String
    ): ProxyConfig {
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val (host, port) = splitHostPort(hostPort)

        val security = params["security"]?.lowercase()
            ?: if (scheme == ProxyScheme.TROJAN) "tls" else ""

        return ProxyConfig(
            raw = raw,
            scheme = scheme,
            name = percentDecode(fragment),
            server = host,
            port = port,
            auth = percentDecode(userInfo),
            transport = params["type"]?.lowercase() ?: "tcp",
            security = security,
            sni = params["sni"] ?: params["peer"] ?: "",
            host = params["host"] ?: "",
            path = percentDecode(params["path"] ?: ""),
            alpn = params["alpn"] ?: "",
            fingerprint = params["fp"] ?: "",
            flow = params["flow"] ?: "",
            params = params.filterKeys { it !in KNOWN_KEYS }
        )
    }

    private fun writeAuthorityScheme(config: ProxyConfig): String {
        val userInfo = config.auth

        val query = LinkedHashMap<String, String>()
        query.putAll(config.params)
        if (config.transport != "tcp") query["type"] = config.transport
        config.security.takeIf { it.isNotBlank() }?.let { query["security"] = it }
        config.sni.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        config.host.takeIf { it.isNotBlank() }?.let { query["host"] = it }
        config.path.takeIf { it.isNotBlank() }?.let { query["path"] = it }
        config.alpn.takeIf { it.isNotBlank() }?.let { query["alpn"] = it }
        config.fingerprint.takeIf { it.isNotBlank() }?.let { query["fp"] = it }
        config.flow.takeIf { it.isNotBlank() }?.let { query["flow"] = it }

        val host = if (':' in config.server) "[${config.server}]" else config.server
        val sb = StringBuilder()
            .append(config.scheme.uri).append("://")
            .append(userInfo).append('@')
            .append(host).append(':').append(config.port)
        if (query.isNotEmpty()) {
            sb.append('?')
            query.entries.joinTo(sb, "&") { (k, v) -> "${k}=${percentEncode(v)}" }
        }
        if (config.name.isNotBlank()) {
            sb.append('#').append(percentEncode(config.name))
        }
        return sb.toString()
    }

    // --- Shadowsocks: две формы ссылки ---

    private fun parseShadowsocks(
        authority: String,
        params: Map<String, String>,
        fragment: String,
        raw: String
    ): ProxyConfig? {
        val at = authority.lastIndexOf('@')
        if (at >= 0) {
            // Форма ss://base64(method:password)@host:port#name
            val encoded = authority.substring(0, at)
            val decoded = FlexibleBase64.decodeToStringOrNull(encoded) ?: return null
            val (cipher, password) = splitAuth(decoded)
            val (host, port) = splitHostPort(authority.substring(at + 1))
            return ProxyConfig(
                raw = raw,
                scheme = ProxyScheme.SHADOWSOCKS,
                name = percentDecode(fragment),
                server = host,
                port = port,
                auth = password,
                cipher = cipher,
                params = params
            )
        }

        // Форма ss://base64(method:password@host:port)#name
        val decoded = FlexibleBase64.decodeToStringOrNull(authority) ?: return null
        val atDecoded = decoded.lastIndexOf('@')
        if (atDecoded <= 0) return null
        val (cipher, password) = splitAuth(decoded.substring(0, atDecoded))
        val (host, port) = splitHostPort(decoded.substring(atDecoded + 1))
        return ProxyConfig(
            raw = raw,
            scheme = ProxyScheme.SHADOWSOCKS,
            name = percentDecode(fragment),
            server = host,
            port = port,
            auth = password,
            cipher = cipher,
            params = params
        )
    }

    private fun writeShadowsocks(config: ProxyConfig): String {
        val userInfo = FlexibleBase64.encodeNoPadding("${config.cipher}:${config.auth}")
        val host = if (':' in config.server) "[${config.server}]" else config.server
        val sb = StringBuilder()
            .append("ss://").append(userInfo).append('@')
            .append(host).append(':').append(config.port)
        if (config.params.isNotEmpty()) {
            sb.append('?')
            config.params.entries.joinTo(sb, "&") { (k, v) -> "${k}=${percentEncode(v)}" }
        }
        if (config.name.isNotBlank()) sb.append('#').append(percentEncode(config.name))
        return sb.toString()
    }

    // --- Vmess: base64 от JSON ---

    private fun parseVmess(raw: String, fallbackParams: Map<String, String>): ProxyConfig? {
        val body = raw.substring("vmess://".length)
        val decoded = FlexibleBase64.decodeToStringOrNull(body) ?: return null
        val json = runCatching {
            VMESS_JSON.parseToJsonElement(decoded) as? JsonObject
        }.getOrNull() ?: return null

        fun str(key: String): String = json[key]?.let {
            if (it is JsonPrimitive) it.content else null
        } ?: ""

        val port = str("port").toIntOrNull() ?: 0
        val params = buildMap {
            putAll(fallbackParams)
            str("aid").takeIf { it.isNotBlank() }?.let { put("alterId", it) }
            str("scy").takeIf { it.isNotBlank() }?.let { put("security-cipher", it) }
            str("obfs").takeIf { it.isNotBlank() }?.let { put("obfs", it) }
            str("obfsParam").takeIf { it.isNotBlank() }?.let { put("obfs-password", it) }
        }

        return ProxyConfig(
            raw = raw,
            scheme = ProxyScheme.VMESS,
            name = str("ps"),
            server = str("add"),
            port = port,
            auth = str("id"),
            cipher = str("scy").ifBlank { "auto" },
            transport = str("net").ifBlank { "tcp" },
            security = str("tls"),
            sni = str("sni"),
            host = str("host"),
            path = percentDecode(str("path")),
            alpn = str("alpn"),
            fingerprint = str("fp"),
            params = params
        )
    }

    private fun encodeVmess(config: ProxyConfig): String {
        val json = buildJsonObject {
            put("v", "2")
            put("ps", config.name)
            put("add", config.server)
            put("port", config.port.toString())
            put("id", config.auth)
            put("aid", config.params["alterId"] ?: "0")
            put("scy", config.cipher.ifBlank { "auto" })
            put("net", config.transport)
            put("type", config.params["type"] ?: "none")
            put("host", config.host)
            put("path", config.path)
            put("tls", config.security)
            put("sni", config.sni)
            put("fp", config.fingerprint)
            put("alpn", config.alpn)
        }
        return FlexibleBase64.encodeNoPadding(json.toString())
    }

    // --- Мелкая механика URL ---

    private fun parseQuery(query: String): Map<String, String> =
        if (query.isBlank()) emptyMap()
        else query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val (key, value) = splitOnce(pair, '=')
            percentDecode(key) to percentDecode(value)
        }.toMap()

    /** `host:port` и `[ipv6]:port`; без порта -- 0, дальше решает вызывающий. */
    private fun splitHostPort(text: String): Pair<String, Int> {
        val hostPort = text.substringBefore('/')
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close > 0) {
                val host = hostPort.substring(1, close)
                val port = hostPort.substring(close + 1).removePrefix(":").toIntOrNull() ?: 0
                return host to port
            }
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return hostPort to 0
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: 0
        return hostPort.substring(0, colon) to port
    }

    private fun splitAuth(text: String): Pair<String, String> {
        val colon = text.indexOf(':')
        return if (colon < 0) text to "" else text.substring(0, colon) to text.substring(colon + 1)
    }

    private fun splitOnce(text: String, separator: Char): Pair<String, String> {
        val index = text.indexOf(separator)
        return if (index < 0) text to "" else text.substring(0, index) to text.substring(index + 1)
    }

    private fun decodeLineIfEncoded(line: String): String {
        if (line.contains("://")) return line
        val decoded = FlexibleBase64.decodeToStringOrNull(line) ?: return line
        return if (decoded.contains("://")) decoded else line
    }

    private fun decodeWholeAsBase64(text: String): String? {
        val compact = text.replace(Regex("\\s"), "")
        return FlexibleBase64.decodeToStringOrNull(compact)?.takeIf { it.contains("://") }
    }

    /**
     * Percent-decoding.
     *
     * `java.net.URLDecoder` здесь не годится: он превращает `+` в пробел,
     * а в путях WebSocket `+` -- обычный символ.
     */
    fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '%' && index + 2 < text.length) {
                val hex = text.substring(index + 1, index + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    bytes.add(value.toByte())
                    index += 3
                    continue
                }
            }
            bytes.addAll(char.toString().encodeToByteArray().toList())
            index++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun percentEncode(text: String): String {
        val sb = StringBuilder()
        for (byte in text.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in SAFE_CHARS || value < 0x80 && char.isLetterOrDigit()) {
                sb.append(char)
            } else {
                sb.append('%')
                    .append(HEX[value shr 4])
                    .append(HEX[value and 0x0F])
            }
        }
        return sb.toString()
    }

    /**
     * Символы, которые не кодируются.
     *
     * Сюда входят разделители URL (`:/?#[]@`) и символы, значимые для
     * запроса (`&`, `=`, `+`): без их сохранения обратная сборка сломает
     * ссылку, а `+` -- не пробел, если его не кодировать, приёмник прочтёт
     * путь WebSocket неверно.
     */
    private const val SAFE_CHARS = "-_.~!$&'()*+,;=:/?#[]@"
}
