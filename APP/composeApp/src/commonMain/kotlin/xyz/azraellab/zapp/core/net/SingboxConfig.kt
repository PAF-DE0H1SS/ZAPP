package xyz.azraellab.zapp.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol

/**
 * Сборка конфига sing-box (amnezia-box) из профиля и общих настроек.
 *
 * Одна точка правды для всех туннельных бэкендов, кроме ядерного
 * WireGuard: любые протоколы, подписки, DNS-политика и правила маршрутов
 * сводятся к одному JSON, который исполняет `box`. Конфиг собирается
 * всегда целиком -- tun-вход, DNS, маршруты, clon-api -- и никогда не
 * «дорисовывается» поверх чужого: формат, не поддерживаемый здесь,
 * падает честной ошибкой, а не запускается с половиной полей.
 *
 * Проверка собранного -- `box check`: во время разработки генератор
 * прогоняется через бинарь на хосте.
 */
object SingboxConfig {

    /** Готовый конфиг и строки для журнала туннеля. */
    data class Built(val json: String, val notes: List<String> = emptyList())

    private const val TUN_TAG = "tun-in"
    private const val DIRECT_TAG = "direct"
    private const val URLTEST_TAG = "auto"
    private const val ENDPOINT_TAG = "wg-ep"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Собирает конфиг для профиля.
     *
     * [nodes] -- узлы подписки; непустые, только если профиль -- подписка.
     * Бросает [IllegalArgumentException] с текстом для журнала: вызывающий
     * (движок туннеля) превращает её в строку ERROR, а не глотает.
     */
    fun build(profile: VpnProfile, vpn: VpnConfig, nodes: List<ProxyConfig> = emptyList()): Built {
        when (VpnProtocol.of(profile.protocol)) {
            VpnProtocol.SINGBOX -> {
                // Конфиг пользователя исполняется как есть: влезать в чужие
                // inbounds/route -- значит молча менять чужие решения.
                check(profile.rawConfig.isNotBlank()) { "sing-box profile has no config text" }
                return Built(profile.rawConfig, listOf("engine: sing-box config as provided"))
            }
            VpnProtocol.XRAY -> {
                val (outbounds, notes) = translateXray(profile.rawConfig)
                check(outbounds.isNotEmpty()) { "xray config has no supported outbounds" }
                return assemble(profile, vpn, outbounds, emptyList(), notes)
            }
            else -> Unit
        }

        val endpoints: Pair<List<JsonObject>, String?> = when (VpnProtocol.of(profile.protocol)) {
            VpnProtocol.WIREGUARD, VpnProtocol.AMNEZIA_WG -> {
                val endpoint = endpointOf(profile)
                val note = if (VpnProtocol.of(profile.protocol) == VpnProtocol.AMNEZIA_WG) {
                    "engine: sing-box (AmneziaWG endpoint)"
                } else {
                    "engine: sing-box (WireGuard endpoint)"
                }
                listOf(endpoint) to note
            }
            else -> emptyList<JsonObject>() to null
        }

        val outbounds = when {
            nodes.isNotEmpty() -> {
                val used = mutableSetOf<String>()
                val built = ArrayList<JsonObject>(nodes.size)
                for (node in nodes) {
                    val outbound = outboundOf(node) ?: continue
                    val base = tagOf(outbound)
                    var tag = base
                    var copy = 2
                    while (!used.add(tag)) {
                        tag = "$base ($copy)"
                        copy++
                    }
                    built += if (tag == base) {
                        outbound
                    } else {
                        JsonObject(outbound + ("tag" to JsonPrimitive(tag)))
                    }
                }
                check(built.isNotEmpty()) { "subscription has no supported links" }
                built
            }
            else -> {
                val proxy = profile.rawConfig.takeIf { "://" in it }
                    ?.let { ProxyUri.parse(it) }
                    ?: throw IllegalArgumentException("profile has no link")
                listOf(outboundOf(proxy) ?: throw IllegalArgumentException("unsupported scheme ${proxy.scheme}"))
            }
        }

        return assemble(
            profile, vpn, outbounds, endpoints.first,
            listOfNotNull(endpoints.second)
        )
    }

    /**
     * Групповое подключение: urltest по списку живых ссылок.
     *
     * Плохие ссылки отсеиваются молча -- группа должна собраться даже из
     * частично нерабочего списка, иначе автовыбор окажется бесполезнее
     * ручного выбора.
     */
    fun buildGroup(vpn: VpnConfig, links: List<String>): Built {
        val used = mutableSetOf<String>()
        val outbounds = ArrayList<JsonObject>()
        for (link in links) {
            val proxy = ProxyUri.parse(link) ?: continue
            val outbound = outboundOf(proxy) ?: continue
            val base = tagOf(outbound)
            var tag = base
            var copy = 2
            while (!used.add(tag)) {
                tag = "$base ($copy)"
                copy++
            }
            outbounds += if (tag == base) outbound
            else JsonObject(outbound + ("tag" to JsonPrimitive(tag)))
        }
        check(outbounds.isNotEmpty()) { "no usable links for group connect" }
        return assemble(VpnProfile(mtu = 1420), vpn, outbounds, emptyList(), listOf("engine: sing-box (urltest group)"))
    }

    /**
     * Конфиг для профиля Tor: весь трафик идёт в локальный SOCKS tor'а.
     *
     * Сам tor здесь не запускает -- движок поднимает его демоном раньше
     * (мосты, транспортные плагины, bootstrap -- это torrc, а не sing-box),
     * а box лишь пускает туннель в уже готовый порт.
     */
    fun buildTor(vpn: VpnConfig, socksPort: Int): Built {
        val outbound = buildJsonObject {
            put("type", "socks")
            put("tag", "tor")
            put("server", "127.0.0.1")
            put("server_port", socksPort.coerceIn(1, 65535))
            put("version", "5")
        }
        return assemble(VpnProfile(mtu = 1420), vpn, listOf(outbound), emptyList(), listOf("engine: sing-box (via tor socks:$socksPort)"))
    }

    // --- Сборка общей части ---

    private fun assemble(
        profile: VpnProfile,
        vpn: VpnConfig,
        outbounds: List<JsonObject>,
        endpoints: List<JsonObject>,
        notes: List<String>
    ): Built {
        val finalTag = when {
            endpoints.isNotEmpty() -> ENDPOINT_TAG
            outbounds.size > 1 -> URLTEST_TAG
            else -> tagOf(outbounds.first())
        }

        val dns = dnsSection(vpn, profile, finalTag)
        val route = routeSection(vpn, finalTag)
        val extraNotes = notes + buildList {
            if (vpn.killSwitch) add("kill-switch: applied by engine (iptables)")
            if (vpn.routing.allowPerAppBypass && vpn.routing.bypassAppList().isNotEmpty()) {
                add("per-app bypass: ${vpn.routing.bypassAppList().size} package(s)")
            }
        }

        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "debug")
                put("timestamp", true)
            })
            put("dns", dns)
            put("inbounds", buildJsonArray {
                add(tunInbound(profile, vpn))
            })
            put("outbounds", buildJsonArray {
                outbounds.forEach { add(it) }
                if (outbounds.size > 1) add(urltestOf(outbounds))
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", DIRECT_TAG)
                })
            })
            if (endpoints.isNotEmpty()) {
                put("endpoints", buildJsonArray { endpoints.forEach { add(it) } })
            }
            put("route", route)
            if (vpn.clashApiEnabled) {
                put("experimental", buildJsonObject {
                    put("clash_api", buildJsonObject {
                        put("external_controller", "127.0.0.1:${vpn.clashApiPort.coerceIn(1, 65535)}")
                        if (vpn.clashApiSecret.isNotBlank()) put("secret", vpn.clashApiSecret)
                    })
                })
            }
        }

        return Built(json.encodeToString(JsonElement.serializer(), root), extraNotes)
    }

    private fun tunInbound(profile: VpnProfile, vpn: VpnConfig): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", TUN_TAG)
        put("address", buildJsonArray {
            add(JsonPrimitive("172.19.0.1/30"))
            if (vpn.ipv6) add(JsonPrimitive("fdfe:dcba:9876::1/126"))
        })
        put("auto_route", true)
        put("stack", "system")
        put("sniff", true)
        // Ни strict_route, ни авто-маршрутизации multicast'а через tun:
        // оба ломают локальную сеть на части Android-прошивок, а их задачу
        // выполняют правила маршрутов ниже.
        if (vpn.routing.excludePrivateRanges) put("strict_route", false)
        val bypass = if (vpn.routing.allowPerAppBypass) vpn.routing.bypassAppList() else emptyList()
        if (bypass.isNotEmpty()) {
            put("exclude_package", buildJsonArray { bypass.forEach { add(JsonPrimitive(it)) } })
        }
        val mtu = profile.mtu.takeIf { it in 576..9000 }
        if (mtu != null) put("mtu", mtu)
    }

    private fun dnsSection(vpn: VpnConfig, profile: VpnProfile, finalTag: String): JsonObject {
        val explicit = (vpn.dnsList() + profile.dnsList()).distinct().filter { it.isNotBlank() }
        val servers = buildJsonArray {
            add(buildJsonObject { put("type", "local"); put("tag", "system") })
            explicit.forEachIndexed { index, server ->
                add(buildJsonObject {
                    put("type", "udp")
                    put("tag", "dns-$index")
                    put("server", server)
                    put("server_port", 53)
                    if (vpn.dnsViaProxy && finalTag != DIRECT_TAG) put("detour", finalTag)
                })
            }
            // Android: type local резолвит через системный резолвер, которого
            // у демона нет ([::1]:53 refused), -- при пустом списке нужен
            // рабочий публичный DNS, иначе ядро не разрешит адреса своих
            // исходящих подключений.
            if (explicit.isEmpty()) {
                add(buildJsonObject {
                    put("type", "udp")
                    put("tag", "dns-0")
                    put("server", "8.8.8.8")
                    put("server_port", 53)
                    // Как и явным серверам: с dnsViaProxy резолв идёт через
                    // туннель, иначе первый пинг узла -- это ещё и утечка
                    // запроса мимо прокси. Сервер -- IP, петли не будет.
                    if (vpn.dnsViaProxy && finalTag != DIRECT_TAG) put("detour", finalTag)
                })
            }
            if (vpn.dnsFakeIp) {
                add(buildJsonObject {
                    put("type", "fakeip")
                    put("tag", "fakeip")
                    put("inet4_range", "198.18.0.0/15")
                    if (vpn.ipv6) put("inet6_range", "fc00::/18")
                })
            }
        }

        val finalServer = if (vpn.dnsFakeIp) null else "dns-0"

        return buildJsonObject {
            put("servers", servers)
            if (finalServer != null) put("final", finalServer)
            if (vpn.dnsFakeIp) {
                put("rules", buildJsonArray {
                    add(buildJsonObject {
                        put("query_type", buildJsonArray {
                            add(JsonPrimitive("A"))
                            add(JsonPrimitive("AAAA"))
                        })
                        put("server", "fakeip")
                    })
                })
            }
            put("independent_cache", true)
        }
    }

    private fun routeSection(vpn: VpnConfig, finalTag: String): JsonObject {
        val rules = buildJsonArray {
            // DNS на себя до маршрутизации: без этого запрос уходит в сеть
            // как обычный UDP и обходит и перехват, и fake-ip.
            if (vpn.routing.interceptDns) {
                add(buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
            }
            if (vpn.routing.excludePrivateRanges) {
                add(buildJsonObject {
                    put("ip_cidr", buildJsonArray {
                        listOf(
                            "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12",
                            "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10"
                        ).forEach { add(JsonPrimitive(it)) }
                    })
                    put("outbound", DIRECT_TAG)
                })
            }
            if (vpn.routing.excludeMulticast) {
                add(buildJsonObject {
                    put("ip_cidr", buildJsonArray {
                        add(JsonPrimitive("224.0.0.0/4"))
                        add(JsonPrimitive("255.255.255.255/32"))
                    })
                    put("outbound", DIRECT_TAG)
                })
            }
            if (!vpn.ipv6) {
                add(buildJsonObject {
                    put("ip_cidr", buildJsonArray {
                        add(JsonPrimitive("::/0"))
                        add(JsonPrimitive("ff00::/8"))
                    })
                    put("action", "reject")
                })
            }
        }

        return buildJsonObject {
            put("rules", rules)
            put("final", finalTag)
            // Резолвер доменных адресов своих же outbounds (серверы почти
            // всегда -- домены). Без него sing-box зовёт deprecated-отчёт
            // missing-domain-resolver, а на нём паникует Impending() форка
            // (см. патч zapp-deprecated-impending). "dns-0" гарантированно
            // существует: публичный fallback или первый явный сервер.
            put("default_domain_resolver", "dns-0")
            // false: на Android untrusted_app SELinux запрещает netlink
            // (b/155595000), мониторы интерфейсов там отключены, и автобинд
            // физического интерфейса некому резолвить. Сокеты ядра и так идут
            // мимо VPN (приложение исключено из туннеля), штатной маршрутизации
            // достаточно.
            put("auto_detect_interface", false)
        }
    }

    private fun urltestOf(outbounds: List<JsonObject>): JsonObject = buildJsonObject {
        put("type", "urltest")
        put("tag", URLTEST_TAG)
        put("outbounds", buildJsonArray {
            outbounds.forEach { add(JsonPrimitive(tagOf(it))) }
        })
        // Часто и мелко: раз в минуту группа перепроверяет узлы, а
        // tolerance в 10 мс переключает только на заметно более быстрый --
        // минимум пинга без дёрганья туда-сюда на шуме замера.
        put("interval", "1m")
        put("tolerance", 10)
        // При смене узла существующие соединения рвутся и переезжают на
        // новый: без этого «выбрал быстрее» действовало бы только для
        // новых соединений, а живые сидели бы на старом пинге.
        put("interrupt_exist_connections", true)
    }

    // --- WireGuard / Amnezia WG ---

    /** WireGuard- или Amnezia-WG-endpoint по профилю. */
    private fun endpointOf(profile: VpnProfile): JsonObject {
        val awg = VpnProtocol.of(profile.protocol) == VpnProtocol.AMNEZIA_WG
        val addresses = profile.address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val allowed = profile.allowedIpList().filter { it.isNotBlank() }

        return buildJsonObject {
            put("type", if (awg) "awg" else "wireguard")
            put("tag", ENDPOINT_TAG)
            put("name", profile.name.ifBlank { "zapp" }.take(32))
            put("private_key", profile.privateKey)
            put("address", buildJsonArray { addresses.forEach { add(JsonPrimitive(it)) } })
            if (profile.mtu in 576..9000) put("mtu", profile.mtu)
            put("peers", buildJsonArray {
                add(buildJsonObject {
                    put("address", profile.gateway)
                    put("port", profile.gatewayPort.coerceIn(1, 65535))
                    put("public_key", profile.publicKey)
                    if (profile.presharedKey.isNotBlank()) {
                        // У wireguard-endpoint ключ называется pre_shared_key,
                        // у awg-endpoint -- preshared_key: это разные типы.
                        if (awg) put("preshared_key", profile.presharedKey)
                        else put("pre_shared_key", profile.presharedKey)
                    }
                    if (allowed.isNotEmpty()) {
                        put("allowed_ips", buildJsonArray { allowed.forEach { add(JsonPrimitive(it)) } })
                    }
                    if (profile.keepaliveSeconds > 0) {
                        put("persistent_keepalive_interval", profile.keepaliveSeconds)
                    }
                })
            })
            if (awg) {
                intExtra(profile, "jc")?.let { put("jc", it) }
                intExtra(profile, "jmin")?.let { put("jmin", it) }
                intExtra(profile, "jmax")?.let { put("jmax", it) }
                for (key in listOf("s1", "s2", "s3", "s4")) {
                    intExtra(profile, key)?.let { put(key, it) }
                }
                for (key in listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")) {
                    profile.extras[key]?.takeIf { it.isNotBlank() }?.let { put(key, it) }
                }
            }
        }
    }

    private fun intExtra(profile: VpnProfile, key: String): Int? =
        profile.extras[key]?.trim()?.toIntOrNull()

    // --- Прокси-outbound'ы ---

    private fun outboundOf(p: ProxyConfig): JsonObject? = when (p.scheme) {
        ProxyScheme.VLESS -> buildJsonObject {
            put("type", "vless")
            putCommon(p)
            put("uuid", p.auth)
            put("packet_encoding", "xudp")
            if (p.flow.isNotBlank() && p.transport == "tcp") put("flow", p.flow)
            transportOf(p)?.let { put("transport", it) }
            if (p.security.isNotBlank()) put("tls", tlsOf(p))
        }
        ProxyScheme.VMESS -> buildJsonObject {
            put("type", "vmess")
            putCommon(p)
            put("uuid", p.auth)
            put("security", p.cipher.ifBlank { "auto" })
            p.params["alterId"]?.toIntOrNull()?.let { put("alter_id", it) }
            transportOf(p)?.let { put("transport", it) }
            if (p.security.isNotBlank()) put("tls", tlsOf(p))
        }
        ProxyScheme.TROJAN -> buildJsonObject {
            put("type", "trojan")
            putCommon(p)
            put("password", p.auth)
            transportOf(p)?.let { put("transport", it) }
            put("tls", tlsOf(p))
        }
        ProxyScheme.SHADOWSOCKS -> buildJsonObject {
            put("type", "shadowsocks")
            putCommon(p)
            put("method", p.cipher.ifBlank { "aes-256-gcm" })
            put("password", p.auth)
            p.params["plugin"]?.takeIf { it.isNotBlank() }?.let { plugin ->
                put("plugin", plugin)
                p.params["plugin-opts"]?.takeIf { it.isNotBlank() }?.let { put("plugin_opts", it) }
            }
        }
        ProxyScheme.HYSTERIA2 -> buildJsonObject {
            put("type", "hysteria2")
            putCommon(p)
            put("password", p.auth)
            put("tls", tlsOf(p))
            p.params["obfs"]?.takeIf { it.isNotBlank() }?.let { obfs ->
                put("obfs", buildJsonObject {
                    put("type", obfs)
                    p.params["obfs-password"]?.let { put("password", it) }
                })
            }
            p.params["up_mbps"]?.toIntOrNull()?.let { put("up_mbps", it) }
            p.params["down_mbps"]?.toIntOrNull()?.let { put("down_mbps", it) }
        }
        ProxyScheme.TUIC -> buildJsonObject {
            put("type", "tuic")
            putCommon(p)
            val uuid = p.auth.substringBefore(':')
            val password = p.auth.substringAfter(':', "")
            put("uuid", uuid)
            put("password", password.ifBlank { uuid })
            p.params["congestion_control"]?.takeIf { it.isNotBlank() }?.let { put("congestion_control", it) }
            p.params["udp_relay_mode"]?.takeIf { it.isNotBlank() }?.let { put("udp_relay_mode", it) }
            put("tls", tlsOf(p))
        }
        ProxyScheme.SOCKS -> buildJsonObject {
            put("type", "socks")
            putCommon(p)
            usersOf(p)?.let { put("users", it) }
        }
        ProxyScheme.HTTP -> buildJsonObject {
            put("type", "http")
            putCommon(p)
            usersOf(p)?.let { put("users", it) }
            if (p.security == "tls") put("tls", tlsOf(p))
        }
        ProxyScheme.SSH -> buildJsonObject {
            put("type", "ssh")
            putCommon(p)
            put("user", p.auth.substringBefore(':'))
            p.auth.substringAfter(':', "").takeIf { it.isNotBlank() }?.let { put("password", it) }
            p.params["private-key"]?.takeIf { it.isNotBlank() }?.let { put("private_key", it) }
        }
    }

    private fun JsonObjectBuilder.putCommon(p: ProxyConfig) {
        put("tag", tagOf(p))
        put("server", p.server)
        put("server_port", p.port.coerceIn(1, 65535))
    }

    private fun usersOf(p: ProxyConfig): JsonArray? {
        if (p.auth.isBlank()) return null
        val user = p.auth.substringBefore(':')
        val pass = p.auth.substringAfter(':', "")
        return buildJsonArray {
            add(buildJsonObject {
                put("username", user)
                if (pass.isNotBlank()) put("password", pass)
            })
        }
    }

    private fun transportOf(p: ProxyConfig): JsonObject? = when (p.transport.lowercase()) {
        "ws", "websocket" -> buildJsonObject {
            put("type", "ws")
            put("path", p.path.ifBlank { "/" })
            if (p.host.isNotBlank()) {
                put("headers", buildJsonObject { put("Host", p.host) })
            }
        }
        "grpc" -> buildJsonObject {
            put("type", "grpc")
            put("service_name", p.path.trimStart('/'))
        }
        "http", "h2", "http2" -> buildJsonObject {
            put("type", "http")
            put("path", p.path.ifBlank { "/" })
            if (p.host.isNotBlank()) {
                put("host", buildJsonArray { add(JsonPrimitive(p.host)) })
            }
        }
        else -> null
    }

    private fun tlsOf(p: ProxyConfig): JsonObject = buildJsonObject {
        val reality = p.security == "reality"
        put("enabled", true)
        put("server_name", p.sni.ifBlank { p.server })
        val insecure = p.params["insecure"]?.lowercase() in setOf("1", "true", "yes")
        if (insecure) put("insecure", true)
        if (p.alpn.isNotBlank()) {
            put("alpn", buildJsonArray {
                p.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { add(JsonPrimitive(it)) }
            })
        }
        val utlsWorthy = p.scheme == ProxyScheme.VLESS ||
            p.scheme == ProxyScheme.VMESS ||
            p.scheme == ProxyScheme.TROJAN
        if (utlsWorthy) {
            put("utls", buildJsonObject {
                put("enabled", true)
                put("fingerprint", p.fingerprint.ifBlank { "chrome" })
            })
        }
        if (reality) {
            val publicKey = p.params["pbk"].orEmpty()
            check(publicKey.isNotBlank()) { "reality link without public key (pbk)" }
            put("reality", buildJsonObject {
                put("enabled", true)
                put("public_key", publicKey)
                put("short_id", p.params["sid"].orEmpty())
            })
        }
    }

    /** Тег outbound'а: имя ссылки или адрес. */
    private fun tagOf(outbound: JsonObject): String =
        outbound["tag"]?.jsonPrimitive?.contentOrNull() ?: "proxy"

    private fun tagOf(p: ProxyConfig): String = p.displayName()

    // --- Xray -> sing-box ---

    /**
     * Переводит outbounds Xray в sing-box-вид.
     *
     * Переводятся четыре основных протокола (vless, vmess, trojan,
     * shadowsocks) и socks/http: на них приходится почти все реальные
     * конфиги. Остальное попадает в notes с именем протокола, а не
     * молча исчезает.
     */
    private fun translateXray(text: String): Pair<List<JsonObject>, List<String>> {
        check(text.isNotBlank()) { "xray profile has no config text" }
        val root = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrElse { throw IllegalArgumentException("xray config is not valid JSON") }
        val outbounds = root["outbounds"]?.jsonArray
            ?: throw IllegalArgumentException("xray config has no outbounds")

        val notes = mutableListOf<String>()
        val built = outbounds.mapNotNull { element ->
            val ob = element as? JsonObject ?: return@mapNotNull null
            val protocol = ob["protocol"]?.jsonPrimitive?.contentOrNull()
            if (protocol == "freedom" || protocol == "blackhole" || protocol == "dns") {
                return@mapNotNull null
            }
            runCatching { xrayOutbound(ob, protocol.orEmpty()) }
                .onSuccess { notes += "xray: translated $protocol" }
                .onFailure { notes += "xray: skipped ${protocol.orEmpty().ifBlank { "unknown" }} (${it.message ?: "unsupported"})" }
                .getOrNull()
        }
        return built to notes
    }

    private fun xrayOutbound(ob: JsonObject, protocol: String): JsonObject? {
        val settings = ob["settings"]?.jsonObject ?: return null
        val stream = ob["streamSettings"]?.jsonObject

        fun firstString(source: JsonObject?, vararg keys: String): String =
            keys.firstNotNullOfOrNull { key ->
                source?.get(key)?.let { if (it is JsonPrimitive) it.content else null }
            } ?: ""

        val proxy = when (protocol) {
            "vless", "vmess" -> {
                val vnext = settings["vnext"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return null
                val user = vnext["users"]?.jsonArray?.firstOrNull()?.jsonObject
                val base = baseFrom(vnext)
                if (protocol == "vless") {
                    base.copy(
                        scheme = ProxyScheme.VLESS,
                        auth = firstString(user, "id"),
                        flow = firstString(user, "flow"),
                        cipher = "auto"
                    )
                } else {
                    base.copy(
                        scheme = ProxyScheme.VMESS,
                        auth = firstString(user, "id"),
                        cipher = firstString(user, "encryption").ifBlank { "auto" }
                    )
                }
            }
            "trojan" -> {
                val server = settings["servers"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
                val user = server["users"]?.jsonArray?.firstOrNull()?.jsonObject
                baseFrom(server).copy(
                    scheme = ProxyScheme.TROJAN,
                    auth = firstString(user, "password")
                )
            }
            "shadowsocks" -> {
                val server = settings["servers"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
                baseFrom(server).copy(
                    scheme = ProxyScheme.SHADOWSOCKS,
                    auth = firstString(server, "password"),
                    cipher = firstString(server, "method")
                )
            }
            "socks" -> {
                val server = settings["servers"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
                val user = server["users"]?.jsonArray?.firstOrNull()?.jsonObject
                val name = firstString(user, "user")
                val pass = firstString(user, "pass")
                baseFrom(server).copy(
                    scheme = ProxyScheme.SOCKS,
                    auth = if (name.isBlank()) "" else if (pass.isBlank()) name else "$name:$pass"
                )
            }
            "http" -> {
                val server = settings["servers"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
                val user = firstString(server, "user")
                val pass = firstString(server, "pass")
                baseFrom(server).copy(
                    scheme = ProxyScheme.HTTP,
                    auth = if (user.isBlank()) "" else if (pass.isBlank()) user else "$user:$pass"
                )
            }
            else -> return null
        }

        val proxyStream = proxy.copy(
            transport = xrayTransportOf(stream),
            security = firstString(stream, "security").ifBlank {
                if (stream?.get("tlsSettings") != null || stream?.get("realitySettings") != null) "tls" else ""
            },
            sni = xraySni(stream),
            host = xrayHost(stream),
            path = xrayPath(stream),
            alpn = xrayAlpn(stream),
            fingerprint = firstString(stream?.get("tlsSettings")?.jsonObject, "fingerprint")
        )

        val outbound = outboundOf(proxyStream) ?: return null
        return stream?.let { addXrayTransport(outbound, it, proxyStream) } ?: outbound
    }

    private fun baseFrom(source: JsonObject): ProxyConfig = ProxyConfig(
        raw = "",
        scheme = ProxyScheme.VLESS,
        name = "",
        server = source["address"]?.jsonPrimitive?.contentOrNull() ?: "",
        port = source["port"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0
    )

    private fun xrayTransportOf(stream: JsonObject?): String {
        val network = stream?.get("network")?.jsonPrimitive?.contentOrNull() ?: "tcp"
        return when (network) {
            "ws" -> "ws"
            "grpc" -> "grpc"
            "h2", "http" -> "http"
            else -> "tcp"
        }
    }

    private fun xraySni(stream: JsonObject?): String =
        stream?.get("tlsSettings")?.jsonObject?.get("serverName")?.jsonPrimitive?.contentOrNull()
            ?: stream?.get("realitySettings")?.jsonObject?.get("serverName")?.jsonPrimitive?.contentOrNull()
            ?: ""

    private fun xrayHost(stream: JsonObject?): String {
        val wsHeaders = stream?.get("wsSettings")?.jsonObject?.get("headers")?.jsonObject
        wsHeaders?.entries?.firstOrNull()?.value?.jsonPrimitive?.contentOrNull()?.let { return it }
        return stream?.get("httpSettings")?.jsonObject?.get("host")?.jsonArray
            ?.firstOrNull()?.jsonPrimitive?.contentOrNull() ?: ""
    }

    private fun xrayPath(stream: JsonObject?): String =
        stream?.get("wsSettings")?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull()
            ?: stream?.get("httpSettings")?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull()
            ?: ""

    private fun xrayAlpn(stream: JsonObject?): String =
        stream?.get("tlsSettings")?.jsonObject?.get("alpn")?.jsonArray
            ?.joinToString(",") { it.jsonPrimitive.contentOrNull().orEmpty() } ?: ""

    /** reality и host/path для grpc из streamSettings, которых нет в ProxyConfig. */
    private fun addXrayTransport(
        outbound: JsonObject,
        stream: JsonObject,
        proxy: ProxyConfig
    ): JsonObject {
        val reality = stream["realitySettings"]?.jsonObject ?: return outbound
        val publicKey = reality["publicKey"]?.jsonPrimitive?.contentOrNull().orEmpty()
        if (publicKey.isBlank()) return outbound
        val merged = outbound.toMutableMap()
        val tls = (outbound["tls"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        tls["reality"] = buildJsonObject {
            put("enabled", true)
            put("public_key", publicKey)
            put("short_id", reality["shortId"]?.jsonPrimitive?.contentOrNull() ?: "")
        }
        tls["server_name"] = JsonPrimitive(
            reality["serverName"]?.jsonPrimitive?.contentOrNull()
                ?: proxy.sni.ifBlank { proxy.server }
        )
        merged["tls"] = JsonObject(tls)
        return JsonObject(merged)
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()
}
