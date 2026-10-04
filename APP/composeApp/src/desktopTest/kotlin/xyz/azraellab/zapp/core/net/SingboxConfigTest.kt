package xyz.azraellab.zapp.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Собранный конфиг sing-box: автовыбор узла и DNS.
 *
 * Обе темы -- про скорость соединения: urltest держит минимальный пинг
 * между узлами, а detour DNS определяет, через туннель или мимо идёт
 * первый же запрос на каждый домен.
 */
class SingboxConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val ss1 = "ss://" + FlexibleBase64.encodeNoPadding("aes-256-gcm:cGFzcw") +
        "@1.2.3.4:8388#one"
    private val ss2 = "ss://" + FlexibleBase64.encodeNoPadding("aes-256-gcm:cGFzcw") +
        "@5.6.7.8:8388#two"

    private fun group(vpn: VpnConfig = VpnConfig()): SingboxConfig.Built =
        SingboxConfig.buildGroup(vpn, listOf(ss1, ss2))

    private fun outboundsOf(built: SingboxConfig.Built): List<JsonObject> =
        json.parseToJsonElement(built.json).jsonObject["outbounds"]!!
            .jsonArray.map { it.jsonObject }

    private fun outboundOfType(built: SingboxConfig.Built, type: String): JsonObject? =
        outboundsOf(built).firstOrNull { it["type"]?.jsonPrimitive?.content == type }

    // --- urltest: минимум пинга между узлами ---

    @Test
    fun groupHasUrltestSelectingAmongAllNodes() {
        val built = group()
        val urltest = outboundOfType(built, "urltest")
            ?: error("в групповом конфиге нет urltest")
        val nodes = urltest["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(2, nodes.size)
        val types = outboundsOf(built).map { it["type"]?.jsonPrimitive?.content }
        assertTrue("direct" in types, "прямой маршрут обязан остаться")
    }

    @Test
    fun urltestProbesOftenAndSwitchesOnlyForRealGain() {
        // Раз в минуту и tolerance 10 мс: часто перепроверяем, но
        // переключаемся только на заметно более быстрый узел -- шум
        // замера не должен дёргать живые соединения туда-сюда.
        val urltest = outboundOfType(group(), "urltest") ?: error("нет urltest")
        assertEquals("1m", urltest["interval"]!!.jsonPrimitive.content)
        assertEquals(10, urltest["tolerance"]!!.jsonPrimitive.int)
    }

    @Test
    fun urltestTakesExistingConnectionsAlongOnSwitch() {
        // Без этого флага «выбрал быстрее» действовало бы только для
        // новых соединений, а живые продолжали сидеть на старом пинге.
        val urltest = outboundOfType(group(), "urltest") ?: error("нет urltest")
        val flag = urltest["interrupt_exist_connections"]
        assertTrue(flag is JsonPrimitive && flag.content == "true")
    }

    // --- DNS: детур под вопросом пользователя ---

    private fun dnsServers(built: SingboxConfig.Built): JsonArray =
        json.parseToJsonElement(built.json).jsonObject["dns"]!!
            .jsonObject["servers"]!!.jsonArray

    private fun dnsServerByTag(built: SingboxConfig.Built, tag: String): JsonObject? =
        dnsServers(built).map { it.jsonObject }.firstOrNull {
            it["tag"]?.jsonPrimitive?.content == tag
        }

    private fun singleProfileDns(vpn: VpnConfig): SingboxConfig.Built =
        SingboxConfig.build(
            VpnProfile(protocol = VpnProtocol.SHADOWSOCKS.code, rawConfig = ss1),
            vpn
        )

    @Test
    fun fallbackDnsGoesThroughProxyWhenAsked() {
        // dnsViaProxy=true и явных серверов нет: фолбэк dns-0 обязан
        // идти через туннель, иначе резолв -- это и утечка запроса, и
        // лишний сетевой ход мимо прокси. Тег детура -- тег outbound'а
        // (имя ссылки), сверяется сам факт наличия.
        val built = singleProfileDns(VpnConfig(dnsViaProxy = true))
        val dns0 = dnsServerByTag(built, "dns-0") ?: error("нет dns-0")
        val detour = dns0["detour"]
        assertTrue(detour is JsonPrimitive && detour.content.isNotEmpty())
    }

    @Test
    fun fallbackDnsStaysDirectByDefault() {
        val built = singleProfileDns(VpnConfig(dnsViaProxy = false))
        val dns0 = dnsServerByTag(built, "dns-0") ?: error("нет dns-0")
        assertNull(dns0["detour"], "без dnsViaProxy детур не добавляется")
    }
}
