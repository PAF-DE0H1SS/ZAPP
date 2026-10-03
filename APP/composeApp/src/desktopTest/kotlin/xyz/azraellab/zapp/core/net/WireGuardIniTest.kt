package xyz.azraellab.zapp.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Разбор и сборка конфига WireGuard.
 *
 * Главная проверка -- обратимость: файл, прошедший через приложение,
 * должен читаться теми же программами, что и исходный.
 */
class WireGuardIniTest {

    private val sample = """
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

    @Test
    fun parsesFullConfig() {
        val profile = assertNotNull(WireGuardIni.parse(sample, name = "home"))
        assertEquals("home", profile.name)
        assertEquals("PRIVATE_KEY_VALUE", profile.privateKey)
        assertEquals("PUBLIC_KEY_VALUE", profile.publicKey)
        assertEquals("PSK_VALUE", profile.presharedKey)
        assertEquals("10.0.0.2/32, fd00::2/128", profile.address)
        assertEquals("1.1.1.1, 9.9.9.9", profile.dnsServers)
        assertEquals(1280, profile.mtu)
        assertEquals("0.0.0.0/0, ::/0", profile.allowedIps)
        assertEquals("wg.example.com", profile.gateway)
        assertEquals(51820, profile.gatewayPort)
        assertEquals(25, profile.keepaliveSeconds)
    }

    @Test
    fun rejectsConfigWithoutPeer() {
        assertNull(WireGuardIni.parse("[Interface]\nPrivateKey = x"))
        assertNull(WireGuardIni.parse("random text"))
    }

    @Test
    fun takesFirstPeerWhenThereAreSeveral() {
        val text = """
            [Interface]
            PrivateKey = k1

            [Peer]
            PublicKey = FIRST

            [Peer]
            PublicKey = SECOND
        """.trimIndent()
        val profile = assertNotNull(WireGuardIni.parse(text))
        assertEquals("FIRST", profile.publicKey)
    }

    @Test
    fun parsesIpv6Endpoint() {
        val text = """
            [Interface]
            PrivateKey = k
            [Peer]
            PublicKey = p
            Endpoint = [2001:db8::1]:51821
        """.trimIndent()
        val profile = assertNotNull(WireGuardIni.parse(text))
        assertEquals("2001:db8::1", profile.gateway)
        assertEquals(51821, profile.gatewayPort)
    }

    @Test
    fun roundTripKeepsAllFields() {
        val original = assertNotNull(WireGuardIni.parse(sample, name = "home"))
        val reparsed = assertNotNull(WireGuardIni.parse(WireGuardIni.write(original), name = original.name))

        assertEquals(original.privateKey, reparsed.privateKey)
        assertEquals(original.publicKey, reparsed.publicKey)
        assertEquals(original.presharedKey, reparsed.presharedKey)
        assertEquals(original.address, reparsed.address)
        assertEquals(original.dnsServers, reparsed.dnsServers)
        assertEquals(original.mtu, reparsed.mtu)
        assertEquals(original.allowedIps, reparsed.allowedIps)
        assertEquals(original.gateway, reparsed.gateway)
        assertEquals(original.gatewayPort, reparsed.gatewayPort)
        assertEquals(original.keepaliveSeconds, reparsed.keepaliveSeconds)
    }

    @Test
    fun roundTripKeepsIpv6Endpoint() {
        val original = assertNotNull(
            WireGuardIni.parse("[Interface]\nPrivateKey = k\n[Peer]\nPublicKey = p\nEndpoint = [2001:db8::1]:51821")
        )
        val reparsed = assertNotNull(WireGuardIni.parse(WireGuardIni.write(original)))
        assertEquals(original.gateway, reparsed.gateway)
        assertEquals(original.gatewayPort, reparsed.gatewayPort)
    }

    @Test
    fun toleratesWindowsLineEndingsAndCommentStyles() {
        val text = "[Interface]\r\nPrivateKey = k1\r\n; comment\r\n[Peer]\r\nPublicKey = p1\r\n"
        val profile = assertNotNull(WireGuardIni.parse(text))
        assertEquals("k1", profile.privateKey)
        assertEquals("p1", profile.publicKey)
    }
}
