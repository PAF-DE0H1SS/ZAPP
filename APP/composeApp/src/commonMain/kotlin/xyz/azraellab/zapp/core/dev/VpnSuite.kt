package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.ConfigCodec
import xyz.azraellab.zapp.core.LinkHealth
import xyz.azraellab.zapp.core.RouteHealth
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnConnects
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.VpnRouting
import xyz.azraellab.zapp.core.VpnSource
import xyz.azraellab.zapp.core.VpnTransport
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.engine.LifecyclePhase
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.engine.isLive
import xyz.azraellab.zapp.core.engine.openvpnTunnelParams
import xyz.azraellab.zapp.core.engine.phase
import xyz.azraellab.zapp.core.gps.GpsState
import xyz.azraellab.zapp.core.net.LinkTarget
import xyz.azraellab.zapp.core.net.ProbeKind
import xyz.azraellab.zapp.core.net.probeTarget
import xyz.azraellab.zapp.core.route

/**
 * VPN: протоколы, списки коннектов, выбор пробы.
 *
 * Здесь живёт то, что решает, что пользователь вообще видит: живые
 * впереди, мёртвые в архив, мосты -- на своей вкладке. Проверки пробы
 * ([probeTarget]) -- чистая адресация: откуда взять хост и порт, зависит
 * от протокола, и ошибка здесь означает «коннект не проверяется вообще».
 */
private fun profile(
    id: String = "",
    name: String = "",
    protocol: String = VpnProtocol.WIREGUARD.code,
    gateway: String = "",
    gatewayPort: Int = 51820,
    rawConfig: String = "",
    alive: Boolean? = null,
    latencyMs: Int = -1,
    sourceId: String = ""
) = VpnProfile(
    id = id,
    name = name,
    protocol = protocol,
    gateway = gateway,
    gatewayPort = gatewayPort,
    rawConfig = rawConfig,
    health = LinkHealth(alive = alive, latencyMs = latencyMs),
    sourceId = sourceId
)

internal fun vpnTests(out: MutableList<DevTest>) {

    // --- Протоколы и транспорты ---

    VpnProtocol.entries.forEach { proto ->
        out += DevTest(
            "vpn/protocol-${proto.code}",
            DevCategory.VPN,
            "VpnProtocol.of(\"${proto.code}\") == $proto"
        ) {
            checkEq(proto, VpnProtocol.of(proto.code), "code")
            checkEq(proto, VpnProtocol.of("  ${proto.code.uppercase()}  "), "регистр и пробелы")
            checkTrue(proto.strKey.name.startsWith("PROTO_"), "строка ключа: ${proto.strKey}")
        }
    }

    out += DevTest("vpn/protocol-unknown-default", DevCategory.VPN, "Неизвестный протокол -> wireguard") {
        checkEq(VpnProtocol.WIREGUARD, VpnProtocol.of("krypton"), "кракозябра")
        checkEq(VpnProtocol.WIREGUARD, VpnProtocol.of(null), "null")
        checkEq(VpnProtocol.WIREGUARD, VpnProtocol.of(""), "пусто")
    }

    out += DevTest("vpn/protocol-keys-unique", DevCategory.VPN, "У протоколов разные строки") {
        val keys = VpnProtocol.entries.map { it.strKey }
        checkEq(keys.size, keys.toSet().size, "дубликаты среди ${keys.size}")
        val codes = VpnProtocol.entries.map { it.code }
        checkEq(codes.size, codes.toSet().size, "дубликаты кодов")
    }

    VpnTransport.entries.forEach { transport ->
        out += DevTest(
            "vpn/transport-${transport.code}",
            DevCategory.VPN,
            "VpnTransport.of(\"${transport.code}\") == $transport"
        ) {
            checkEq(transport, VpnTransport.of(transport.code), "code")
            checkEq(transport, VpnTransport.of(" ${transport.code.uppercase()} "), "регистр")
        }
    }

    out += DevTest("vpn/transport-unknown-default", DevCategory.VPN, "Неизвестный транспорт -> udp") {
        checkEq(VpnTransport.UDP, VpnTransport.of("carrier-pigeon"), "неизвестный")
        checkEq(VpnTransport.UDP, VpnTransport.of(null), "null")
    }

    // --- Профиль: списки значений ---

    out += DevTest("vpn/profile-dns-list", DevCategory.VPN, "Список DNS через запятую, пробелы лишние") {
        val p = VpnProfile(dnsServers = " 1.1.1.1 , ,9.9.9.9, ")
        checkEq(listOf("1.1.1.1", "9.9.9.9"), p.dnsList(), "dnsList")
        checkTrue(VpnProfile().dnsList().isEmpty(), "пустой профиль")
    }

    out += DevTest("vpn/profile-allowed-ips", DevCategory.VPN, "Список AllowedIPs") {
        val p = VpnProfile(allowedIps = "0.0.0.0/0, ::/0 , ")
        checkEq(listOf("0.0.0.0/0", "::/0"), p.allowedIpList(), "allowedIpList")
    }

    out += DevTest("vpn/profile-subscription-flag", DevCategory.VPN, "Признак подписки по URL") {
        checkTrue(VpnProfile(subscriptionUrl = "https://example.com/sub").isSubscription, "с URL")
        checkFalse(VpnProfile(subscriptionUrl = "  ").isSubscription, "пробелы -- не URL")
    }

    out += DevTest("vpn/config-dns-list", DevCategory.VPN, "DNS конфига разбирается так же") {
        checkEq(listOf("8.8.8.8"), VpnConfig(dnsServers = "8.8.8.8,").dnsList(), "dnsList")
    }

    out += DevTest("vpn/active-profile", DevCategory.VPN, "Активный профиль ищется по id") {
        val config = VpnConfig(
            activeProfileId = "b",
            profiles = listOf(profile(id = "a", name = "A"), profile(id = "b", name = "B"))
        )
        checkEq("B", config.activeProfile()?.name, "активный")
        checkEq(null, VpnConfig().activeProfile(), "нет профилей")
        checkEq("A", config.profileById("a")?.name, "по id")
        checkEq(null, config.profileById("zzz"), "неизвестный id")
    }

    out += DevTest("vpn/active-profile-repair", DevCategory.VPN, "Висячий выбор чинится на первый узел") {
        val alive = VpnConfig(
            activeProfileId = "b",
            profiles = listOf(profile(id = "a"), profile(id = "b"))
        )
        checkEq("b", alive.repairedActive().activeProfileId, "живой id не трогаем")

        val dangling = VpnConfig(
            activeProfileId = "gone",
            profiles = listOf(profile(id = "a"), profile(id = "b"))
        )
        checkEq("a", dangling.repairedActive().activeProfileId, "висячий уезжает на первый")
        checkEq("", VpnConfig(activeProfileId = "gone").repairedActive().activeProfileId, "без профилей -- пусто")
        checkEq("", VpnConfig(profiles = listOf(profile(id = "a"))).repairedActive().activeProfileId, "пустой id -- как есть")
    }

    // C5: источник мостов Tor переживает пересборку списка -- раньше он жил
    // только в activeProfileId и сбрасывался на top-100 при каждом
    // обновлении источника.
    out += DevTest("vpn/bridge-source-repair", DevCategory.VPN, "Источник мостов переживает обновление") {
        val alive = VpnConfig(
            torBridgeSourceId = "tor-b",
            activeProfileId = "vpn-x",
            profiles = listOf(profile(id = "vpn-x"), profile(id = "tor-b"))
        )
        checkEq("tor-b", alive.repairedActive().torBridgeSourceId, "живой источник не трогаем")

        val dangling = VpnConfig(
            torBridgeSourceId = "gone",
            activeProfileId = "vpn-x",
            profiles = listOf(profile(id = "vpn-x"), profile(id = "tor-b"))
        )
        val repaired = dangling.repairedActive()
        checkEq("", repaired.torBridgeSourceId, "висячий источник очищен")
        checkEq("vpn-x", repaired.activeProfileId, "выбор VPN не задет")
        checkEq("", VpnConfig(torBridgeSourceId = "gone").repairedActive().torBridgeSourceId, "без профилей -- пусто")
    }

    // --- Список: живые, архив, мосты ---

    val mixed = listOf(
        profile(id = "fresh", name = "Fresh"),
        profile(id = "alive", name = "Alive", alive = true, latencyMs = 10),
        profile(id = "dead", name = "Dead", alive = false, latencyMs = 5),
        profile(id = "bridge", name = "Bridge", protocol = VpnProtocol.TOR.code),
        profile(id = "dead-bridge", name = "Dead bridge", protocol = VpnProtocol.TOR.code, alive = false)
    )

    out += DevTest("vpn/visible-excludes-dead-and-tor", DevCategory.VPN, "В список не попадают мёртвые и мосты") {
        checkEq(listOf("fresh", "alive"), VpnConfig(profiles = mixed).visibleProfiles().map { it.id }, "visible")
    }

    out += DevTest("vpn/archived-exactly-dead-vpn", DevCategory.VPN, "Архив -- ровно мёртвые не-мосты") {
        checkEq(listOf("dead"), VpnConfig(profiles = mixed).archivedProfiles().map { it.id }, "archive")
    }

    out += DevTest("vpn/visible-and-archive-disjoint", DevCategory.VPN, "Список и архив не пересекаются") {
        val config = VpnConfig(profiles = mixed)
        val visible = config.visibleProfiles().map { it.id }.toSet()
        val archived = config.archivedProfiles().map { it.id }.toSet()
        checkTrue(visible.intersect(archived).isEmpty(), "пересечение: ${visible.intersect(archived)}")
        val vpn = config.profiles.filter { it.protocol != VpnProtocol.TOR.code }
        checkEq(vpn.size, visible.size + archived.size, "все VPN-профили разложены")
    }

    out += DevTest("vpn/tor-bridges-protocol-only", DevCategory.VPN, "Мосты -- только протокол tor, включая мёртвые") {
        checkEq(listOf("bridge", "dead-bridge"), VpnConnects.torBridges(mixed).map { it.id }, "torBridges")
    }

    out += DevTest("vpn/profiles-of-source", DevCategory.VPN, "Коннекты своего источника") {
        val config = VpnConfig(
            profiles = listOf(
                profile(id = "1", sourceId = "src1"),
                profile(id = "2", sourceId = "src2"),
                profile(id = "3", sourceId = "src1")
            )
        )
        checkEq(listOf("1", "3"), config.profilesOfSource("src1").map { it.id }, "src1")
        checkTrue(config.profilesOfSource("nope").isEmpty(), "неизвестный источник")
    }

    // --- Сортировка ---

    val unsorted = listOf(
        profile(id = "slow", name = "C", alive = true, latencyMs = 300),
        profile(id = "fast", name = "B", alive = true, latencyMs = 10),
        profile(id = "unchecked", name = "D"),
        profile(id = "dead", name = "A", alive = false, latencyMs = 1),
        profile(id = "mid", name = "A", alive = true, latencyMs = 50)
    )

    out += DevTest("vpn/sort-by-ping", DevCategory.VPN, "По пингу: живые впереди, быстрые раньше медленных") {
        val order = VpnConnects.sort(unsorted, byPing = true).map { it.id }
        checkEq(listOf("fast", "mid", "slow", "dead", "unchecked"), order, "порядок")
    }

    out += DevTest("vpn/sort-by-ping-unanswered-last", DevCategory.VPN, "Непроверенные в конце, но перед мёртвыми нельзя") {
        val order = VpnConnects.sort(unsorted, byPing = true).map { it.id }
        checkEq("unchecked", order.last(), "непроверенный последний")
        checkEq(0, order.indexOf("fast"), "быстрый первый")
    }

    out += DevTest("vpn/sort-by-name", DevCategory.VPN, "По имени: обычный порядок строк") {
        val order = VpnConnects.sort(unsorted, byPing = false).map { it.name }
        checkEq(listOf("A", "A", "B", "C", "D"), order, "по имени")
    }

    out += DevTest("vpn/sort-ping-name-tiebreak", DevCategory.VPN, "Равный пинг разрешается именем") {
        val twins = listOf(
            profile(id = "2", name = "zeta", alive = true, latencyMs = 5),
            profile(id = "1", name = "alpha", alive = true, latencyMs = 5)
        )
        checkEq(listOf("alpha", "zeta"), VpnConnects.sort(twins, byPing = true).map { it.name }, "tiebreak")
    }

    // --- Фильтр ---

    out += DevTest("vpn/filter-noop", DevCategory.VPN, "Без фильтра список возвращается как есть") {
        val result = VpnConnects.filter(unsorted, onlyAlive = false, query = "  ")
        checkTrue(result === unsorted, "ожидался тот же объект")
    }

    out += DevTest("vpn/filter-only-alive", DevCategory.VPN, "Только живые") {
        val result = VpnConnects.filter(unsorted, onlyAlive = true, query = "")
        checkEq(listOf("slow", "fast", "mid"), result.map { it.id }, "живые")
    }

    out += DevTest("vpn/filter-query-case", DevCategory.VPN, "Поиск по имени без учёта регистра") {
        checkEq(1, VpnConnects.filter(unsorted, false, "b").size, "b")
        checkEq(2, VpnConnects.filter(unsorted, false, "a").size, "a")
        checkTrue(VpnConnects.filter(unsorted, false, "нет такого").isEmpty(), "пусто")
    }

    out += DevTest("vpn/filter-combined", DevCategory.VPN, "Фильтр и поиск независимы") {
        val result = VpnConnects.filter(unsorted, onlyAlive = true, query = "a")
        checkEq(listOf("mid"), result.map { it.id }, "живые с буквей a")
    }

    out += DevTest("vpn/has-more", DevCategory.VPN, "Кнопка «Показать ещё» по лимиту") {
        checkTrue(VpnConnects.hasMore(unsorted, 3), "5 > 3")
        checkFalse(VpnConnects.hasMore(unsorted, 5), "ровно")
        checkFalse(VpnConnects.hasMore(unsorted, 50), "лимит больше списка")
    }

    // --- Мост для автоподключения ---

    val bridges = listOf(
        profile(id = "b1", name = "slow", protocol = VpnProtocol.TOR.code, alive = true, latencyMs = 200),
        profile(id = "b2", name = "fast", protocol = VpnProtocol.TOR.code, alive = true, latencyMs = 30),
        profile(id = "b3", name = "dead", protocol = VpnProtocol.TOR.code, alive = false, latencyMs = 5),
        profile(id = "wg", name = "wireguard", protocol = VpnProtocol.WIREGUARD.code, alive = true, latencyMs = 1)
    )

    out += DevTest("vpn/tor-bridge-selected-alive", DevCategory.VPN, "Выбранный живой мост побеждает") {
        checkEq("b1", VpnConnects.torBridgeFor(bridges, "b1")?.id, "выбранный")
    }

    out += DevTest("vpn/tor-bridge-selected-dead-fallback", DevCategory.VPN, "Выбранный мёртвый -> самый быстрый живой") {
        checkEq("b2", VpnConnects.torBridgeFor(bridges, "b3")?.id, "fallback")
    }

    out += DevTest("vpn/tor-bridge-no-selected-fastest", DevCategory.VPN, "Без выбора -- самый быстрый живой") {
        checkEq("b2", VpnConnects.torBridgeFor(bridges, "")?.id, "быстрый")
    }

    out += DevTest("vpn/tor-bridge-all-dead-returns-selected", DevCategory.VPN, "Все мёртвые -- возвращается выбранный") {
        val dead = bridges.map { if (it.protocol == VpnProtocol.TOR.code) it.copy(health = it.health.copy(alive = false)) else it }
        checkEq("b1", VpnConnects.torBridgeFor(dead, "b1")?.id, "выбранный мёртвый")
        checkEq(null, VpnConnects.torBridgeFor(dead, ""), "выбора нет и живых нет")
    }

    out += DevTest("vpn/tor-bridge-ignores-non-tor", DevCategory.VPN, "WireGuard-профиль не выдаётся как мост") {
        checkEq("b2", VpnConnects.torBridgeFor(bridges, "wg")?.id, "wg -- не мост")
    }

    out += DevTest("vpn/tor-bridge-unanswered-not-beat-measured", DevCategory.VPN, "Непроверенные не обгоняют замеренных") {
        val pool = listOf(
            profile(id = "measured", name = "m", protocol = VpnProtocol.TOR.code, alive = true, latencyMs = 500),
            profile(id = "fresh", name = "f", protocol = VpnProtocol.TOR.code)
        )
        checkEq("measured", VpnConnects.torBridgeFor(pool, "")?.id, "измеренный быстрее непроверенного")
    }

    // --- Источники ---

    out += DevTest("vpn/sources-by-kind", DevCategory.VPN, "Источники фильтруются по типу") {
        val sources = listOf(
            VpnSource(id = "1", name = "proxy", url = "https://a", kind = "proxy"),
            VpnSource(id = "2", name = "tor", url = "https://b", kind = "tor"),
            VpnSource(id = "3", name = "proxy2", url = "https://c", kind = "proxy")
        )
        checkEq(listOf("1", "3"), VpnConnects.sourcesByKind(sources, "proxy").map { it.id }, "proxy")
        checkEq(listOf("2"), VpnConnects.sourcesByKind(sources, "tor").map { it.id }, "tor")
    }

    out += DevTest("vpn/source-urls-dedup-mirrors", DevCategory.VPN, "Зеркала идут после основного, дубликат отбрасывается") {
        val source = VpnSource(
            id = "s",
            name = "s",
            url = "https://main",
            mirrors = listOf("https://mirror", "https://main", "")
        )
        checkEq(listOf("https://main", "https://mirror"), source.urls(), "urls")
    }

    out += DevTest("vpn/default-sources-sane", DevCategory.VPN, "Дефолтные источники: живой список без дублей") {
        val sources = VpnConfig.defaultSources()
        checkTrue(sources.isNotEmpty(), "список не пуст")
        val ids = sources.map { it.id }
        checkEq(ids.size, ids.toSet().size, "id уникальны")
        checkTrue(sources.all { it.urls().isNotEmpty() }, "у каждого есть хотя бы URL")
        checkTrue(sources.any { it.kind == "tor" }, "есть мостовые источники")
        checkTrue(sources.any { it.kind == "proxy" }, "есть VPN-источники")
    }

    out += DevTest("vpn/routing-bypass-apps", DevCategory.VPN, "Список обходящих приложений") {
        val routing = VpnRouting(bypassApps = " com.a, com.b ,, com.c ")
        checkEq(listOf("com.a", "com.b", "com.c"), routing.bypassAppList(), "bypassAppList")
        checkTrue(VpnRouting().bypassAppList().isEmpty(), "пусто")
    }

    // --- OpenVPN: адресация туннеля из ifconfig (afunix-ветка) ---

    out += DevTest("vpn/openvpn-tunnel-netmask", DevCategory.VPN, "ifconfig с маской -> длина префикса") {
        val p = VpnProfile(protocol = "openvpn", rawConfig = "remote x 443\nifconfig 10.8.0.6 255.255.255.0")
        val t = openvpnTunnelParams(p, VpnConfig()) ?: error("params")
        checkEq(listOf("10.8.0.6/24"), t.addresses, "addresses")
        checkEq(listOf("0.0.0.0/0"), t.routes, "routes")
        checkEq(listOf("8.8.8.8", "1.1.1.1"), t.dns, "dns default")
        checkEq(1420, t.mtu, "mtu профиля")
        checkEq("ZAPP", t.session, "session без имени")
        checkTrue(t.bypassApps.isEmpty(), "bypass пуст")
    }

    out += DevTest("vpn/openvpn-tunnel-peer-address", DevCategory.VPN, "Пир-адрес вместо маски -> /32") {
        val peer = VpnProfile(protocol = "openvpn", rawConfig = "ifconfig 10.8.0.6 10.8.0.5")
        checkEq(listOf("10.8.0.6/32"), openvpnTunnelParams(peer, VpnConfig())?.addresses, "p2p")
        val mask = VpnProfile(protocol = "openvpn", rawConfig = "ifconfig 10.8.0.6 255.255.255.252")
        checkEq(listOf("10.8.0.6/30"), openvpnTunnelParams(mask, VpnConfig())?.addresses, "/30")
        val broken = VpnProfile(protocol = "openvpn", rawConfig = "ifconfig 10.8.0.6 255.0.255.0")
        checkEq(listOf("10.8.0.6/32"), openvpnTunnelParams(broken, VpnConfig())?.addresses, "битая маска -> не маска")
    }

    out += DevTest("vpn/openvpn-tunnel-no-ifconfig", DevCategory.VPN, "Нет ifconfig -> null; IFCONFIG прописным работает") {
        val plain = VpnProfile(protocol = "openvpn", rawConfig = "remote x 443")
        checkEq(null, openvpnTunnelParams(plain, VpnConfig()), "без ifconfig")
        val upper = VpnProfile(protocol = "openvpn", rawConfig = "  IFCONFIG   10.9.0.2   255.255.255.0 ")
        checkEq(listOf("10.9.0.2/24"), openvpnTunnelParams(upper, VpnConfig())?.addresses, "заглавным, двойные пробелы")
        val v6 = VpnProfile(protocol = "openvpn", rawConfig = "ifconfig 2001:db8::1 255.255.255.0")
        checkEq(null, openvpnTunnelParams(v6, VpnConfig()), "ipv6-адрес не поддерживаем")
    }

    out += DevTest("vpn/openvpn-tunnel-dns-bypass-name", DevCategory.VPN, "DNS конфига, bypass и имя сессии") {
        val vpn = VpnConfig(
            dnsServers = "1.1.1.2, 9.9.9.9",
            routing = VpnRouting(allowPerAppBypass = true, bypassApps = " com.a, com.b ,, ")
        )
        val p = VpnProfile(
            name = "Мой коннект", protocol = "openvpn", mtu = 1280,
            rawConfig = "ifconfig 10.8.0.6 255.255.255.0"
        )
        val t = openvpnTunnelParams(p, vpn) ?: error("params")
        checkEq(listOf("1.1.1.2", "9.9.9.9"), t.dns, "dns конфига")
        checkEq(listOf("com.a", "com.b"), t.bypassApps, "bypass")
        checkEq("Мой коннект", t.session, "session")
        checkEq(1280, t.mtu, "mtu")
    }

    // --- Проба: куда стучаться ---

    out += DevTest("vpn/probe-wireguard-udp", DevCategory.VPN, "WireGuard: шлюз и UDP") {
        val target = probeTarget(VpnProfile(protocol = "wireguard", gateway = "1.2.3.4", gatewayPort = 51820))
        checkEq(LinkTarget("1.2.3.4", 51820, ProbeKind.UDP), target, "target")
    }

    out += DevTest("vpn/probe-wireguard-bad-port-null", DevCategory.VPN, "WireGuard: порт вне диапазона -- проверять нечего") {
        checkEq(null, probeTarget(VpnProfile(gateway = "1.2.3.4", gatewayPort = 0)), "port 0")
        checkEq(null, probeTarget(VpnProfile(gateway = "1.2.3.4", gatewayPort = 70000)), "port 70000")
        checkEq(null, probeTarget(VpnProfile(gateway = "", gatewayPort = 51820)), "пустой шлюз")
    }

    out += DevTest("vpn/probe-amneziawg-udp", DevCategory.VPN, "Amnezia WG -- тоже UDP") {
        val target = probeTarget(
            VpnProfile(protocol = "amneziawg", gateway = "5.6.7.8", gatewayPort = 20000)
        )
        checkEq(ProbeKind.UDP, target?.kind, "kind")
    }

    out += DevTest("vpn/probe-openvpn-remote-udp", DevCategory.VPN, "OpenVPN: remote и proto udp -> UDP") {
        val p = VpnProfile(protocol = "openvpn", rawConfig = "remote vpn.example 443\nproto udp")
        checkEq(LinkTarget("vpn.example", 443, ProbeKind.UDP), probeTarget(p), "target")
    }

    out += DevTest("vpn/probe-openvpn-tcp-default-port", DevCategory.VPN, "OpenVPN без порта -> 1194, без proto -> TCP") {
        val p = VpnProfile(protocol = "openvpn", rawConfig = "remote vpn.example")
        checkEq(LinkTarget("vpn.example", 1194, ProbeKind.TCP), probeTarget(p), "target")
    }

    out += DevTest("vpn/probe-openvpn-falls-back-to-gateway", DevCategory.VPN, "OpenVPN без remote -> шлюз профиля") {
        val p = VpnProfile(protocol = "openvpn", gateway = "gw.example", gatewayPort = 9999)
        checkEq(LinkTarget("gw.example", 9999, ProbeKind.TCP), probeTarget(p), "gateway")
        checkEq(null, probeTarget(VpnProfile(protocol = "openvpn")), "и шлюза нет")
    }

    out += DevTest("vpn/probe-singbox-json", DevCategory.VPN, "sing-box JSON: server и server_port") {
        val raw = """{"outbounds":[{"type":"vless","server":"10.0.0.1","server_port":8443}]}"""
        checkEq(LinkTarget("10.0.0.1", 8443, ProbeKind.TCP), probeTarget(VpnProfile(protocol = "singbox", rawConfig = raw)), "target")
    }

    out += DevTest("vpn/probe-singbox-quic-udp", DevCategory.VPN, "sing-box hysteria2 -- UDP") {
        val raw = """{"outbounds":[{"type":"hysteria2","server":"10.0.0.2","server_port":443}]}"""
        checkEq(ProbeKind.UDP, probeTarget(VpnProfile(protocol = "singbox", rawConfig = raw))?.kind, "kind")
        checkEq(null, probeTarget(VpnProfile(protocol = "singbox", rawConfig = "{}")), "нет полей")
    }

    out += DevTest("vpn/probe-link-vless-tcp", DevCategory.VPN, "vless-ссылка -> TCP") {
        val link = "vless://d34f89a1-0000-4000-8000-000000000001@example.com:443#n"
        checkEq(LinkTarget("example.com", 443, ProbeKind.TCP), probeTarget(VpnProfile(protocol = "vless", rawConfig = link)), "target")
    }

    out += DevTest("vpn/probe-link-hy2-udp", DevCategory.VPN, "hy2-ссылка -> UDP") {
        val link = "hy2://pass@5.5.5.5:8443#n"
        checkEq(ProbeKind.UDP, probeTarget(VpnProfile(protocol = "hysteria2", rawConfig = link))?.kind, "kind")
    }

    out += DevTest("vpn/probe-tor-bridge", DevCategory.VPN, "Tor: первый адрес моста") {
        val bridges = "obfs4 9.9.9.9:443 FINGERPRINT cert=abc iat-mode=0"
        checkEq(LinkTarget("9.9.9.9", 443, ProbeKind.TCP), probeTarget(VpnProfile(protocol = "tor", rawConfig = bridges)), "target")
        checkEq(null, probeTarget(VpnProfile(protocol = "tor", rawConfig = "нет адреса")), "без адреса")
    }

    out += DevTest("vpn/probe-gateway-fallback-tcp", DevCategory.VPN, "Неизвестный протокол -> шлюз, TCP") {
        val p = VpnProfile(protocol = "socks5", gateway = "gw.example", gatewayPort = 1080)
        checkEq(LinkTarget("gw.example", 1080, ProbeKind.TCP), probeTarget(p), "target")
        checkEq(
            LinkTarget("gw.example", 443, ProbeKind.TCP),
            probeTarget(p.copy(gatewayPort = 70000)),
            "битый порт заменяется на 443"
        )
        checkEq(null, probeTarget(VpnProfile(protocol = "socks5")), "пусто")
    }

    // --- Пинг плашки: какой коннект меряем ---

    val pingLink = profile(
        id = "l1", name = "L1", protocol = "vless",
        rawConfig = "vless://u@h.example:443#n", alive = true, latencyMs = 40
    )
    val pingLinkSlow = profile(
        id = "l2", name = "L2", protocol = "trojan",
        rawConfig = "trojan://u@h2.example:443#n", alive = true, latencyMs = 200
    )
    // Быстрее всех, но в urltest-группу не попадает: узел -- свой бэкенд.
    val pingWg = profile(
        id = "wg", name = "WG", protocol = VpnProtocol.WIREGUARD.code,
        rawConfig = "vless://u@wg.example:443#n", alive = true, latencyMs = 1
    )
    val pingBridge = profile(
        id = "t1", name = "T1", protocol = VpnProtocol.TOR.code,
        rawConfig = "obfs4 9.9.9.9:443 FP cert=abc iat-mode=0", alive = true, latencyMs = 90
    )

    out += DevTest("vpn/ping-profile-manual", DevCategory.VPN, "Без автовыбора -- пинг активного коннекта") {
        val config = VpnConfig(activeProfileId = "l2", profiles = listOf(pingLink, pingLinkSlow, pingBridge))
        checkEq("l2", VpnConnects.pingProfile(config)?.id, "активный, даже если не самый быстрый")
        checkEq(null, VpnConnects.pingProfile(VpnConfig()), "активного нет")
        val wgOnly = VpnConfig(activeProfileId = "wg", profiles = listOf(pingWg))
        checkEq("wg", VpnConnects.pingProfile(wgOnly)?.id, "вручную -- что выбрано, то и меряем")
    }

    out += DevTest("vpn/ping-profile-group-top-member", DevCategory.VPN, "Группа: самый быстрый из участников") {
        val config = VpnConfig(
            groupMode = true,
            activeProfileId = "l2",
            profiles = listOf(pingLink, pingLinkSlow, pingWg, pingBridge)
        )
        val picked = VpnConnects.pingProfile(config)
        checkEq("l1", picked?.id, "wg и мост в группу не входят, верх списка -- 40 ms")
        checkEq(40, picked?.health?.latencyMs, "именно его и показываем")
    }

    out += DevTest("vpn/ping-profile-group-tor-kept", DevCategory.VPN, "Группа + активный мост -> мост") {
        val config = VpnConfig(
            groupMode = true,
            activeProfileId = "t1",
            profiles = listOf(pingLink, pingBridge)
        )
        checkEq("t1", VpnConnects.pingProfile(config)?.id, "тор работает ровно с активным мостом")
    }

    out += DevTest("vpn/ping-profile-group-empty", DevCategory.VPN, "В группу никто не попал -> пинга нет") {
        val config = VpnConfig(groupMode = true, profiles = listOf(pingWg))
        checkEq(null, VpnConnects.pingProfile(config), "только wg -- показывать нечего")
    }

    out += DevTest("vpn/group-members-mirror-boxgroup", DevCategory.VPN, "Участники группы -- те же, что у urltest") {
        val cases = listOf(
            Triple("a", profile(id = "a", protocol = "vless", rawConfig = "vless://u@h:443#n", alive = true), true),
            Triple("d", profile(id = "d", protocol = "vless", rawConfig = "vless://u@h:443#n", alive = false), false),
            Triple("u", profile(id = "u", protocol = "vless", rawConfig = "vless://u@h:443#n"), true),
            Triple("n", profile(id = "n", protocol = "vless", rawConfig = "{}"), false),
            Triple("t", profile(id = "t", protocol = VpnProtocol.TOR.code, rawConfig = "vless://u@h:443#n"), false),
            Triple("w", profile(id = "w", protocol = VpnProtocol.WIREGUARD.code, rawConfig = "vless://u@h:443#n"), false),
            Triple("o", profile(id = "o", protocol = VpnProtocol.OPENVPN.code, rawConfig = "vless://u@h:443#n"), false),
            Triple("s", profile(id = "s", protocol = VpnProtocol.SINGBOX.code, rawConfig = "vless://u@h:443#n"), false)
        )
        val members = VpnConnects.groupMembers(cases.map { it.second }).map { it.id }.toSet()
        val expected = cases.filter { it.third }.map { it.first }.toSet()
        checkEq(expected, members, "участники")
    }

    // --- Автовыбор моста: переключаться или нет ---

    val aliveCurrent = profile(id = "c", name = "C", alive = true, latencyMs = 200)
    val deadCurrent = profile(id = "c", name = "C", alive = false, latencyMs = 10)

    out += DevTest("vpn/better-bridge-dead-current", DevCategory.VPN, "Мёртвого текущего меняем на живого") {
        val candidate = profile(id = "k", name = "K", alive = true, latencyMs = 5000)
        checkEq("k", VpnConnects.betterBridge(deadCurrent, listOf(candidate), 50)?.id, "пусть даже и медленного")
        checkEq(
            null,
            VpnConnects.betterBridge(deadCurrent, listOf(profile(id = "z", alive = false)), 50),
            "живых кандидатов нет"
        )
    }

    out += DevTest("vpn/better-bridge-gain-threshold", DevCategory.VPN, "Выигрыш меньше порога -- не трогаем соединение") {
        val small = profile(id = "small", alive = true, latencyMs = 170)
        val big = profile(id = "big", alive = true, latencyMs = 100)
        checkEq("big", VpnConnects.betterBridge(aliveCurrent, listOf(small, big), 50)?.id, "100 ms выигрыша")
        checkEq(null, VpnConnects.betterBridge(aliveCurrent, listOf(small), 50), "30 ms -- шум, а не повод")
        checkEq(
            null,
            VpnConnects.betterBridge(aliveCurrent, listOf(profile(id = "same", alive = true, latencyMs = 200)), 50),
            "ровно тот же пинг"
        )
    }

    out += DevTest("vpn/better-bridge-no-baseline", DevCategory.VPN, "Текущий без замера -- сравнивать не с чем") {
        val unmeasured = profile(id = "c", alive = true, latencyMs = -1)
        val candidate = profile(id = "k", alive = true, latencyMs = 1)
        checkEq(null, VpnConnects.betterBridge(unmeasured, listOf(candidate), 50), "не гадаем")
    }

    out += DevTest("vpn/better-bridge-unusable-candidates", DevCategory.VPN, "Кандидат обязан быть живым с замером") {
        val dead = profile(id = "dead", alive = false, latencyMs = 1)
        val silent = profile(id = "silent", alive = true, latencyMs = -1)
        val silentUnknown = profile(id = "unknown", alive = null, latencyMs = 5)
        checkEq(
            null,
            VpnConnects.betterBridge(aliveCurrent, listOf(dead, silent, silentUnknown), 50),
            "ни один не подходит"
        )
        checkEq("k", VpnConnects.betterBridge(null, listOf(dead, profile(id = "k", alive = true, latencyMs = 5)), 50)?.id,
            "без текущего -- первый пригодный")
    }

    out += DevTest("vpn/better-bridge-fastest-of-batch", DevCategory.VPN, "Из кандидатов берётся самый быстрый") {
        val candidates = listOf(
            profile(id = "slow", alive = true, latencyMs = 150),
            profile(id = "fast", alive = true, latencyMs = 10),
            profile(id = "mid", alive = true, latencyMs = 90)
        )
        checkEq("fast", VpnConnects.betterBridge(aliveCurrent, candidates, 50)?.id, "10 ms")
        checkEq(
            null,
            VpnConnects.betterBridge(aliveCurrent, listOf(aliveCurrent), 50),
            "сам себя не обгоняет"
        )
    }

    // --- Kill switch: живой TUN и DNS-исключения ---

    out += DevTest("vpn/tunnel-state-is-live", DevCategory.VPN, "Переподключение -- рабочее состояние туннеля") {
        checkTrue(TunnelState.CONNECTING.isLive, "подключение live")
        checkTrue(TunnelState.CONNECTED.isLive, "сессия live")
        checkTrue(TunnelState.RECONNECTING.isLive, "переподключение live: СТОП доступен")
        checkFalse(TunnelState.DISCONNECTED.isLive, "выключенный не live")
        checkFalse(TunnelState.ERROR.isLive, "ошибка не live")
    }

    out += DevTest("vpn/kill-switch-dns-default-off", DevCategory.VPN, "DNS при kill switch выключен по умолчанию") {
        checkFalse(VpnConfig().killSwitchDns, "дефолт -- без исключений")
    }

    out += DevTest("vpn/kill-switch-dns-survives-json", DevCategory.VPN, "Флаг DNS переживает сериализацию") {
        val cfg = VpnConfig(killSwitch = true, killSwitchDns = true, dnsServers = "9.9.9.9, 149.112.112.112")
        val back = ConfigCodec.decodeConfig(ConfigCodec.encodeConfig(AppConfig(vpn = cfg)))
        checkTrue(back.vpn.killSwitchDns, "флаг доехал")
        checkEq(listOf("9.9.9.9", "149.112.112.112"), back.vpn.dnsList(), "свой список DNS на месте")
    }

    // --- Оси состояния: здоровье маршрута и фаза жизненного цикла ---

    out += DevTest("vpn/route-health-triple", DevCategory.VPN, "Здоровье маршрута -- три, а не два состояния") {
        checkEq(RouteHealth.UNKNOWN, LinkHealth(alive = null).route, "не проверялось -- не то же, что живо")
        checkEq(RouteHealth.HEALTHY, LinkHealth(alive = true).route, "живо -- здоровье")
        checkEq(RouteHealth.UNHEALTHY, LinkHealth(alive = false).route, "мёртвое -- нездоровье")
    }

    out += DevTest("vpn/lifecycle-phase-tunnel", DevCategory.VPN, "Фазы туннеля: покой, подъём, работа") {
        checkEq(LifecyclePhase.INACTIVE, TunnelState.DISCONNECTED.phase, "выключенный -- покой")
        checkEq(LifecyclePhase.ESTABLISHING, TunnelState.CONNECTING.phase, "подключение -- подъём")
        checkEq(LifecyclePhase.ACTIVE, TunnelState.CONNECTED.phase, "сессия -- работа")
        checkEq(LifecyclePhase.ACTIVE, TunnelState.RECONNECTING.phase, "переподключение при живом TUN -- работа")
        checkEq(LifecyclePhase.INACTIVE, TunnelState.ERROR.phase, "ошибка -- покой")
    }

    out += DevTest("vpn/lifecycle-phase-daemon-gps", DevCategory.VPN, "Фазы демона и подмены на той же оси") {
        checkEq(LifecyclePhase.INACTIVE, DaemonState.STOPPED.phase, "демон остановлен -- покой")
        checkEq(LifecyclePhase.ESTABLISHING, DaemonState.STARTING.phase, "демон стартует -- подъём")
        checkEq(LifecyclePhase.ACTIVE, DaemonState.RUNNING.phase, "демон работает -- работа")
        checkEq(LifecyclePhase.INACTIVE, DaemonState.ERROR.phase, "демон упал -- покой")
        checkEq(LifecyclePhase.INACTIVE, GpsState.OFF.phase, "подмена выключена -- покой")
        checkEq(LifecyclePhase.ESTABLISHING, GpsState.STARTING.phase, "подмена стартует -- подъём")
        checkEq(LifecyclePhase.ACTIVE, GpsState.ACTIVE.phase, "подмена работает -- работа")
        checkEq(LifecyclePhase.INACTIVE, GpsState.ERROR.phase, "подмена упала -- покой")
    }

    out += DevTest("vpn/is-live-matches-phase", DevCategory.VPN, "Живость туннеля -- это фаза, не список состояний") {
        for (state in TunnelState.entries) {
            checkEq(
                state.phase != LifecyclePhase.INACTIVE,
                state.isLive,
                "isLive и фаза расходятся на $state"
            )
        }
    }
}
