package xyz.azraellab.zapp.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор ссылок конфигов.
 *
 * Форматы не придуманы здесь: взяты ссылки, которые реально ходят между
 * приложениями, включая обе формы `ss://` и base64-подписки. Обратный
 * вывод тоже проверяется -- разобрать и не потерять мало, ссылка должна
 * пережить цикл `parse -> write -> parse`.
 */
class ProxyUriTest {

    @Test
    fun parsesVlessWithWebSocket() {
        val config = ProxyUri.parse(
            "vless://d34f89a1-0000-4000-8000-000000000001@example.com:443" +
                "?encryption=none&security=tls&type=ws&path=%2Fws&host=cdn.example.com" +
                "&sni=example.com#My%20Node"
        )
        assertNotNull(config)
        assertEquals(ProxyScheme.VLESS, config.scheme)
        assertEquals("example.com", config.server)
        assertEquals(443, config.port)
        assertEquals("d34f89a1-0000-4000-8000-000000000001", config.auth)
        assertEquals("ws", config.transport)
        assertEquals("tls", config.security)
        assertEquals("cdn.example.com", config.host)
        assertEquals("/ws", config.path)
        assertEquals("example.com", config.sni)
        assertEquals("My Node", config.name)
        assertTrue(config.isComplete)
    }

    @Test
    fun keepsRealityParametersForRoundTrip() {
        val link = "vless://d34f89a1-0000-4000-8000-000000000001@1.2.3.4:443" +
            "?security=reality&type=tcp&pbk=PUBLIC&sid=abc&fp=chrome&sni=www.example.com" +
            "#reality"
        val config = ProxyUri.parse(link)
        assertNotNull(config)
        assertEquals("reality", config.security)
        assertEquals("PUBLIC", config.params["pbk"])
        assertEquals("abc", config.params["sid"])

        val reparsed = ProxyUri.parse(ProxyUri.write(config))
        assertNotNull(reparsed)
        assertEquals("reality", reparsed.security)
        assertEquals("PUBLIC", reparsed.params["pbk"])
        assertEquals("abc", reparsed.params["sid"])
    }

    @Test
    fun trojanDefaultsToTls() {
        val config = ProxyUri.parse("trojan://secret@host.example:443?type=tcp#t")
        assertNotNull(config)
        assertEquals(ProxyScheme.TROJAN, config.scheme)
        assertEquals("secret", config.auth)
        assertEquals("tls", config.security)
    }

    @Test
    fun parsesBothShadowsocksForms() {
        // ss://base64(method:password)@host:port#name
        val withUserInfo = ProxyUri.parse(
            "ss://" + FlexibleBase64.encodeNoPadding("aes-256-gcm:pass word") +
                "@10.0.0.1:8388#ss-node"
        )
        assertNotNull(withUserInfo)
        assertEquals("aes-256-gcm", withUserInfo.cipher)
        assertEquals("pass word", withUserInfo.auth)
        assertEquals("10.0.0.1", withUserInfo.server)
        assertEquals(8388, withUserInfo.port)
        assertEquals("ss-node", withUserInfo.name)

        // ss://base64(method:password@host:port)#name -- старая форма
        val whole = ProxyUri.parse(
            "ss://" + FlexibleBase64.encodeNoPadding("chacha20-ietf-poly1305:pw@10.0.0.2:8388") + "#old"
        )
        assertNotNull(whole)
        assertEquals("chacha20-ietf-poly1305", whole.cipher)
        assertEquals("pw", whole.auth)
        assertEquals("10.0.0.2", whole.server)
        assertEquals(8388, whole.port)
    }

    @Test
    fun shadowsocksRoundTripKeepsCredentials() {
        val original = ProxyUri.parse(
            "ss://" + FlexibleBase64.encodeNoPadding("aes-128-gcm:secret") + "@1.1.1.1:443#n"
        )
        assertNotNull(original)
        val reparsed = ProxyUri.parse(ProxyUri.write(original))
        assertNotNull(reparsed)
        assertEquals(original.cipher, reparsed.cipher)
        assertEquals(original.auth, reparsed.auth)
        assertEquals(original.server, reparsed.server)
        assertEquals(original.port, reparsed.port)
        assertEquals(original.name, reparsed.name)
    }

    @Test
    fun parsesVmessFromBase64Json() {
        val json = """{"v":"2","ps":"vm","add":"2.2.2.2","port":"8443","id":"uuid-1",""" +
            """"aid":"0","net":"ws","host":"h.example.com","path":"/vmess","tls":"tls","sni":"h.example.com"}"""
        val config = ProxyUri.parse("vmess://" + FlexibleBase64.encodeNoPadding(json))
        assertNotNull(config)
        assertEquals(ProxyScheme.VMESS, config.scheme)
        assertEquals("vm", config.name)
        assertEquals("2.2.2.2", config.server)
        assertEquals(8443, config.port)
        assertEquals("uuid-1", config.auth)
        assertEquals("ws", config.transport)
        assertEquals("tls", config.security)
        assertEquals("/vmess", config.path)
        assertEquals("h.example.com", config.host)
        assertEquals("0", config.params["alterId"])
    }

    @Test
    fun vmessRoundTripKeepsServerAndName() {
        val json = """{"v":"2","ps":"vm","add":"2.2.2.2","port":"8443","id":"uuid-1",""" +
            """"aid":"16","net":"tcp","type":"none","tls":""}"""
        val original = ProxyUri.parse("vmess://" + FlexibleBase64.encodeNoPadding(json))
        assertNotNull(original)
        val reparsed = ProxyUri.parse(ProxyUri.write(original))
        assertNotNull(reparsed)
        assertEquals(original.server, reparsed.server)
        assertEquals(original.port, reparsed.port)
        assertEquals(original.auth, reparsed.auth)
        assertEquals(original.name, reparsed.name)
        assertEquals("16", reparsed.params["alterId"])
    }

    @Test
    fun acceptsHy2AliasForHysteria2() {
        val config = ProxyUri.parse("hy2://password@5.5.5.5:8443?sni=example.com&insecure=1#h")
        assertNotNull(config)
        assertEquals(ProxyScheme.HYSTERIA2, config.scheme)
        assertEquals("1", config.params["insecure"])
        assertEquals("example.com", config.sni)
        assertEquals("hysteria2", ProxyUri.write(config).substringBefore("://"))
    }

    @Test
    fun parseAllDeduplicatesAndKeepsOrder() {
        val first = "vless://d34f89a1-0000-4000-8000-000000000001@a.example:443#one"
        val second = "trojan://pw@b.example:443#two"
        val list = ProxyUri.parseAll("$first\n$second\n$first\n")
        assertEquals(2, list.size)
        assertEquals("one", list[0].name)
        assertEquals("two", list[1].name)
    }

    @Test
    fun parseAllReadsBase64Subscription() {
        val body = "vless://d34f89a1-0000-4000-8000-000000000001@a.example:443#one\n" +
            "trojan://pw@b.example:443#two\n"
        val list = ProxyUri.parseAll(FlexibleBase64.encodeNoPadding(body))
        assertEquals(2, list.size)
        assertEquals("one", list[0].name)
        assertEquals("two", list[1].name)
    }

    @Test
    fun parseAllReadsBase64EncodedLines() {
        val line = "vless://d34f89a1-0000-4000-8000-000000000001@a.example:443#one"
        val list = ProxyUri.parseAll(FlexibleBase64.encodeNoPadding(line))
        assertEquals(1, list.size)
        assertEquals("one", list[0].name)
    }

    @Test
    fun ignoresGarbageWithoutBreakingTheList() {
        val list = ProxyUri.parseAll(
            "not a link\n" +
                "vless://d34f89a1-0000-4000-8000-000000000001@a.example:443#one\n" +
                "\n" +
                "ftp://file.example/file"
        )
        assertEquals(1, list.size)
        assertEquals("one", list[0].name)
    }

    @Test
    fun percentDecodingKeepsPlusAsLiteral() {
        // '+' в пути -- это '+', а не пробел: URLDecoder здесь сломал бы ссылку.
        assertEquals("/a+b", ProxyUri.percentDecode("/a+b"))
        assertEquals("/a b", ProxyUri.percentDecode("/a%20b"))
        assertEquals("имя", ProxyUri.percentDecode("%D0%B8%D0%BC%D1%8F"))
    }

    @Test
    fun parsesIpv6Endpoint() {
        val config = ProxyUri.parse(
            "vless://d34f89a1-0000-4000-8000-000000000001@[2001:db8::1]:8443#v6"
        )
        assertNotNull(config)
        assertEquals("2001:db8::1", config.server)
        assertEquals(8443, config.port)
    }

    @Test
    fun rejectsUnknownSchemeAndGarbage() {
        assertNull(ProxyUri.parse("ftp://file.example/file"))
        assertNull(ProxyUri.parse("just some text"))
        assertNull(ProxyUri.parse("://broken"))
    }

    @Test
    fun displayNameFallsBackToAddress() {
        val config = ProxyUri.parse(
            "vless://d34f89a1-0000-4000-8000-000000000001@host.example:443"
        )
        assertNotNull(config)
        assertEquals("host.example:443", config.displayName())
    }
}
