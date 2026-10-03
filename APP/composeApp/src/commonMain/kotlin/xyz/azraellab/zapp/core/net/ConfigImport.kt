package xyz.azraellab.zapp.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol

/**
 * Автоопределение формата конфига по тексту.
 *
 * Пользователь не обязан знать, что он скопировал: wg-ini, .ovpn, sing-box
 * JSON, xray JSON, ссылка, список ссылок или адрес подписки. Формат
 * определяется по содержимому, в порядке «что более специфично», и
 * неузнанный текст возвращает как NONE -- без молчаливой угадаловки.
 */
object ConfigImport {

    /** Что вышло из текста. */
    data class Result(
        val kind: Kind,
        val profiles: List<VpnProfile> = emptyList()
    ) {
        companion object {
            val none = Result(Kind.NONE)
        }
    }

    /** Тип импорта. */
    enum class Kind {
        /** Текст не распознан. */
        NONE,

        /** Один конфиг: wg/awg, ovpn, sing-box, xray. */
        PROFILE,

        /** Список ссылок -- из нескольких профилей. */
        LIST,

        /** Адрес подписки: узлы придут при подключении. */
        SUBSCRIPTION
    }

    /** JSON-конфиги разбираем lenient: хвостовые запятые и комментарии -- норма. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Amnezia-параметры, по которым wg-конфиг становится Amnezia WG. */
    private val AWG_KEYS = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5"
    )

    suspend fun import(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.none

        // 1. WireGuard / Amnezia WG: секции -- самый явный признак.
        if (trimmed.contains("[Interface]", ignoreCase = true)) {
            wgProfile(trimmed)?.let { return Result(Kind.PROFILE, listOf(it)) }
        }

        // 2. OpenVPN: служебные ключи, которых нет ни в одном JSON.
        if (looksLikeOvpn(trimmed)) {
            return Result(
                Kind.PROFILE,
                listOf(
                    VpnProfile(
                        protocol = VpnProtocol.OPENVPN.code,
                        rawConfig = trimmed
                    )
                )
            )
        }

        // 3. Tor: строки мостов -- ни JSON, ни ссылки.
        if (looksLikeBridges(trimmed)) {
            return Result(
                Kind.PROFILE,
                listOf(
                    VpnProfile(
                        name = "Tor",
                        protocol = VpnProtocol.TOR.code,
                        rawConfig = trimmed
                    )
                )
            )
        }

        // 4. JSON: sing-box или xray.
        if (trimmed.startsWith("{")) {
            jsonProfile(trimmed)?.let { return Result(Kind.PROFILE, listOf(it)) }
        }

        // 5. Ссылка или список ссылок.
        val uris = ProxyUri.parseAll(trimmed)
        if (uris.isNotEmpty()) {
            val profiles = uris.map(::fromProxy)
            val kind = if (profiles.size == 1) Kind.PROFILE else Kind.LIST
            return Result(kind, profiles)
        }

        // 6. Адрес подписки: ссылка, которую не разобрать как прокси.
        subscriptionUrl(trimmed)?.let { url ->
            return Result(
                Kind.SUBSCRIPTION,
                listOf(
                    VpnProfile(
                        name = hostOf(url),
                        protocol = VpnProtocol.SINGBOX.code,
                        rawConfig = "",
                        subscriptionUrl = url
                    )
                )
            )
        }

        return Result.none
    }

    /**
     * Строки мостов Tor: `obfs4 1.2.3.4:443 cert=...` и однозначные
     * транспортные ключи. Текст без `://` и без них Tor'у не считается --
     * случайная каша не должна превращаться в профиль.
     */
    private fun looksLikeBridges(text: String): Boolean {
        if ("://" in text) return false
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return false
        val transport = Regex(
            "(?i)^(bridge\\s+)?(obfs4|obfs3|snowflake|meek-azure|meek|webtunnel|scramblesuit|vanilla)\\b"
        )
        // С транспортом или с отпечатком: `1.2.3.4:443 <FINGERPRINT>`.
        val fingerprint = Regex("^[0-9A-Za-z.\\-]+:\\d{1,5}\\s+[0-9A-Fa-f]{40}$")
        val bridged = lines.count { transport.containsMatchIn(it) || fingerprint.matches(it) }
        if (bridged > 0) return true
        // Ванильные мосты без отпечатка: чистые `ip:port`.
        val bare = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{1,5}$")
        return lines.all { bare.matches(it) }
    }

    /** WG-ini в профиль; null -- не похоже на конфиг WireGuard. */
    private fun wgProfile(text: String): VpnProfile? {
        val profile = WireGuardIni.parse(text) ?: return null
        if (profile.privateKey.isBlank() && profile.publicKey.isBlank()) return null
        val awg = profile.extras.keys.any { it in AWG_KEYS }
        return profile.copy(
            protocol = if (awg) VpnProtocol.AMNEZIA_WG.code else VpnProtocol.WIREGUARD.code
        )
    }

    private fun looksLikeOvpn(text: String): Boolean {
        val head = text.lineSequence().take(30).joinToString("\n")
        return head.contains(Regex("(?m)^\\s*(client|dev\\s+|remote\\s+|proto\\s+)"))
    }

    /** JSON-конфиг: sing-box (поле type) или xray (поле protocol). */
    private fun jsonProfile(text: String): VpnProfile? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val outbounds = root["outbounds"]?.let { runCatching { it.jsonArray }.getOrNull() }
        val endpoints = root["endpoints"]
        val hasSingType = outbounds?.any { el ->
            el is JsonObject && "type" in el
        } == true
        val hasXrayProto = outbounds?.any { el ->
            el is JsonObject && "protocol" in el
        } == true
        val hasInbounds = "inbounds" in root
        val hasTunXray = root["inbounds"]?.let { inb ->
            runCatching {
                inb.jsonArray.any { el ->
                    el is JsonObject && el["protocol"]?.jsonPrimitive?.contentOrNullCompat() == "tun"
                }
            }.getOrDefault(false)
        } == true

        return when {
            // sing-box: свой тип у outbound'а или явные секции.
            hasSingType || endpoints != null -> VpnProfile(
                protocol = VpnProtocol.SINGBOX.code,
                rawConfig = text
            )
            // xray: протокол вместо типа; tun-вход -- признак того же.
            hasXrayProto || hasTunXray || (hasInbounds && outbounds != null) -> VpnProfile(
                protocol = VpnProtocol.XRAY.code,
                rawConfig = text
            )
            else -> null
        }
    }

    /** Ссылка прокси в профиль: канонический текст -- в rawConfig. */
    fun fromProxy(proxy: ProxyConfig): VpnProfile = VpnProfile(
        name = proxy.displayName(),
        protocol = protocolCode(proxy.scheme),
        transport = VpnTransportCode.of(proxy.transport),
        gateway = proxy.server,
        gatewayPort = proxy.port,
        rawConfig = proxy.raw
    )

    private fun protocolCode(scheme: ProxyScheme): String = when (scheme) {
        ProxyScheme.VLESS -> VpnProtocol.VLESS.code
        ProxyScheme.VMESS -> VpnProtocol.VMESS.code
        ProxyScheme.TROJAN -> VpnProtocol.TROJAN.code
        ProxyScheme.SHADOWSOCKS -> VpnProtocol.SHADOWSOCKS.code
        ProxyScheme.HYSTERIA2 -> VpnProtocol.HYSTERIA2.code
        ProxyScheme.TUIC -> VpnProtocol.TUIC.code
        ProxyScheme.SOCKS -> VpnProtocol.SOCKS5.code
        ProxyScheme.HTTP -> VpnProtocol.HTTP.code
        ProxyScheme.SSH -> VpnProtocol.SSH.code
    }

    /** Транспорт ссылки в код профиля: напрямую, но с явным фоллбэком. */
    private object VpnTransportCode {
        fun of(transport: String): String = when (transport.lowercase()) {
            "ws", "websocket" -> "ws"
            "grpc" -> "grpc"
            "h2", "http2" -> "http2"
            "tcp" -> "tcp"
            "udp" -> "udp"
            else -> "tcp"
        }
    }

    /** Строка -- адрес подписки, если это URL без userinfo и порт не прокси. */
    private fun subscriptionUrl(text: String): String? {
        if ('\n' in text) return null
        val lower = text.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        // Прокси-ссылка http://user:pass@host -- не подписка.
        val authority = text.substringAfter("://").substringBefore("/").substringBefore('?')
        if ('@' in authority) return null
        if (!authority.contains('.')) return null
        return text
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").substringBefore(':')

    private fun JsonPrimitive.contentOrNullCompat(): String? =
        runCatching { content }.getOrNull()
}
