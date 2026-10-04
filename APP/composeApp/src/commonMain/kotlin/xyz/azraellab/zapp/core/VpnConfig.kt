package xyz.azraellab.zapp.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Настройки VPN-профиля.
 *
 * Профиль описывает способ подключения и поведение при обрыве. Реализации
 * протоколов живут вне этой модели: [protocol] и [transport] только говорят
 * бэкенду, что именно построить.
 *
 * [rawConfig] -- исходный текст, из которого профиль собран: wg-ini,
 * `.ovpn`, ссылка или sing-box JSON. Он хранится целиком, а не по частям,
 * потому что разбор обратно в «точно тот же файл» всегда точнее любого
 * набора полей: экспорт, переимпорт и сравнение версий не теряют ничего.
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
    val logging: Boolean = false,

    // --- Исходные данные ---
    /** Текст конфига: wg-ini, ovpn, ссылка, JSON. Пусто у собранного вручную профиля. */
    val rawConfig: String = "",
    /**
     * Незнакомые ключи `[Interface]` WireGuard/Amnezia: `jc`, `jmin`, `s1`...
     *
     * Хранятся отдельно от основных полей, потому что для ядра это
     * просто ключи ini, а для sing-box -- типизированные поля endpoint'а,
     * и обе стороны описывают одно и то же, но по-разному.
     */
    val extras: Map<String, String> = emptyMap(),
    /** Адрес подписки; непустой -- профиль живёт обновлением списка узлов. */
    val subscriptionUrl: String = "",
    /** Как часто обновлять подписку; 0 -- только вручную. */
    val refreshSeconds: Int = 0,

    // --- Происхождение и работоспособность ---
    /** Источник (GitHub-репо); пусто -- добавлен вручную. */
    val sourceId: String = "",
    /** Итог последней проверки: жив/мертв, задержка, время проверки. */
    val health: LinkHealth = LinkHealth()
) {
    fun dnsList(): List<String> = dnsServers.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun allowedIpList(): List<String> = allowedIps.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Признак профиля-подписки: список узлов приходит по ссылке. */
    val isSubscription: Boolean get() = subscriptionUrl.isNotBlank()
}

/** Протоколы VPN. */
enum class VpnProtocol(val code: String, val ru: String, val en: String) {
    WIREGUARD("wireguard", "WireGuard", "WireGuard"),
    AMNEZIA_WG("amneziawg", "Amnezia WG", "Amnezia WG"),
    OPENVPN("openvpn", "OpenVPN", "OpenVPN"),
    VLESS("vless", "VLESS", "VLESS"),
    VMESS("vmess", "VMess", "VMess"),
    TROJAN("trojan", "Trojan", "Trojan"),
    SHADOWSOCKS("shadowsocks", "Shadowsocks", "Shadowsocks"),
    HYSTERIA2("hysteria2", "Hysteria 2", "Hysteria 2"),
    TUIC("tuic", "TUIC", "TUIC"),
    SINGBOX("singbox", "Sing-box", "Sing-box"),
    XRAY("xray", "Xray", "Xray"),
    TOR("tor", "Tor", "Tor"),
    SOCKS5("socks5", "SOCKS5", "SOCKS5"),
    HTTP("http", "HTTP", "HTTP"),
    SSH("ssh", "SSH", "SSH");

    /** Ключ строки в UI. */
    val strKey: Str
        get() = when (this) {
            WIREGUARD -> Str.PROTO_WIREGUARD
            AMNEZIA_WG -> Str.PROTO_AMNEZIA_WG
            OPENVPN -> Str.PROTO_OPENVPN
            VLESS -> Str.PROTO_VLESS
            VMESS -> Str.PROTO_VMESS
            TROJAN -> Str.PROTO_TROJAN
            SHADOWSOCKS -> Str.PROTO_SHADOWSOCKS
            HYSTERIA2 -> Str.PROTO_HYSTERIA2
            TUIC -> Str.PROTO_TUIC
            SINGBOX -> Str.PROTO_SINGBOX
            XRAY -> Str.PROTO_XRAY
            TOR -> Str.PROTO_TOR
            SOCKS5 -> Str.PROTO_SOCKS5
            HTTP -> Str.PROTO_HTTP
            SSH -> Str.PROTO_SSH
        }

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
    /** Весь трафик через туннель, а не только указанные сети. */
    val routesAllTraffic: Boolean = true,
    /** Не пускать в туннель локальные адреса и DHCP. */
    val excludePrivateRanges: Boolean = true,
    /** Не пускать в туннель multicast. */
    val excludeMulticast: Boolean = true,
    /**
     * Перехватывать DNS, чтобы не было утечек.
     *
     * Работает у бэкендов, которые сами владеют резолвом (sing-box), и
     * честно не работает в ядерном WireGuard: там системный резолвер
     * остаётся системным, и движок говорит об этом в журнале строкой, а
     * не делает вид, что правило применилось.
     */
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
    @SerialName("splitTunneling") val splitTunneling: Boolean = false,

    // --- DNS-политика для бэкендов, владеющих резолвом (sing-box) ---
    /** Отдавать клиентам fake-ip и раскрывать домен по SNI/снифферу. */
    val dnsFakeIp: Boolean = false,
    /** Резолвить через прокси, а не напрямую. */
    val dnsViaProxy: Boolean = false,
    /** Запасной резолвер, когда основной не отвечает. */
    val dnsBackup: String = "",

    // --- Мониторинг (clash-api у sing-box) ---
    val clashApiEnabled: Boolean = false,
    val clashApiPort: Int = 9090,
    val clashApiSecret: String = "",

    // --- Источники коннектов (GitHub-репо и свои ссылки) ---
    val sources: List<VpnSource> = defaultSources(),

    // --- Tor ---
    /**
     * Исторический флаг «показывать мосты в общем списке VPN».
     *
     * Мосты теперь живут отдельной вкладкой Tor и в списки VPN не
     * попадают никогда, поэтому флаг ничего не решает; поле оставлено,
     * чтобы старые конфиги читались без ошибки десериализации.
     */
    val torEnabled: Boolean = true,
    /** Локальный SOCKS-порт tor'а; через него box и пускает трафик. */
    val torSocksPort: Int = 9050,

    /**
     * Групповое подключение: urltest по живым коннектам вместо одного узла.
     *
     * Отдельный флаг, а не виртуальный профиль: выбор «один коннект» и
     * «автоподбор» -- режим работы движка, а не элемент списка, и
     * путаница между ними стоит лишнего подключения.
     */
    val groupMode: Boolean = false
) {
    fun dnsList(): List<String> = dnsServers.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun activeProfile(): VpnProfile? = profiles.firstOrNull { it.id == activeProfileId }

    fun profileById(id: String): VpnProfile? = profiles.firstOrNull { it.id == id }

    /**
     * Коннекты VPN-страницы: живые и ещё не проверенные.
     *
     * Мосты Tor сюда не входят никогда: VPN -- только VPN, тор живёт на
     * своей вкладке со своими списками. Раньше решал флаг [torEnabled],
     * теперь разделение жёсткое.
     */
    fun visibleProfiles(): List<VpnProfile> = profiles.filter {
        it.health.alive != false && it.protocol != VpnProtocol.TOR.code
    }

    /**
     * Архив VPN: коннекты, признанные мёртвыми последней проверкой.
     * Мёртвые мосты остаются на вкладке Tor -- в архив VPN не идут.
     */
    fun archivedProfiles(): List<VpnProfile> = profiles.filter {
        it.health.alive == false && it.protocol != VpnProtocol.TOR.code
    }

    /** Коннекты активного источника. */
    fun profilesOfSource(sourceId: String): List<VpnProfile> =
        profiles.filter { it.sourceId == sourceId }

    companion object {
        /**
         * Источники по умолчанию: репозитории, которые сами тестируют и
         * обновляют коннекты каждые 2-4 часа.
         *
         * Первый URL -- основной, остальные -- зеркала на случай
         * блокировок (GitHub RAW, GitLab-зеркало, Gitea): порядок в
         * списке -- порядок попыток. Формат всех -- обычный список
         * ссылок, который разбирает [xyz.azraellab.zapp.core.net.ProxyUri].
         */
        fun defaultSources(): List<VpnSource> {
            fun igareck(file: String, name: String, kind: String = "proxy", top: Boolean = false) =
                VpnSource(
                    id = "igareck:$file",
                    name = name,
                    url = "https://raw.githack.com/igareck/vpn-configs-for-russia/main/$file",
                    mirrors = listOf(
                        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/$file",
                        "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/$file"
                    ),
                    kind = kind,
                    top = top
                )

            return listOf(
                igareck(
                    "BLACK_VLESS_RUS_mobile.txt",
                    "Black list: top-150 (mix)",
                    top = true
                ),
                igareck("BLACK_SS+All_RUS.txt", "Shadowsocks + all"),
                igareck("BLACK_VLESS_RUS.txt", "VLESS"),
                igareck("BLACK_SS_WEAK_DPI_RUS.txt", "Shadowsocks (weak DPI)"),
                igareck(
                    "Vless-Reality-White-Lists-Rus-Mobile.txt",
                    "White list: VLESS Reality (mobile)",
                    top = true
                ),
                igareck("WHITE-CIDR-RU-all.txt", "White list: all"),
                igareck(
                    "TOR-BRIDGES/TOR_BRIDGES_TOP100.txt",
                    "Tor bridges: top-100",
                    kind = "tor",
                    top = true
                ),
                igareck(
                    "TOR-BRIDGES/TOR_BRIDGES_ALL.txt",
                    "Tor bridges: all",
                    kind = "tor"
                )
            )
        }
    }
}

/**
 * Результат проверки коннекта.
 *
 * Три состояния вместо двух: `null` в [alive] -- «не проверялось», и это
 * не то же самое, что «жив»: список по умолчанию показывает и тех, и
 * других, а в архив попадают только те, кого проверили и он ответил
 * отказом.
 */
@Serializable
data class LinkHealth(
    /** null -- не проверялся; true -- жив; false -- мёртв (в архив). */
    val alive: Boolean? = null,
    /** Задержка ответа в миллисекундах; -1 -- измерить не удалось. */
    val latencyMs: Int = -1,
    /** Когда проверяли; 0 -- никогда. */
    val checkedAtMs: Long = 0L,
    /** Причина отказа -- коротко, по-английски, для строки в списке. */
    val error: String = ""
)

/**
 * Источник коннектов: один файл-подписка из GitHub-репозитория или своя ссылка.
 *
 * [url] плюс [mirrors] -- это одна подписка, у которой несколько адресов:
 * файлы в репо обновляются синхронно на всех площадках, поэтому зеркало
 * не хранит отдельную копию, а просто тот же текст с другого хоста.
 */
@Serializable
data class VpnSource(
    val id: String,
    val name: String,
    val url: String,
    val mirrors: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** "proxy" -- обычные коннекты, "tor" -- мосты Tor. */
    val kind: String = "proxy",
    /** Период автообновления; репозитории обновляются раз в 2-4 часа. */
    val refreshSeconds: Int = 7200,
    /** Показывать источник в топе списка источников. */
    val top: Boolean = false,
    /** Когда последний раз удачно обновляли; 0 -- ещё никогда. */
    val lastRefreshAtMs: Long = 0L
) {
    /** Все адреса по порядку: основной, потом зеркала. */
    fun urls(): List<String> = listOf(url) + mirrors.filter { it.isNotBlank() && it != url }
}
