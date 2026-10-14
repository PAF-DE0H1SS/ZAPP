package xyz.azraellab.zapp.core.dev

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.TorBridge
import xyz.azraellab.zapp.core.TorBridges
import xyz.azraellab.zapp.core.GpsRouteCodec
import xyz.azraellab.zapp.core.RoutePoint
import xyz.azraellab.zapp.core.net.ClashStats
import xyz.azraellab.zapp.core.net.FlexibleBase64
import xyz.azraellab.zapp.core.net.ProxyScheme
import xyz.azraellab.zapp.core.net.ProxyUri
import xyz.azraellab.zapp.core.net.SingboxConfig
import xyz.azraellab.zapp.core.net.WireGuardIni
import xyz.azraellab.zapp.core.net.encodePath
import xyz.azraellab.zapp.core.net.encodeQuery
import xyz.azraellab.zapp.core.net.clashStatus
import xyz.azraellab.zapp.core.net.decodeClashBody
import xyz.azraellab.zapp.core.VpnConfig
import kotlinx.serialization.json.Json

/**
 * Разбор и сборка форматов: ссылки, ini, base64, мосты, маршруты, json.
 *
 * Общий приём -- обратимость: `parse -> write -> parse` не должен терять
 * поля. Форматы не наши, их диктуют чужие приложения и подписки, поэтому
 * здесь ловятся именно те варианты, что реально встречаются.
 */
private const val UUID = "d34f89a1-0000-4000-8000-000000000001"

private val json = Json { ignoreUnknownKeys = true }

internal fun parserTests(out: MutableList<DevTest>) {

    // --- ProxyUri: vless ---

    val vless =
        "vless://$UUID@example.com:443" +
            "?encryption=none&security=tls&type=ws&path=%2Fws&host=cdn.example.com" +
            "&sni=example.com#My%20Node"

    val vlessConfig = ProxyUri.parse(vless)
    checkNotNull(vlessConfig) { "фикстура vless должна парситься (это проверка самих тестов)" }

    out += DevTest("parsers/vless-scheme", DevCategory.PARSERS, "vless: схема") {
        checkEq(ProxyScheme.VLESS, requireNotNull(ProxyUri.parse(vless)).scheme, "scheme")
    }
    out += DevTest("parsers/vless-server", DevCategory.PARSERS, "vless: адрес сервера") {
        checkEq("example.com", requireNotNull(ProxyUri.parse(vless)).server, "server")
    }
    out += DevTest("parsers/vless-port", DevCategory.PARSERS, "vless: порт") {
        checkEq(443, requireNotNull(ProxyUri.parse(vless)).port, "port")
    }
    out += DevTest("parsers/vless-auth", DevCategory.PARSERS, "vless: uuid") {
        checkEq(UUID, requireNotNull(ProxyUri.parse(vless)).auth, "auth")
    }
    out += DevTest("parsers/vless-transport", DevCategory.PARSERS, "vless: транспорт ws") {
        checkEq("ws", requireNotNull(ProxyUri.parse(vless)).transport, "transport")
    }
    out += DevTest("parsers/vless-security", DevCategory.PARSERS, "vless: tls") {
        checkEq("tls", requireNotNull(ProxyUri.parse(vless)).security, "security")
    }
    out += DevTest("parsers/vless-path", DevCategory.PARSERS, "vless: путь декодируется") {
        checkEq("/ws", requireNotNull(ProxyUri.parse(vless)).path, "path")
    }
    out += DevTest("parsers/vless-host-header", DevCategory.PARSERS, "vless: host из query") {
        checkEq("cdn.example.com", requireNotNull(ProxyUri.parse(vless)).host, "host")
    }
    out += DevTest("parsers/vless-sni", DevCategory.PARSERS, "vless: sni") {
        checkEq("example.com", requireNotNull(ProxyUri.parse(vless)).sni, "sni")
    }
    out += DevTest("parsers/vless-name", DevCategory.PARSERS, "vless: имя из фрагмента") {
        checkEq("My Node", requireNotNull(ProxyUri.parse(vless)).name, "name")
    }
    out += DevTest("parsers/vless-complete", DevCategory.PARSERS, "vless: конфиг полный") {
        checkTrue(requireNotNull(ProxyUri.parse(vless)).isComplete, "isComplete")
    }

    out += DevTest("parsers/reality-params-roundtrip", DevCategory.PARSERS, "reality-параметры переживают пересборку") {
        val link = "vless://$UUID@1.2.3.4:443" +
            "?security=reality&type=tcp&pbk=PUBLIC&sid=abc&fp=chrome&sni=www.example.com#reality"
        val config = requireNotNull(ProxyUri.parse(link)) { "reality ссылка" }
        checkEq("reality", config.security, "security")
        checkEq("PUBLIC", config.params["pbk"], "pbk")
        val reparsed = requireNotNull(ProxyUri.parse(ProxyUri.write(config))) { "пересборка" }
        checkEq("reality", reparsed.security, "security после цикла")
        checkEq("PUBLIC", reparsed.params["pbk"], "pbk после цикла")
        checkEq("abc", reparsed.params["sid"], "sid после цикла")
    }

    out += DevTest("parsers/trojan-defaults-tls", DevCategory.PARSERS, "trojan по умолчанию tls") {
        val config = requireNotNull(ProxyUri.parse("trojan://secret@host.example:443?type=tcp#t"))
        checkEq(ProxyScheme.TROJAN, config.scheme, "scheme")
        checkEq("secret", config.auth, "auth")
        checkEq("tls", config.security, "security")
    }

    // --- ProxyUri: shadowsocks ---

    out += DevTest("parsers/ss-userinfo-form", DevCategory.PARSERS, "ss:// метод:пароль@хост:порт") {
        val link = "ss://" + FlexibleBase64.encodeNoPadding("aes-256-gcm:pass word") +
            "@10.0.0.1:8388#ss-node"
        val config = requireNotNull(ProxyUri.parse(link))
        checkEq("aes-256-gcm", config.cipher, "cipher")
        checkEq("pass word", config.auth, "auth")
        checkEq("10.0.0.1", config.server, "server")
        checkEq(8388, config.port, "port")
        checkEq("ss-node", config.name, "name")
    }

    out += DevTest("parsers/ss-legacy-form", DevCategory.PARSERS, "ss:// base64(method:pass@host:port) -- старая форма") {
        val link = "ss://" +
            FlexibleBase64.encodeNoPadding("chacha20-ietf-poly1305:pw@10.0.0.2:8388") + "#old"
        val config = requireNotNull(ProxyUri.parse(link))
        checkEq("chacha20-ietf-poly1305", config.cipher, "cipher")
        checkEq("pw", config.auth, "auth")
        checkEq("10.0.0.2", config.server, "server")
        checkEq(8388, config.port, "port")
    }

    out += DevTest("parsers/ss-roundtrip", DevCategory.PARSERS, "ss: цикл parse -> write -> parse") {
        val original = requireNotNull(
            ProxyUri.parse(
                "ss://" + FlexibleBase64.encodeNoPadding("aes-128-gcm:secret") + "@1.1.1.1:443#n"
            )
        )
        val reparsed = requireNotNull(ProxyUri.parse(ProxyUri.write(original)))
        checkEq(original.cipher, reparsed.cipher, "cipher")
        checkEq(original.auth, reparsed.auth, "auth")
        checkEq(original.server, reparsed.server, "server")
        checkEq(original.port, reparsed.port, "port")
        checkEq(original.name, reparsed.name, "name")
    }

    // --- ProxyUri: vmess ---

    val vmessJson = """{"v":"2","ps":"vm","add":"2.2.2.2","port":"8443","id":"uuid-1",""" +
        """"aid":"0","net":"ws","host":"h.example.com","path":"/vmess","tls":"tls","sni":"h.example.com"}"""

    out += DevTest("parsers/vmess-from-base64-json", DevCategory.PARSERS, "vmess: разбор base64+JSON") {
        val config = requireNotNull(ProxyUri.parse("vmess://" + FlexibleBase64.encodeNoPadding(vmessJson)))
        checkEq(ProxyScheme.VMESS, config.scheme, "scheme")
        checkEq("vm", config.name, "name")
        checkEq("2.2.2.2", config.server, "server")
        checkEq(8443, config.port, "port")
        checkEq("uuid-1", config.auth, "auth")
        checkEq("ws", config.transport, "transport")
        checkEq("tls", config.security, "security")
        checkEq("/vmess", config.path, "path")
        checkEq("0", config.params["alterId"], "alterId")
    }

    out += DevTest("parsers/vmess-roundtrip", DevCategory.PARSERS, "vmess: цикл переживает поля") {
        val original = requireNotNull(
            ProxyUri.parse(
                "vmess://" + FlexibleBase64.encodeNoPadding(
                    """{"v":"2","ps":"vm","add":"2.2.2.2","port":"8443","id":"uuid-1",""" +
                        """"aid":"16","net":"tcp","type":"none","tls":""}"""
                )
            )
        )
        val reparsed = requireNotNull(ProxyUri.parse(ProxyUri.write(original)))
        checkEq(original.server, reparsed.server, "server")
        checkEq(original.port, reparsed.port, "port")
        checkEq(original.auth, reparsed.auth, "auth")
        checkEq("16", reparsed.params["alterId"], "alterId")
    }

    out += DevTest("parsers/hy2-alias", DevCategory.PARSERS, "hy2 -- это hysteria2") {
        val config = requireNotNull(
            ProxyUri.parse("hy2://password@5.5.5.5:8443?sni=example.com&insecure=1#h")
        )
        checkEq(ProxyScheme.HYSTERIA2, config.scheme, "scheme")
        checkEq("1", config.params["insecure"], "insecure")
        checkEq("example.com", config.sni, "sni")
        checkEq("hysteria2", ProxyUri.write(config).substringBefore("://"), "каноническая схема")
    }

    out += DevTest("parsers/vless-ipv6-endpoint", DevCategory.PARSERS, "vless: IPv6 в скобках") {
        val config = requireNotNull(
            ProxyUri.parse("vless://$UUID@[2001:db8::1]:8443#v6")
        )
        checkEq("2001:db8::1", config.server, "server")
        checkEq(8443, config.port, "port")
    }

    // --- ProxyUri: списки ---

    out += DevTest("parsers/parse-all-dedup-keeps-order", DevCategory.PARSERS, "parseAll дедуплицирует и сохраняет порядок") {
        val first = "vless://$UUID@a.example:443#one"
        val second = "trojan://pw@b.example:443#two"
        val list = ProxyUri.parseAll("$first\n$second\n$first\n")
        checkEq(2, list.size, "размер")
        checkEq("one", list[0].name, "первый")
        checkEq("two", list[1].name, "второй")
    }

    out += DevTest("parsers/parse-all-base64-subscription", DevCategory.PARSERS, "parseAll читает base64-подписку") {
        val body = "vless://$UUID@a.example:443#one\ntrojan://pw@b.example:443#two\n"
        val list = ProxyUri.parseAll(FlexibleBase64.encodeNoPadding(body))
        checkEq(2, list.size, "размер")
        checkEq("one", list[0].name, "первый")
        checkEq("two", list[1].name, "второй")
    }

    out += DevTest("parsers/parse-all-base64-line", DevCategory.PARSERS, "parseAll распаковывает строку- base64") {
        val line = "vless://$UUID@a.example:443#one"
        val list = ProxyUri.parseAll(FlexibleBase64.encodeNoPadding(line))
        checkEq(1, list.size, "размер")
        checkEq("one", list[0].name, "имя")
    }

    out += DevTest("parsers/parse-all-ignores-garbage", DevCategory.PARSERS, "Мусор в списке не ломает разбор") {
        val list = ProxyUri.parseAll(
            "not a link\nvless://$UUID@a.example:443#one\n\nftp://file.example/file"
        )
        checkEq(1, list.size, "размер")
        checkEq("one", list[0].name, "имя")
    }

    out += DevTest("parsers/percent-decode", DevCategory.PARSERS, "percentDecode: + это плюс, %20 -- пробел") {
        checkEq("/a+b", ProxyUri.percentDecode("/a+b"), "плюс")
        checkEq("/a b", ProxyUri.percentDecode("/a%20b"), "пробел")
        checkEq("имя", ProxyUri.percentDecode("%D0%B8%D0%BC%D1%8F"), "кириллица")
    }

    out += DevTest("parsers/reject-unknown-scheme", DevCategory.PARSERS, "Неизвестная схема и мусор -> null") {
        checkEq(null, ProxyUri.parse("ftp://file.example/file"), "ftp")
        checkEq(null, ProxyUri.parse("just some text"), "текст")
        checkEq(null, ProxyUri.parse("://broken"), "без схемы")
        checkEq(null, ProxyUri.parse(""), "пусто")
    }

    out += DevTest("parsers/display-name-falls-back", DevCategory.PARSERS, "Имя списка падает на адрес") {
        val config = requireNotNull(ProxyUri.parse("vless://$UUID@host.example:443"))
        checkEq("", config.name, "имени нет")
        checkEq("host.example:443", config.displayName(), "displayName")
    }

    // --- FlexibleBase64 ---

    out += DevTest("parsers/base64-standard", DevCategory.PARSERS, "Стандартный base64 декодируется") {
        checkEq("hello", FlexibleBase64.decodeToStringOrNull("aGVsbG8="), "hello")
    }

    out += DevTest("parsers/base64-urlsafe", DevCategory.PARSERS, "URL-safe base64 декодируется") {
        // '-' и '_' вместо '+' и '/'.
        val encoded = FlexibleBase64.encodeNoPadding(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte()))
        val urlSafe = encoded.replace('+', '-').replace('/', '_')
        val decoded = FlexibleBase64.decodeOrNull(urlSafe)
        checkNotNull(decoded) { "url-safe вариант" }
        checkEq(
            FlexibleBase64.decodeOrNull(encoded)?.toList(),
            decoded.toList(),
            "декодирование совпадает"
        )
    }

    out += DevTest("parsers/base64-unpadded", DevCategory.PARSERS, "Base64 без заполнения доделывается") {
        // "hello" -> aGVsbG8 (7 символов, без '=').
        checkEq("hello", FlexibleBase64.decodeToStringOrNull("aGVsbG8"), "без паддинга")
        checkEq("hell", FlexibleBase64.decodeToStringOrNull("aGVsbA"), "остаток 2")
        checkEq("hel", FlexibleBase64.decodeToStringOrNull("aGVs"), "остаток 3")
    }

    out += DevTest("parsers/base64-invalid", DevCategory.PARSERS, "Невозможный base64 -> null") {
        checkEq(null, FlexibleBase64.decodeOrNull(""), "пусто")
        checkEq(null, FlexibleBase64.decodeOrNull("abcde"), "остаток 1")
        checkEq(null, FlexibleBase64.decodeOrNull("a!b@"), "лишние символы")
        checkEq(null, FlexibleBase64.decodeToStringOrNull("not base64!@#"), "знаки препинания")
    }

    out += DevTest("parsers/base64-encode-no-padding-roundtrip", DevCategory.PARSERS, "encodeNoPadding без '=' и обратно") {
        val text = "весь текст подписки целиком"
        val encoded = FlexibleBase64.encodeNoPadding(text)
        checkFalse(encoded.contains('='), "паддинг должен быть срезан: $encoded")
        checkEq(text, FlexibleBase64.decodeToStringOrNull(encoded), "roundtrip")
    }

    // --- WireGuardIni ---

    val wgSample = """
        # some comment
        [Interface]
        PrivateKey = PRIVATE_KEY_VALUE
        Address = 10.0.0.2/32, fd00::2/128
        DNS = 1.1.1.1, 9.9.9.9
        MTU = 1280

        [Peer]
        PublicKey = PUBLIC_KEY_VALUE
        PresharedKey = PSK_VALUE
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = wg.example.com:51820
        PersistentKeepalive = 25
    """.trimIndent()

    val wgFields = listOf(
        "name" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.name },
        "privateKey" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.privateKey },
        "publicKey" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.publicKey },
        "presharedKey" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.presharedKey },
        "address" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.address },
        "dnsServers" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.dnsServers },
        "mtu" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.mtu },
        "allowedIps" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.allowedIps },
        "gateway" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.gateway },
        "gatewayPort" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.gatewayPort },
        "keepaliveSeconds" to { p: xyz.azraellab.zapp.core.VpnProfile -> p.keepaliveSeconds }
    )
    val wgExpected = mapOf<String, Any?>(
        "name" to "home",
        "privateKey" to "PRIVATE_KEY_VALUE",
        "publicKey" to "PUBLIC_KEY_VALUE",
        "presharedKey" to "PSK_VALUE",
        "address" to "10.0.0.2/32, fd00::2/128",
        "dnsServers" to "1.1.1.1, 9.9.9.9",
        "mtu" to 1280,
        "allowedIps" to "0.0.0.0/0, ::/0",
        "gateway" to "wg.example.com",
        "gatewayPort" to 51820,
        "keepaliveSeconds" to 25
    )
    wgFields.forEach { (field, getter) ->
        out += DevTest(
            "parsers/wg-field-$field",
            DevCategory.PARSERS,
            "WireGuard.ini: поле $field"
        ) {
            val profile = requireNotNull(WireGuardIni.parse(wgSample, name = "home")) { "parse" }
            checkEq(wgExpected[field], getter(profile), "поле $field")
        }
    }

    out += DevTest("parsers/wg-reject-without-peer", DevCategory.PARSERS, "Без [Peer] конфиг не конфиг") {
        checkEq(null, WireGuardIni.parse("[Interface]\nPrivateKey = x"), "только Interface")
        checkEq(null, WireGuardIni.parse("random text"), "мусор")
    }

    out += DevTest("parsers/wg-first-peer-wins", DevCategory.PARSERS, "Из нескольких пиров берётся первый") {
        val text = """
            [Interface]
            PrivateKey = k1
            [Peer]
            PublicKey = FIRST
            [Peer]
            PublicKey = SECOND
        """.trimIndent()
        val profile = requireNotNull(WireGuardIni.parse(text))
        checkEq("FIRST", profile.publicKey, "первый пир")
    }

    out += DevTest("parsers/wg-ipv6-endpoint", DevCategory.PARSERS, "IPv6-endpoint в скобках") {
        val text = """
            [Interface]
            PrivateKey = k
            [Peer]
            PublicKey = p
            Endpoint = [2001:db8::1]:51821
        """.trimIndent()
        val profile = requireNotNull(WireGuardIni.parse(text))
        checkEq("2001:db8::1", profile.gateway, "хост")
        checkEq(51821, profile.gatewayPort, "порт")
    }

    out += DevTest("parsers/wg-roundtrip", DevCategory.PARSERS, "ini переживает цикл write -> parse") {
        val original = requireNotNull(WireGuardIni.parse(wgSample, name = "home"))
        val reparsed = requireNotNull(WireGuardIni.parse(WireGuardIni.write(original), name = original.name))
        wgFields.forEach { (field, getter) ->
            checkEq(getter(original), getter(reparsed), "поле $field после цикла")
        }
    }

    out += DevTest("parsers/wg-crlf-and-comments", DevCategory.PARSERS, "Windows-переносы и ;-комментарии") {
        val text = "[Interface]\r\nPrivateKey = k1\r\n; comment\r\n[Peer]\r\nPublicKey = p1\r\n"
        val profile = requireNotNull(WireGuardIni.parse(text))
        checkEq("k1", profile.privateKey, "privateKey")
        checkEq("p1", profile.publicKey, "publicKey")
    }

    // --- TorBridges ---

    out += DevTest("parsers/bridge-vanilla", DevCategory.PARSERS, "Vanilla-мост без транспорта") {
        val bridges = TorBridges.parse("1.2.3.4:9001 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        checkEq(1, bridges.size, "размер")
        checkEq(TorBridge("", "1.2.3.4", 9001), bridges[0], "мост")
        checkEq("1.2.3.4:9001", bridges[0].endpoint, "endpoint")
    }

    out += DevTest("parsers/bridge-obfs4-prefix", DevCategory.PARSERS, "Bridge obfs4 ... -- транспорт и адрес") {
        val bridges = TorBridges.parse("Bridge obfs4 5.6.7.8:443 FINGERPRINT cert=abc iat-mode=0")
        checkEq(1, bridges.size, "размер")
        checkEq("obfs4", bridges[0].transport, "transport")
        checkEq("5.6.7.8", bridges[0].host, "host")
        checkEq(443, bridges[0].port, "port")
    }

    out += DevTest("parsers/bridge-webtunnel-ipv6", DevCategory.PARSERS, "webtunnel: IPv6 в скобках сохраняется в endpoint") {
        val bridges = TorBridges.parse(
            "webtunnel [2001:db8:c934:3cba:7cd2:7e56:c8c9:40a6]:443 " +
                "412F21AAD09FA4F83AD52186458466BEC5D27795 url=https://cdn.example.org/xyz ver=0"
        )
        checkEq(1, bridges.size, "размер")
        checkEq("webtunnel", bridges[0].transport, "transport")
        checkEq("2001:db8:c934:3cba:7cd2:7e56:c8c9:40a6", bridges[0].host, "host")
        checkEq(
            "[2001:db8:c934:3cba:7cd2:7e56:c8c9:40a6]:443",
            bridges[0].endpoint,
            "endpoint"
        )
    }

    out += DevTest("parsers/bridge-skips-noise", DevCategory.PARSERS, "Комментарии и пустые строки пропускаются") {
        val bridges = TorBridges.parse("# комментарий\nBridge 9.9.9.9:9001 ABCDEF0123456789\n\n")
        checkEq(1, bridges.size, "размер")
        checkEq("9.9.9.9", bridges[0].host, "host")
    }

    out += DevTest("parsers/bridge-url-skipped", DevCategory.PARSERS, "URL вместо адреса -- не мост") {
        val bridges = TorBridges.parse("Bridge https://example.org:443/path ABCDEF0123456789")
        checkTrue(bridges.isEmpty(), "список должен быть пуст")
    }

    out += DevTest("parsers/bridge-port-range", DevCategory.PARSERS, "Порт вне 1..65535 отбрасывается") {
        checkTrue(TorBridges.parse("1.2.3.4:70000 AAAA").isEmpty(), "70000")
        checkTrue(TorBridges.parse("1.2.3.4:0 AAAA").isEmpty(), "0")
        checkEq(1, TorBridges.parse("1.2.3.4:65535 AAAA").size, "65535 -- можно")
    }

    out += DevTest("parsers/bridge-real-shape", DevCategory.PARSERS, "Реальная подписка top-100 разбирается") {
        val raw = """
            185.220.101.1:443 6BAA1B6BFD4D1A0E4C7E0D0B1C2D3E4F5A6B7C8D
            obfs4 104.244.72.128:443 B0F3C4E5A6D7F8091A2B3C4D5E6F708192A3B4C5 cert=aaa iat-mode=0
            snowflake 192.0.2.1:80
        """.trimIndent()
        val bridges = TorBridges.parse(raw)
        checkEq(3, bridges.size, "размер")
        checkEq("", bridges[0].transport, "vanilla без транспорта")
        checkEq("obfs4", bridges[1].transport, "obfs4")
        checkEq("snowflake", bridges[2].transport, "snowflake")
        checkEq("192.0.2.1:80", bridges[2].endpoint, "endpoint")
    }

    out += DevTest("parsers/bridge-transport-set", DevCategory.PARSERS, "Транспорты lyrebird распознаются") {
        for (transport in listOf("obfs4", "snowflake", "webtunnel", "scramblesuit")) {
            val bridges = TorBridges.parse("$transport 10.0.0.1:443 FINGERPRINT")
            checkEq(1, bridges.size, "$transport: размер")
            checkEq(transport, bridges[0].transport, "$transport: транспорт")
        }
    }

    // --- GpsRouteCodec ---

    out += DevTest("parsers/route-parse-basic", DevCategory.PARSERS, "Маршрут: широта,долгота через ;") {
        val points = GpsRouteCodec.parse("55.75,37.61;59.93,30.33")
        checkEq(2, points.size, "размер")
        checkEq(55.75, points[0].latitude, "широта первой")
        checkEq(30.33, points[1].longitude, "долгота второй")
        checkEq(0, points[0].dwellSeconds, "пауза по умолчанию")
        checkEq(null, points[0].speedMps, "скорость по умолчанию")
    }

    out += DevTest("parsers/route-parse-full", DevCategory.PARSERS, "Маршрут: скорость и пауза в точке") {
        val points = GpsRouteCodec.parse("10,20,5.5,3;11,21")
        checkEq(2, points.size, "размер")
        checkEq(5.5, points[0].speedMps, "скорость")
        checkEq(3, points[0].dwellSeconds, "пауза")
        checkEq(null, points[1].speedMps, "у второй нет скорости")
    }

    out += DevTest("parsers/route-parse-skips-invalid", DevCategory.PARSERS, "Битые точки отбрасываются, годные остаются") {
        val points = GpsRouteCodec.parse("10,20;не число;999,0;11,21")
        checkEq(2, points.size, "осталось две валидные")
        checkEq(10.0, points[0].latitude, "первая валидная")
        checkEq(11.0, points[1].latitude, "вторая валидная")
    }

    out += DevTest("parsers/route-parse-empty", DevCategory.PARSERS, "Пустая строка -- пустой маршрут") {
        checkTrue(GpsRouteCodec.parse("").isEmpty(), "пусто")
        checkTrue(GpsRouteCodec.parse(";;").isEmpty(), "только разделители")
    }

    out += DevTest("parsers/route-roundtrip", DevCategory.PARSERS, "Маршрут переживает write -> parse") {
        val original = listOf(
            RoutePoint(55.75, 37.61, speedMps = 1.5, dwellSeconds = 2),
            RoutePoint(59.93, 30.33)
        )
        val reparsed = GpsRouteCodec.parse(GpsRouteCodec.write(original))
        checkEq(2, reparsed.size, "размер")
        checkEq(original[0].latitude, reparsed[0].latitude, "широта")
        checkEq(original[0].speedMps, reparsed[0].speedMps, "скорость")
        checkEq(original[0].dwellSeconds, reparsed[0].dwellSeconds, "пауза")
        checkEq(original[1].latitude, reparsed[1].latitude, "широта второй")
    }

    out += DevTest("parsers/route-validity", DevCategory.PARSERS, "Валиден маршрут от двух точек") {
        checkFalse(GpsRouteCodec.isValid(emptyList()), "пустой")
        checkFalse(GpsRouteCodec.isValid(listOf(RoutePoint(0.0, 0.0))), "одна точка")
        checkTrue(GpsRouteCodec.isValid(listOf(RoutePoint(0.0, 0.0), RoutePoint(1.0, 1.0))), "две точки")
    }

    // --- SingboxConfig ---

    val ss1 = "ss://" + FlexibleBase64.encodeNoPadding("aes-256-gcm:cGFzcw") + "@1.2.3.4:8388#one"
    val ss2 = "ss://" + FlexibleBase64.encodeNoPadding("aes-256-gcm:cGFzcw") + "@5.6.7.8:8388#two"

    out += DevTest("parsers/singbox-group-json-valid", DevCategory.PARSERS, "Групповой конфиг -- валидный json") {
        val built = SingboxConfig.buildGroup(VpnConfig(), listOf(ss1, ss2))
        val root = json.parseToJsonElement(built.json).jsonObject
        checkTrue("outbounds" in root, "нет outbounds")
        checkTrue(root["outbounds"]!!.jsonArray.isNotEmpty(), "outbounds пуст")
    }

    out += DevTest("parsers/singbox-group-urltest", DevCategory.PARSERS, "В группе есть urltest по узлам") {
        val built = SingboxConfig.buildGroup(VpnConfig(), listOf(ss1, ss2))
        val outbounds = json.parseToJsonElement(built.json).jsonObject["outbounds"]!!.jsonArray
        val urltest = outbounds.map { it.jsonObject }
            .firstOrNull { it["type"]?.jsonPrimitive?.content == "urltest" }
        checkNotNull(urltest) { "нет urltest" }
        val nodes = urltest!!["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        checkEq(2, nodes.size, "узлов в группе")
        val types = outbounds.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        checkIn("direct", types, "прямой маршрут обязан остаться")
    }

    out += DevTest("parsers/singbox-urltest-tuning", DevCategory.PARSERS, "urltest: интервал 1m и tolerance 10") {
        val built = SingboxConfig.buildGroup(VpnConfig(), listOf(ss1, ss2))
        val urltest = json.parseToJsonElement(built.json).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "urltest" }
        checkEq("1m", urltest["interval"]!!.jsonPrimitive.content, "interval")
        checkEq(10, urltest["tolerance"]!!.jsonPrimitive.content.toInt(), "tolerance")
        checkEq(
            "true",
            urltest["interrupt_exist_connections"]!!.jsonPrimitive.content,
            "interrupt_exist_connections"
        )
    }

    out += DevTest("parsers/singbox-duplicate-tags-renamed", DevCategory.PARSERS, "Дубликаты тегов разводятся") {
        val built = SingboxConfig.buildGroup(VpnConfig(), listOf(ss1, ss1))
        val tags = json.parseToJsonElement(built.json).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject["tag"]?.jsonPrimitive?.content }
        checkEq(tags.size, tags.toSet().size, "теги уникальны: $tags")
    }

    // --- Неподдерживаемые узлы: FATAL на старте роняет всю группу ---

    val ssPlugin = "ss://" + FlexibleBase64.encodeNoPadding("aes-256-gcm:cGFzcw") +
        "@9.9.9.9:8388?plugin=obfs-local%3Bobfs%3Dtls%3Bobfs-host%3Dexample.com#plugin"
    val vlessBadFlow = "vless://11111111-1111-1111-1111-111111111111@2.2.2.2:443" +
        "?type=tcp&security=tls&flow=xtls-rprx-vision-udp443#bad-flow"
    val vlessVision = "vless://22222222-2222-2222-2222-222222222222@3.3.3.3:443" +
        "?type=tcp&security=tls&flow=xtls-rprx-vision&pbk=PUBLIC#vision"

    fun groupOutbounds(links: List<String>) =
        json.parseToJsonElement(SingboxConfig.buildGroup(VpnConfig(), links).json)
            .jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }

    out += DevTest("parsers/singbox-group-skips-ss-plugin", DevCategory.PARSERS, "ss с SIP003-плагином не попадает в группу") {
        val outbounds = groupOutbounds(listOf(ssPlugin, ss1))
        val nodes = outbounds.mapNotNull { it["server"]?.jsonPrimitive?.content }
        checkFalse("9.9.9.9" in nodes, "ссылка с плагином отброшена")
        checkTrue("1.2.3.4" in nodes, "обычная ss осталась")
        checkTrue(outbounds.none { "plugin" in it }, "в json нет поля plugin")
    }

    out += DevTest("parsers/singbox-group-skips-bad-flow", DevCategory.PARSERS, "Неизвестный flow отброшен, vision остаётся") {
        val outbounds = groupOutbounds(listOf(vlessBadFlow, vlessVision, ss1))
        val flows = outbounds.mapNotNull { it["flow"]?.jsonPrimitive?.content }
        checkFalse(flows.any { it != "xtls-rprx-vision" }, "не-vision flow: $flows")
        checkEq(1, flows.size, "только vision-flow выжил")
        val servers = outbounds.mapNotNull { it["server"]?.jsonPrimitive?.content }
        checkFalse("2.2.2.2" in servers, "ссылка с чужим flow отброшена")
        checkTrue("3.3.3.3" in servers, "vision-ссылка осталась")
    }

out += DevTest("parsers/singbox-group-all-bad-fails-fast", DevCategory.PARSERS, "Группа из одних бракованных ссылок падает понятно") {
        val built = runCatching { SingboxConfig.buildGroup(VpnConfig(), listOf(ssPlugin, vlessBadFlow)) }
        checkTrue(built.isFailure, "ожидаем отказ: ни одной рабочей ссылки")
    }

    out += DevTest("parsers/clash-traffic-parse", DevCategory.PARSERS, "clash_api разбирается в байты") {
        val t = ClashStats.parse("""{"up": 12345, "down": 67890}""")
        checkNotNull(t) { "не распарсилось" }
        checkEq(12345L, t!!.up, "up")
        checkEq(67890L, t.down, "down")
        checkNotNull(ClashStats.parse("""{"up":0,"down":0}""")) { "нули валидны" }
        checkEq(null, ClashStats.parse("not json"), "мусор")
        checkEq(null, ClashStats.parse("""{"up": -1, "down": 1}"""), "минус -- не счётчик")
        // `/traffic` в нынешней сборке sing-box всегда отдаёт нули, поэтому
        // реально читается `/connections` -- у него свои имена полей, и
        // список сессий стоит перед итогами.
        val c = ClashStats.parse("""{"connections":[],"downloadTotal":7886987,"uploadTotal":2574347}""")
        checkNotNull(c) { "итоги /connections не распарсились" }
        checkEq(7886987L, c!!.down, "downloadTotal")
        checkEq(2574347L, c.up, "uploadTotal")
    }

    out += DevTest("parsers/clash-delay-probe", DevCategory.PARSERS, "Проба туннеля: ответ -- туннель жив") {
        checkEq(3936, ClashStats.delay("""{"delay":3936}"""), "задержка снята")
        checkEq(0, ClashStats.delay("""{"delay":0}"""), "мгновенно -- тоже ответ")
        // Провал замера приходит как {"message":…} без поля delay.
        checkEq(null, ClashStats.delay("""{"message":"An error occurred in the delay test"}"""), "провал пробы")
        checkEq(null, ClashStats.delay("not json"), "мусор")
        checkEq(null, ClashStats.delay("""{"delay":-5}"""), "отрицательного времени не бывает")
        checkEq(null, ClashStats.delay("""{"delay":999999999}"""), "перебор -- это уже зависший запрос")
    }

    out += DevTest("parsers/clash-probe-target", DevCategory.PARSERS, "Проба сама находит путь до узла") {
        // Замерено на устройстве с одним активным профилем: `/proxies`
        // отдаёт три записи -- `GLOBAL` (Fallback), сам узел и `Direct` --
        // группы auto нет вовсе, и подставить её имя наугад -- получить
        // 404 вместо пробы. Здесь у GLOBAL выбор указан; пустой вариант
        // проверен ниже.
        val single = """
            {"proxies":{
              "GLOBAL":{"type":"Fallback","now":"LV node","all":["LV node"]},
              "LV node":{"type":"VLESS"},
              "direct":{"type":"Direct"}
            }}
        """.trimIndent()
        checkEq("LV node", ClashStats.probeTarget(single), "одиночный узел")

        // Указатели ведут по цепочке: группа -> группа -> конкретный узел.
        val grouped = """
            {"proxies":{
              "GLOBAL":{"type":"Fallback","now":"auto","all":["auto"]},
              "auto":{"type":"URLTest","now":"node-1","all":["node-1","node-2"]},
              "node-1":{"type":"Trojan"},
              "node-2":{"type":"VMess"},
              "direct":{"type":"Direct"}
            }}
        """.trimIndent()
        checkEq("node-1", ClashStats.probeTarget(grouped), "автовыбор -> узел")

        // Настоящий ответ устройства: группа есть, но она ещё ничего не
        // выбирала (`now` пуст). Цель -- сама группа: трафик пойдёт именно
        // через неё, а падать на первый попавшийся узел нельзя -- тот может
        // быть мёртв при полностью рабочем туннеле, и наоборот.
        val unselected = """
            {"proxies":{
              "GLOBAL":{"type":"Fallback","now":"auto","all":["auto"]},
              "auto":{"type":"URLTest","now":"","all":["node-1","node-2"]},
              "node-1":{"type":"VLESS"},
              "direct":{"type":"Direct"}
            }}
        """.trimIndent()
        checkEq("auto", ClashStats.probeTarget(unselected), "группа без выбора -- цель сама группа")

        // Замерено на устройстве: у `GLOBAL` в одиночном конфиге тип
        // `Fallback`, и `/delay` по нему отвечает `404` за 7 мс, тогда как
        // реальный узел меряет полные 8 с. Если у `GLOBAL` нет выбора,
        // цель -- узел, иначе сторож убивал бы живой туннель через 15 с.
        val unselectedGlobal = """
            {"proxies":{
              "GLOBAL":{"type":"Fallback","now":"","all":["LV node"]},
              "LV node":{"type":"VLESS"},
              "direct":{"type":"Direct"}
            }}
        """.trimIndent()
        checkEq("LV node", ClashStats.probeTarget(unselectedGlobal), "GLOBAL без выбора -- берём узел")

        // Единственный выбор -- прямой выход: мимо прокси, замер по нему
        // ничего не доказал бы, поэтому цели нет вовсе.
        val onlyDirect =
            """{"proxies":{"GLOBAL":{"type":"Fallback","now":"direct"},"direct":{"type":"Direct"}}}"""
        checkEq(null, ClashStats.probeTarget(onlyDirect), "прямой выход -- не цель")
        checkEq(null, ClashStats.probeTarget("not json"), "мусор")
        checkEq(null, ClashStats.probeTarget("""{"proxies":{}}"""), "пусто")
    }

    out += DevTest("parsers/clash-probe-encoding", DevCategory.PARSERS, "Путь пробы кодируется побайтово") {
        // Регрессия: посимвольный обход рассыпал суррогатные пары эмодзи на
        // половинки, и цель `🇳🇱 …` не находилась -- 404 вместо замера.
        val enc = encodePath("🇳🇱 Netherlands")
        checkTrue(
            enc.startsWith("%F0%9F%87%B3%F0%9F%87%B1%20"),
            "эмодзи ушло в %XX побайтово: $enc"
        )
        checkEq("LV%20node%20%7C%20%5B%2ACIDR%5D", encodePath("LV node | [*CIDR]"), "ASCII-спецсимволы")
        checkEq("auto", encodePath("auto"), "обычный тег не трогаем")
        // Значение query уезжает целиком: `&` и `?` внутри адреса иначе
        // разорвут строку запроса, и сервер увидит обрезанную цель.
        checkEq(
            "https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204",
            encodeQuery("https://www.gstatic.com/generate_204"),
            "адрес целиком"
        )
        checkEq("a%20b", encodeQuery("a b"), "пробел")
        checkEq("a%2Bb", encodeQuery("a+b"), "плюс не остаётся плюсом")
    }

    out += DevTest("parsers/clash-body-utf8", DevCategory.PARSERS, "Ответ api читается как UTF-8") {
        // Регрессия: тело ответа читалось как ISO-8859-1, эмодзи в теге узла
        // превращалось в латиницу, повторное кодирование давало мусорный
        // путь -- и сервер отвечал 404 на полностью живом узле. В group-режиме
        // баг прятался: там цель `auto`, чистый ASCII.
        val json = """{"proxies":{"🇳🇱 Netherlands | [*CIDR]":{"type":"VLESS"}}}"""
        val raw = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + json)
            .toByteArray(Charsets.UTF_8)
        checkEq(200, clashStatus(raw), "код статуса из строки протокола")
        val body = decodeClashBody(raw) ?: error("тело не разобранось")
        checkEq(json, body, "юникод доживает до парсера")
        val target = ClashStats.probeTarget(body) ?: error("цель не найдена")
        checkEq("🇳🇱 Netherlands | [*CIDR]", target, "цель -- имя узла как на сервере")
        checkEq(
            "%F0%9F%87%B3%F0%9F%87%B1%20Netherlands%20%7C%20%5B%2ACIDR%5D",
            encodePath(target),
            "и кодируется обратно в те же байты"
        )

        // chunked: размер шестнадцатеричный, payload тоже UTF-8
        val size = json.toByteArray(Charsets.UTF_8).size
        val chunked = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "${size.toString(16)}\r\n$json\r\n0\r\n\r\n").toByteArray(Charsets.UTF_8)
        checkEq(json, decodeClashBody(chunked), "chunked с юникодом")

        checkEq(
            404,
            clashStatus(
                "HTTP/1.1 404 Not Found\r\n\r\n{\"message\":\"Resource not found\"}"
                    .toByteArray(Charsets.UTF_8)
            ),
            "отказ сервера различим по коду"
        )
        checkEq(null, decodeClashBody(ByteArray(0)), "пустой ответ")
    }

    out += DevTest("parsers/singbox-tor-socks", DevCategory.PARSERS, "Tor-конфиг смотрит в локальный SOCKS") {
        val built = SingboxConfig.buildTor(VpnConfig(), socksPort = 9050)
        val root = json.parseToJsonElement(built.json).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "socks" }
        checkEq("127.0.0.1", outbound["server"]!!.jsonPrimitive.content, "server")
        checkEq(9050, outbound["server_port"]!!.jsonPrimitive.content.toInt(), "port")
        checkEq("tor", outbound["tag"]!!.jsonPrimitive.content, "tag")
    }

    out += DevTest("parsers/singbox-tor-port-clamped", DevCategory.PARSERS, "SOCKS-порт зажат в 1..65535") {
        val built = SingboxConfig.buildTor(VpnConfig(), socksPort = 99999)
        val outbound = json.parseToJsonElement(built.json).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "socks" }
        checkEq(65535, outbound["server_port"]!!.jsonPrimitive.content.toInt(), "порт сверху")
    }
}
