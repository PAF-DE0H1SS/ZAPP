package xyz.azraellab.zapp.core.net

import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol

/**
 * Куда стучаться, чтобы проверить один коннект.
 *
 * Разные протоколы хранят адрес по-разному: wg -- в `Endpoint`, ссылки --
 * в authority URI, sing-box/xray -- в JSON-полях, мосты Tor -- в текстовой
 * строке. Проверка живости не должна об этом знать: она получает готовые
 * хост, порт и тип проб, а откуда их взять -- решает эта функция.
 */
data class LinkTarget(
    val host: String,
    val port: Int,
    val kind: ProbeKind
)

/**
 * Цель пробы для профиля; null -- проверять нечего (Tor без адресов,
 * ссылка без разбора, профиль без шлюза).
 */
fun probeTarget(profile: VpnProfile): LinkTarget? {
    val protocol = VpnProtocol.of(profile.protocol)

    when (protocol) {
        VpnProtocol.TOR -> return bridgeTarget(profile.rawConfig)

        VpnProtocol.WIREGUARD, VpnProtocol.AMNEZIA_WG -> {
            if (profile.gateway.isNotBlank() && profile.gatewayPort in 1..65535) {
                return LinkTarget(profile.gateway, profile.gatewayPort, ProbeKind.UDP)
            }
            return null
        }

        VpnProtocol.OPENVPN -> {
            val remote = Regex("(?m)^\\s*remote\\s+(\\S+)(?:\\s+(\\d+))?")
                .find(profile.rawConfig) ?: return gatewayFallback(profile)
            val host = remote.groupValues[1]
            val port = remote.groupValues[2].toIntOrNull() ?: 1194
            val udp = Regex("(?m)^\\s*proto\\s+udp").containsMatchIn(profile.rawConfig)
            return LinkTarget(host, port, if (udp) ProbeKind.UDP else ProbeKind.TCP)
        }

        VpnProtocol.SINGBOX, VpnProtocol.XRAY -> return jsonTarget(profile.rawConfig)

        else -> Unit
    }

    // Ссылка прокси: authority уже распарсен, схема говорит про TCP/UDP.
    val proxy = ProxyUri.parse(profile.rawConfig)
    if (proxy != null && proxy.server.isNotBlank() && proxy.port in 1..65535) {
        val udp = proxy.scheme == ProxyScheme.HYSTERIA2 || proxy.scheme == ProxyScheme.TUIC
        return LinkTarget(proxy.server, proxy.port, if (udp) ProbeKind.UDP else ProbeKind.TCP)
    }

    return gatewayFallback(profile)
}

/** Шлюз профиля как последний шанс; пустой шлюз -- проверки нет. */
private fun gatewayFallback(profile: VpnProfile): LinkTarget? {
    if (profile.gateway.isBlank()) return null
    val port = if (profile.gatewayPort in 1..65535) profile.gatewayPort else 443
    return LinkTarget(profile.gateway, port, ProbeKind.TCP)
}

/** Первый адрес из текста мостов: `obfs4 1.2.3.4:443 cert=...`. */
private fun bridgeTarget(bridges: String): LinkTarget? {
    val match = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})").find(bridges) ?: return null
    val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return LinkTarget(match.groupValues[1], port, ProbeKind.TCP)
}

/**
 * Адрес из JSON-конфига sing-box/xray: `server` + `server_port`.
 * QUIC-протоколы (hy2/tuic) в JSON ловим по полю type/protocol.
 */
private fun jsonTarget(raw: String): LinkTarget? {
    val host = Regex("\"server\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        ?: return null
    val port = Regex("\"server_port\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        ?: return null
    if (port !in 1..65535) return null
    val udp = Regex("\"type\"\\s*:\\s*\"(hysteria2|tuic|wireguard|amnezia)\"").containsMatchIn(raw) ||
        Regex("\"protocol\"\\s*:\\s*\"(hysteria2|tuic|wireguard)\"").containsMatchIn(raw)
    return LinkTarget(host, port, if (udp) ProbeKind.UDP else ProbeKind.TCP)
}
