package xyz.azraellab.zapp.core.warp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WARP: сборка запроса, разбор ответа, хост:порт, превращение в профиль. */
class WarpTest {

    private val responseJson = """
        {
          "id": "dev-1",
          "account": {"id": "acc-1", "license": "LIC-KEY"},
          "config": {
            "client_id": "CLIENT",
            "interface": {
              "addresses": {
                "v4": "172.16.0.2/32",
                "v6": "2606:4700:110::1/128"
              }
            },
            "peers": [
              {
                "public_key": "PEERPUB",
                "endpoint": {"host": "engage.cloudflareclient.com", "v4": "162.159.192.1:2408"}
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun requestCarriesKeyAndSerial() {
        val body = buildRegisterRequest("PUBKEY", "SERIAL-1")
        assertTrue(body.contains("\"key\": \"PUBKEY\""))
        assertTrue(body.contains("\"serial_number\": \"SERIAL-1\""))
    }

    @Test
    fun parsesRegistrationResponse() {
        val account = parseRegisterResponse(responseJson, "PRIVKEY")
        assertNotNull(account)
        assertEquals("dev-1", account.id)
        assertEquals("LIC-KEY", account.license)
        assertEquals("PRIVKEY", account.privateKey)
        assertEquals("PEERPUB", account.peerPublicKey)
        assertEquals("162.159.192.1", account.endpointHost)
        assertEquals(2408, account.endpointPort)
        assertEquals("172.16.0.2/32", account.ipv4)
        assertEquals("2606:4700:110::1/128", account.ipv6)
    }

    @Test
    fun garbageRejected() {
        assertNull(parseRegisterResponse("<html>blocked</html>", "k"))
    }

    @Test
    fun missingPeerRejected() {
        val json = """{"id":"d","config":{"interface":{"addresses":{"v4":"1.2.3.4/32"}}}}"""
        assertNull(parseRegisterResponse(json, "k"))
    }

    @Test
    fun missingEndpointHostPortRejected() {
        val json = """{"id":"d","config":{"peers":[{"public_key":"P","endpoint":{"host":"x"}}]}}"""
        assertNull(parseRegisterResponse(json, "k"))
    }

    @Test
    fun splitsHostPort() {
        assertEquals("1.2.3.4" to 2408, splitHostPort("1.2.3.4:2408"))
        assertEquals("[::1]" to 51820, splitHostPort("[::1]:51820"))
    }

    @Test
    fun missingPortRejected() {
        assertNull(splitHostPort("1.2.3.4"))
        assertNull(splitHostPort("1.2.3.4:"))
        assertNull(splitHostPort(":2408"))
    }

    @Test
    fun accountBecomesWireGuardProfile() {
        val profile = parseRegisterResponse(responseJson, "PRIVKEY")!!.toProfile("WARP")
        assertEquals("WARP", profile.name)
        assertEquals("162.159.192.1", profile.gateway)
        assertEquals(2408, profile.gatewayPort)
        assertEquals("172.16.0.2/32", profile.address)
        assertEquals("PRIVKEY", profile.privateKey)
        assertEquals("PEERPUB", profile.publicKey)
        assertEquals("0.0.0.0/0", profile.allowedIps)
        assertEquals(1280, profile.mtu)
    }
}
