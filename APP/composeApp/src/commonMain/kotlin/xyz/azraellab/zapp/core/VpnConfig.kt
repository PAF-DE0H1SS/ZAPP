package xyz.azraellab.zapp.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Настройки VPN-профиля.
 *
 * Профиль описывает способ подключения и поведение при обрыве. Реализации
 * протоколов живут вне этой модели: [protocol] и [transport] только говорят
 * бэкенду, что именно построить.
 */
@Serializable
data class VpnProfile(
    val id: String = "",
    val name: String = "",
    val protocol: String = VpnProtocol.WIREGUARD.code,
    val transport: String = VpnTransport.UDP.code,

    // --- Адресация ---
    val gateway: String = "",
    val gatewayPort: Int = 51820,
    val address: String = "",
    val subnetCidr: String = "0.0.0.0/0",
    val dnsServers: String = "",

    // --- Аутентификация ---
    val privateKey: String = "",
    val publicKey: String = "",
    val presharedKey: String = "",
    /** Ссылка на файл сертификата или ключа. */
    val certPath: String = "",
    val certPassword: String = "",

    // --- Поведение ---
    val keepaliveSeconds: Int = 25,
    val mtu: Int = 1420,
    val autoReconnect: Boolean = true,
    val reconnectDelaySeconds: Int = 5,
    val maxReconnectAttempts: Int = 0,
    val ipv6: Boolean = false,
    val allowedIps: String = "0.0.0.0/0",
    val bypassLan: Boolean = true,
    val logging: Boolean = false
) {
    fun dnsList(): List<String> = dnsServers.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun allowedIpList(): List<String> = allowedIps.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Протоколы VPN. */
enum class VpnProtocol(val code: String, val ru: String, val en: String) {
    WIREGUARD("wireguard", "WireGuard", "WireGuard"),
    OPENVPN("openvpn", "OpenVPN", "OpenVPN"),
    SHADOWSOCKS("shadowsocks", "Shadowsocks", "Shadowsocks"),
    SOCKS5("socks5", "SOCKS5", "SOCKS5"),
    HTTP("http", "HTTP", "HTTP"),
    SSH("ssh", "SSH", "SSH");

    companion object {
        fun of(code: String?): VpnProtocol =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: WIREGUARD
    }
}

/** Транспорт VPN. */
enum class VpnTransport(val code: String, val ru: String, val en: String) {
    UDP("udp", "UDP", "UDP"),
    TCP("tcp", "TCP", "TCP"),
    WEBSOCKET("ws", "WebSocket", "WebSocket"),
    GRPC("grpc", "gRPC", "gRPC"),
    HTTP2("http2", "HTTP/2", "HTTP/2");

    companion object {
        fun of(code: String?): VpnTransport =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: UDP
    }
}

/**
 * Что делает включённый туннель с DNS и маршрутами.
 */
@Serializable
data class VpnRouting(
    /** Сколько раз кликать по значку туннеля, чтобы он поднялся. */
    val routesAllTraffic: Boolean = true,
    /** Не пускать в туннель локальные адреса и DHCP. */
    val excludePrivateRanges: Boolean = true,
    /** Не пускать в туннель multicast. */
    val excludeMulticast: Boolean = true,
    /** Перехватывать DNS, чтобы не было утечек. */
    val interceptDns: Boolean = true,
    /** Разрешить приложениям обходить туннель. */
    val allowPerAppBypass: Boolean = false,
    /** Приложения вне туннеля, через запятую. */
    val bypassApps: String = ""
) {
    fun bypassAppList(): List<String> = bypassApps.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Общие настройки VPN, не привязанные к отдельному профилю. */
@Serializable
data class VpnConfig(
    val enabled: Boolean = false,
    val autoStart: Boolean = false,
    val activeProfileId: String = "",
    val profiles: List<VpnProfile> = emptyList(),
    val routing: VpnRouting = VpnRouting(),
    val killSwitch: Boolean = false,
    val ipv6: Boolean = false,
    val dnsServers: String = "",
    @SerialName("splitTunneling") val splitTunneling: Boolean = false
) {
    fun dnsList(): List<String> = dnsServers.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun activeProfile(): VpnProfile? = profiles.firstOrNull { it.id == activeProfileId }

    fun profileById(id: String): VpnProfile? = profiles.firstOrNull { it.id == id }
}
