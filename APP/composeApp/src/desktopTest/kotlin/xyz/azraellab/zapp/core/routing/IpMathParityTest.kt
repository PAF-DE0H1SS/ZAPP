package xyz.azraellab.zapp.core.routing

import xyz.azraellab.zapp.core.native.ZappNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Паритет IP/CIDR-математики: натив (C) против фолбэка на Kotlin.
 *
 * Натив -- основной путь, фолбэк -- страховка без библиотеки. Обе ветки
 * обязаны отдавать одинаковый результат на тех же краевых случаях,
 * что гоняет детектор маршрутизации.
 */
class IpMathParityTest {

    private val ipv4 = listOf(
        "10.1.2.3", "0.0.0.0", "255.255.255.255", "01.2.3.4",
        " 1.2.3.4 ", "256.1.1.1", "1.2.3", "1.2.3.4.5", "a.b.c.d",
        "1..2.3", "", "   ", "1.2.3.400", "1.2.3.4a", "-1.2.3.4",
        "1.2.3.-4", "1.2.3.٤"
    )

    private val ipv6 = listOf(
        "::1", "::", "2001:db8::1", "2001:0db8:0000:0000:0000:0000:0000:0001",
        "::ffff:1.2.3.4", "1:2:3:4:5:6:1.2.3.4", ":: 1.2.3.4",
        "2001::db8::1", "1:2:3:4:5:6:7::", "1:2:3:4:5:6:7::8",
        "gg::1", "12345::", "fe80::1%eth0", "", " ", "1::2::3"
    )

    private val literals = ipv4 + ipv6 + listOf(
        "example.com", "10.0.0.0/8", "localhost", "-H"
    )

    @Test
    fun `нативное ядро поднялось`() {
        assertEquals(true, ZappNative.available, "libzappjni.so не загрузилась")
    }

    @Test
    fun `parseIpv4 -- паритет с фолбэком`() {
        for (text in ipv4) {
            assertEquals(parseIpv4Fallback(text), parseIpv4(text), "расхождение: $text")
        }
    }

    @Test
    fun `parseIpv4 -- эталонные значения`() {
        assertEquals(0x0A010203u, parseIpv4("10.1.2.3"))
        assertEquals(0u, parseIpv4("0.0.0.0"))
        assertEquals(0xFFFFFFFFu, parseIpv4("255.255.255.255"))
        assertEquals(null, parseIpv4("256.1.1.1"))
    }

    @Test
    fun `parseIpv6 -- паритет с фолбэком`() {
        for (text in ipv6) {
            assertEquals(parseIpv6Fallback(text), parseIpv6(text), "расхождение: $text")
        }
    }

    @Test
    fun `parseIpv6 -- эталонные значения`() {
        assertEquals(List(7) { 0 } + listOf(1), parseIpv6("::1"))
        assertEquals(List(8) { 0 }, parseIpv6("::"))
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0xFFFF, 0x0102, 0x0304),
            parseIpv6("::ffff:1.2.3.4")
        )
        assertEquals(null, parseIpv6("2001::db8::1"))
    }

    @Test
    fun `isIpLiteral -- паритет с фолбэком`() {
        for (text in literals) {
            val want = parseIpv4Fallback(text) != null || parseIpv6Fallback(text) != null
            assertEquals(want, isIpLiteral(text), "расхождение: $text")
        }
    }

    @Test
    fun `cidrContains -- паритет с фолбэком`() {
        val cidrs = listOf(
            "10.0.0.0/8", "0.0.0.0/0", "1.2.3.4/32", "100.64.0.0/10",
            "192.168.1.0/24", "10.0.0.0/+8", "10.0.0.0/-0",
            "10.0.0.0/ 8", "10.0.0.0//8", "10.0.0.0/33", "10.0.0.0/999",
            "::/0", "fc00::/7", "2001:db8::/32", "::/128", "::/129",
            "не-подсеть", "/8", "10.0.0.0/"
        )
        val addresses = listOf(
            "10.1.2.3", "11.1.2.3", "1.2.3.4", "100.64.0.1", "100.127.255.255",
            "100.128.0.0", "255.255.255.255", "::1", "fd00::1", "fe80::1",
            "2001:db8::1", "мусор", ""
        )
        for (cidr in cidrs) {
            for (address in addresses) {
                assertEquals(
                    cidrContainsFallback(cidr, address),
                    cidrContains(cidr, address),
                    "расхождение: $cidr / $address"
                )
            }
        }
    }

    @Test
    fun `cidrContains -- эталонные значения`() {
        assertTrue(cidrContains("10.0.0.0/8", "10.1.2.3"))
        assertTrue(!cidrContains("10.0.0.0/8", "11.1.2.3"))
        assertTrue(cidrContains("0.0.0.0/0", "1.2.3.4"))
        assertTrue(cidrContains("fc00::/7", "fd00::1"))
        assertTrue(!cidrContains("fc00::/7", "fe00::1"))
        assertTrue(!cidrContains("10.0.0.0/8", "::1"), "смешанные семейства")
    }
}
