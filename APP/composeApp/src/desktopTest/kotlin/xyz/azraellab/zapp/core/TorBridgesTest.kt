package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разбор bridge-строк: вкладка Tor показывает сами мосты, а не профили,
 * поэтому каждая строка из конфига обязана превратиться в адрес+порт.
 *
 * Парсер -- чистая функция, экран её не тестирует: здесь ловятся
 * форматы, которые реально встречаются в подписках (vanilla, obfs4,
 * webtunnel с IPv6 и url=, мусорные строки).
 */
class TorBridgesTest {

    @Test
    fun vanillaLineWithoutTransportAndPrefix() {
        val bridges = TorBridges.parse("1.2.3.4:9001 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertEquals(1, bridges.size)
        assertEquals(TorBridge("", "1.2.3.4", 9001), bridges[0])
        assertEquals("1.2.3.4:9001", bridges[0].endpoint)
    }

    @Test
    fun bridgePrefixAndObfs4Transport() {
        val bridges = TorBridges.parse(
            "Bridge obfs4 5.6.7.8:443 FINGERPRINT cert=abc iat-mode=0"
        )
        assertEquals(1, bridges.size)
        assertEquals("obfs4", bridges[0].transport)
        assertEquals("5.6.7.8", bridges[0].host)
        assertEquals(443, bridges[0].port)
    }

    @Test
    fun webtunnelWithIpv6BracketEndpoint() {
        val bridges = TorBridges.parse(
            "webtunnel [2001:db8:c934:3cba:7cd2:7e56:c8c9:40a6]:443 " +
                "412F21AAD09FA4F83AD52186458466BEC5D27795 url=https://cdn.example.org/xyz ver=0"
        )
        assertEquals(1, bridges.size)
        assertEquals("webtunnel", bridges[0].transport)
        assertEquals("2001:db8:c934:3cba:7cd2:7e56:c8c9:40a6", bridges[0].host)
        assertEquals(443, bridges[0].port)
        // IPv6 в torrc -- со скобками, строка в списке должна совпадать.
        assertEquals("[2001:db8:c934:3cba:7cd2:7e56:c8c9:40a6]:443", bridges[0].endpoint)
    }

    @Test
    fun commentsAndBlankLinesAreSkipped() {
        val bridges = TorBridges.parse(
            """
            # комментарий
            Bridge 9.9.9.9:9001 ABCDEF0123456789

            """.trimIndent()
        )
        assertEquals(1, bridges.size)
        assertEquals("9.9.9.9", bridges[0].host)
    }

    @Test
    fun urlInsteadOfAddressIsSkipped() {
        // URL -- не адрес для списка: показать его как мост нельзя.
        val bridges = TorBridges.parse("Bridge https://example.org:443/path ABCDEF0123456789")
        assertTrue(bridges.isEmpty())
    }

    @Test
    fun outOfRangePortIsSkipped() {
        val bridges = TorBridges.parse("1.2.3.4:70000 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(bridges.isEmpty())
    }

    @Test
    fun realWorldTop100Shape() {
        val raw = """
            185.220.101.1:443 6BAA1B6BFD4D1A0E4C7E0D0B1C2D3E4F5A6B7C8D
            obfs4 104.244.72.128:443 B0F3C4E5A6D7F8091A2B3C4D5E6F708192A3B4C5 cert=aaa iat-mode=0
            snowflake 192.0.2.1:80
        """.trimIndent()
        val bridges = TorBridges.parse(raw)
        assertEquals(3, bridges.size)
        assertEquals("", bridges[0].transport)
        assertEquals("obfs4", bridges[1].transport)
        assertEquals("snowflake", bridges[2].transport)
        assertEquals("192.0.2.1:80", bridges[2].endpoint)
    }
}
