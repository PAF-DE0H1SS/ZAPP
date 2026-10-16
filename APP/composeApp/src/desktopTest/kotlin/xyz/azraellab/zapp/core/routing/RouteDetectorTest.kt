package xyz.azraellab.zapp.core.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnRouting

/**
 * Детектор правила: порядок правил как в сборщике конфига,
 * доменные условия, подсети, порты и неоднозначные случаи.
 */
class RouteDetectorTest {

    private fun vpn(
        sections: List<RouteSection> = emptyList(),
        ipv6: Boolean = false,
        privateRanges: Boolean = true,
        multicast: Boolean = true
    ) = VpnConfig(
        ipv6 = ipv6,
        routing = VpnRouting(
            excludePrivateRanges = privateRanges,
            excludeMulticast = multicast,
            sections = sections
        )
    )

    private fun section(
        label: String = "s",
        action: SectionAction = SectionAction.DIRECT,
        domains: String = "",
        ipCidrs: String = "",
        ports: String = "",
        network: String = "any",
        enabled: Boolean = true,
        resolveRealIp: Boolean = false
    ) = RouteSection(
        label = label,
        enabled = enabled,
        action = action,
        domains = domains,
        ipCidrs = ipCidrs,
        ports = ports,
        network = network,
        resolveRealIp = resolveRealIp
    )

    /** Прогон с QUIC-обходом по умолчанию выключенным: тест не зависит от глобального флага. */
    private fun traceFor(
        config: VpnConfig,
        query: String,
        port: Int? = null,
        network: String = "any",
        quic: Boolean = false
    ) = RouteDetector.trace(config, query, port, network, quic)

    // --- IpMath ---

    @Test
    fun ipv4ParsesAndRejects() {
        assertEquals(0x0A010203u, parseIpv4("10.1.2.3"))
        assertNull(parseIpv4("256.1.1.1"))
        assertNull(parseIpv4("1.2.3"))
        assertNull(parseIpv4("1.2.3.4.5"))
        assertNull(parseIpv4("a.b.c.d"))
        assertNull(parseIpv4("1..2.3"))
    }

    @Test
    fun ipv6CompressionAndFull() {
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1), parseIpv6("::1"))
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0), parseIpv6("::"))
        val full = parseIpv6("2001:db8:0:0:0:0:0:1")
        assertEquals(full, parseIpv6("2001:db8::1"))
        assertEquals(0x2001, full?.get(0))
        assertNull(parseIpv6("2001::db8::1"))
        assertNull(parseIpv6("12345::"))
        assertNull(parseIpv6("gg::1"))
    }

    @Test
    fun ipv6V4Mapped() {
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0xFFFF, 0x0102, 0x0304),
            parseIpv6("::ffff:1.2.3.4")
        )
    }

    @Test
    fun cidrContainsV4() {
        assertTrue(cidrContains("10.0.0.0/8", "10.1.2.3"))
        assertFalse(cidrContains("10.0.0.0/8", "11.0.0.1"))
        assertTrue(cidrContains("203.0.113.5/32", "203.0.113.5"))
        assertFalse(cidrContains("203.0.113.5/32", "203.0.113.6"))
        assertTrue(cidrContains("0.0.0.0/0", "8.8.8.8"))
        assertFalse(cidrContains("10.0.0.0/8", "2001:db8::1"))
        assertFalse(cidrContains("10.0.0.0", "10.0.0.1"))
        assertFalse(cidrContains("10.0.0.0/99", "10.0.0.1"))
    }

    @Test
    fun cidrContainsV6() {
        assertTrue(cidrContains("2001:db8::/32", "2001:db8:1234::1"))
        assertFalse(cidrContains("2001:db8::/32", "2001:db9::1"))
        assertTrue(cidrContains("ff00::/8", "ff02::1"))
    }

    // --- Базовые правила ---

    @Test
    fun privateIpv4GoesDirect() {
        val result = traceFor(vpn(), "192.168.1.1")
        assertEquals(TraceAction.DIRECT, result.action)
        assertEquals(RouteDetector.RULE_PRIVATE, result.by)
    }

    @Test
    fun privateCgnatGoesDirect() {
        val result = traceFor(vpn(), "100.64.0.1")
        assertEquals(RouteDetector.RULE_PRIVATE, result.by)
    }

    @Test
    fun privateDomainIsNotAssumed() {
        val result = traceFor(vpn(), "router.lan")
        assertEquals(TraceAction.TUNNEL, result.action)
        assertEquals(RouteDetector.RULE_DEFAULT, result.by)
    }

    @Test
    fun multicastGoesDirect() {
        val result = traceFor(vpn(), "239.1.2.3")
        assertEquals(TraceAction.DIRECT, result.action)
        assertEquals(RouteDetector.RULE_MULTICAST, result.by)
    }

    @Test
    fun ipv6BlockedWhenDisabled() {
        val result = traceFor(vpn(ipv6 = false), "2001:db8::1")
        assertEquals(TraceAction.BLOCK, result.action)
        assertEquals(RouteDetector.RULE_V6, result.by)
    }

    @Test
    fun ipv6PassesWhenEnabled() {
        val result = traceFor(vpn(ipv6 = true), "2001:db8::1")
        assertEquals(RouteDetector.RULE_DEFAULT, result.by)
    }

    @Test
    fun baseRulesBeatSections() {
        // Частные сети стоят выше секций и в конфиге -- здесь так же.
        val sections = listOf(section(label = "lan-block", action = SectionAction.BLOCK, ipCidrs = "10.0.0.0/8"))
        val result = traceFor(vpn(sections = sections), "10.1.2.3")
        assertEquals(RouteDetector.RULE_PRIVATE, result.by)
        assertEquals(TraceAction.DIRECT, result.action)
    }

    // --- Секции: доменные условия ---

    @Test
    fun sectionSuffixWins() {
        val sections = listOf(section(label = "video", domains = "youtube.com"))
        val result = traceFor(vpn(sections = sections), "www.youtube.com")
        assertEquals("video", result.by)
        assertEquals(TraceAction.DIRECT, result.action)
        assertTrue(result.reason.contains("domain_suffix: youtube.com"))
    }

    @Test
    fun sectionSuffixRespectsDotBoundary() {
        val sections = listOf(section(label = "video", domains = "youtube.com"))
        val hit = traceFor(vpn(sections = sections), "notyoutube.com")
        assertEquals(RouteDetector.RULE_DEFAULT, hit.by)
        val sub = traceFor(vpn(sections = sections), "m.youtube.com")
        assertEquals("video", sub.by)
    }

    @Test
    fun sectionExactDomain() {
        val sections = listOf(section(label = "mail", domains = "full:mail.ru"))
        assertEquals("mail", traceFor(vpn(sections = sections), "mail.ru").by)
        assertEquals(
            RouteDetector.RULE_DEFAULT,
            traceFor(vpn(sections = sections), "hotmail.ru").by
        )
    }

    @Test
    fun sectionKeyword() {
        val sections = listOf(section(label = "goog", domains = "keyword:google"))
        assertEquals("goog", traceFor(vpn(sections = sections), "www.google.com").by)
    }

    @Test
    fun sectionRegex() {
        val sections = listOf(section(label = "cdn", domains = "regex:^cdn-[0-9]+\\.example\\.com$"))
        assertEquals("cdn", traceFor(vpn(sections = sections), "cdn-42.example.com").by)
        assertEquals(
            RouteDetector.RULE_DEFAULT,
            traceFor(vpn(sections = sections), "cdn.example.com").by
        )
    }

    @Test
    fun firstMatchingSectionWins() {
        val sections = listOf(
            section(label = "first", domains = "one.com"),
            section(label = "second", domains = "keyword:one")
        )
        val result = traceFor(vpn(sections = sections), "www.one.com")
        assertEquals("first", result.by)
    }

    @Test
    fun disabledSectionSkipped() {
        val sections = listOf(section(label = "off", domains = "example.com", enabled = false))
        assertEquals(
            RouteDetector.RULE_DEFAULT,
            traceFor(vpn(sections = sections), "example.com").by
        )
    }

    @Test
    fun dnsSectionSkipped() {
        val sections = listOf(
            section(label = "dns", action = SectionAction.DNS, domains = "example.com")
        )
        val result = traceFor(vpn(sections = sections), "example.com")
        assertEquals(RouteDetector.RULE_DEFAULT, result.by)
        assertTrue(result.steps.none { it.rule == "dns" })
    }

    @Test
    fun sectionWithoutMatchersCatchesAll() {
        val sections = listOf(section(label = "all"))
        assertEquals("all", traceFor(vpn(sections = sections), "anything.test").by)
    }

    // --- Секции: подсети, порты, сеть ---

    @Test
    fun sectionCidr() {
        val sections = listOf(section(label = "testnet", ipCidrs = "203.0.113.0/24"))
        assertEquals("testnet", traceFor(vpn(sections = sections), "203.0.113.5").by)
        assertEquals(
            RouteDetector.RULE_DEFAULT,
            traceFor(vpn(sections = sections), "203.0.114.5").by
        )
    }

    @Test
    fun sectionDomainDoesNotMatchIpLiteral() {
        val sections = listOf(section(label = "web", domains = "example.com"))
        assertEquals(
            RouteDetector.RULE_DEFAULT,
            traceFor(vpn(sections = sections), "93.184.216.34").by
        )
    }

    @Test
    fun resolveRealIpIsMaybe() {
        val sections = listOf(section(label = "rr", domains = "example.com", ipCidrs = "8.8.8.0/24", resolveRealIp = true))
        val result = traceFor(vpn(sections = sections), "example.com")
        assertEquals(RouteDetector.RULE_DEFAULT, result.by)
        assertTrue(result.reason.contains("rr"))
        val step = result.steps.first { it.rule == "rr" }
        assertFalse(step.verified)
    }

    @Test
    fun sectionPortNeedsPortData() {
        val sections = listOf(section(label = "web", domains = "example.com", ports = "443"))
        val unknown = traceFor(vpn(sections = sections), "example.com")
        assertEquals(RouteDetector.RULE_DEFAULT, unknown.by)
        assertTrue(unknown.reason.contains("web"))

        val hit = traceFor(vpn(sections = sections), "example.com", port = 443)
        assertEquals("web", hit.by)

        val miss = traceFor(vpn(sections = sections), "example.com", port = 80)
        assertEquals(RouteDetector.RULE_DEFAULT, miss.by)
    }

    @Test
    fun sectionPortRange() {
        val sections = listOf(section(label = "game", ports = "8000-8999"))
        assertEquals(
            "game",
            traceFor(vpn(sections = sections), "1.2.3.4", port = 8443).by
        )
        assertEquals(
            RouteDetector.RULE_DEFAULT,
            traceFor(vpn(sections = sections), "1.2.3.4", port = 9000).by
        )
    }

    @Test
    fun sectionNetwork() {
        val sections = listOf(section(label = "quic-in", network = "udp"))
        assertEquals(
            "quic-in",
            traceFor(vpn(sections = sections), "1.2.3.4", network = "udp").by
        )
        assertEquals(
            RouteDetector.RULE_DEFAULT,
            traceFor(vpn(sections = sections), "1.2.3.4", network = "tcp").by
        )
        val anyNet = traceFor(vpn(sections = sections), "1.2.3.4")
        assertEquals(RouteDetector.RULE_DEFAULT, anyNet.by)
        assertTrue(anyNet.reason.contains("quic-in"))
    }

    @Test
    fun sectionBlockAction() {
        val sections = listOf(section(label = "ads", action = SectionAction.BLOCK, domains = "ads.example.com"))
        val result = traceFor(vpn(sections = sections), "banners.ads.example.com")
        assertEquals(TraceAction.BLOCK, result.action)
        assertEquals("ads", result.by)
    }

    // --- QUIC-обход и пустой ввод ---

    @Test
    fun quicBlockRejectsUdp() {
        val result = traceFor(vpn(), "example.com", network = "udp", quic = true)
        assertEquals(TraceAction.BLOCK, result.action)
        assertEquals(RouteDetector.RULE_QUIC, result.by)
    }

    @Test
    fun quicBlockWithAnyNetworkIsMaybe() {
        val result = traceFor(vpn(), "example.com", quic = true)
        assertEquals(RouteDetector.RULE_DEFAULT, result.by)
        assertTrue(result.reason.contains(RouteDetector.RULE_QUIC))
    }

    @Test
    fun quicOffAddsNoStep() {
        val result = traceFor(vpn(), "example.com", network = "udp", quic = false)
        assertTrue(result.steps.none { it.rule == RouteDetector.RULE_QUIC })
    }

    @Test
    fun emptyQueryIsDefault() {
        val result = traceFor(vpn(), "   ")
        assertEquals(TraceAction.TUNNEL, result.action)
        assertEquals(RouteDetector.RULE_DEFAULT, result.by)
        assertEquals("", result.reason)
        assertTrue(result.steps.isNotEmpty())
    }
}
