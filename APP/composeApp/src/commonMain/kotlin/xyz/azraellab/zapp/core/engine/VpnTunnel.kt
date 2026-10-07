package xyz.azraellab.zapp.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnProfile

/**
 * Параметры туннеля, которые должна настроить платформа до запуска ядра.
 *
 * На Android туннель создаёт VpnService: у приложения нет прав на
 * `/dev/net/tun`, зато система выдаёт ему готовый дескриптор. Поля
 * зеркалят tun-инбаунд из сгенерированного конфига, чтобы маршруты
 * платформы и маршруты ядра не расходились ни на запятую.
 */
data class VpnTunnelParams(
    /** Имя соединения в системном списке VPN. */
    val session: String,
    /** Адреса/префиксы туннеля, например `172.19.0.1/30`. */
    val addresses: List<String>,
    /** Маршруты через туннель, например `0.0.0.0/0`. */
    val routes: List<String>,
    /** DNS-серверы, которые получат приложения. */
    val dns: List<String>,
    /** 0 -- не задавать, взять системный. */
    val mtu: Int,
    /** Приложения, которым разрешено идти мимо туннеля (split tunneling). */
    val bypassApps: List<String>
)

/**
 * Мост «ядро туннеля <-> платформенный VPN».
 *
 * Android actual поднимает foreground-сервис с VpnService и отдаёт номер
 * готового fd; desktop actual возвращает null -- на Linux туннель ядро
 * открывает само, и в окружение передавать нечего.
 */
expect object VpnTunnel {

    /**
     * Путь к unix-сокету, которым платформа передаёт ядру готовый fd
     * (SCM_RIGHTS), либо null, если передавать нечего.
     *
     * Номер fd в окружении ненадёжен: ProcessBuilder при спавне дочернего
     * процесса закрывает все унаследованные дескрипторы > 2, поэтому ядро
     * получает fd через сокет.
     */
    val fdSocketPath: String?

    /**
     * Поднимает платформенный туннель.
     *
     * @return номер fd для ядра либо null, если fd не нужен.
     * @throws IllegalStateException если туннель поднять не удалось:
     *         нет разрешения, система отказала, сервис не ответил.
     */
    suspend fun establish(params: VpnTunnelParams): Int?

    /** Снимает туннель. Безопасно вызывать повторно. */
    fun close()
}

/**
 * Читает из сгенерированного конфига tun-инбаунд и собирает параметры
 * платформы.
 *
 * `null` -- в конфиге нет tun-инбаунда: это прокси-конфиг без туннеля,
 * платформе поднимать нечего, а fd ядру не нужен.
 */
fun vpnTunnelParams(
    builtJson: String,
    vpn: VpnConfig,
    session: String
): VpnTunnelParams? {
    val root = runCatching { Json.parseToJsonElement(builtJson).jsonObject }.getOrNull() ?: return null
    val inbound = (root["inbounds"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.firstOrNull { element ->
            (element["type"] as? JsonPrimitive)?.contentOrNull == "tun"
        } ?: return null

    val addresses = inbound.stringList("address")?.takeIf { it.isNotEmpty() }
        ?: listOf("172.19.0.1/30")
    val mtu = (inbound["mtu"] as? JsonPrimitive)?.intOrNull ?: 0
    val routes = buildList {
        if (addresses.none { ':' in it }) add("0.0.0.0/0")
        if (addresses.any { ':' in it }) add("::/0")
        // IPv6-адрес есть -- в6-маршрут обязан быть; а вот в4-маршрут
        // добавляется всегда, даже при одном в6-адресе: без него
        // приложения останутся без сети в принципе.
        if (addresses.any { ':' in it } && addresses.none { ':' !in it }) add(0, "0.0.0.0/0")
    }
    val dns = (vpn.dnsList()).filter { it.isNotBlank() }
        .ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
    val bypass = if (vpn.routing.allowPerAppBypass) vpn.routing.bypassAppList() else emptyList()

    return VpnTunnelParams(
        session = session,
        addresses = addresses,
        routes = routes,
        dns = dns,
        mtu = mtu,
        bypassApps = bypass
    )
}

private fun JsonObject.stringList(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    return array.mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/**
 * Параметры туннеля для afunix-ветки openvpn: адресация из профиля.
 *
 * В afunix-режиме openvpn не трогает tun-интерфейс сам (и на Android
 * не может: fd выдаёт VpnService), поэтому адрес и маску читаем из
 * клиентской директивы `ifconfig local second` до establish. Пуш
 * сервера не читается -- VpnService требует адрес до подключения, а
 * пуш приходит после рукопожатия; конфиг без ifconfig честно
 * получает `null`, и вызывающая сторона падает с понятной ошибкой.
 *
 * Второй аргумент ifconfig в топологии p2p бывает и пир-адресом
 * (`ifconfig 10.8.0.6 10.8.0.5`), а не маской: маска обязана быть
 * непрерывной, пир-адрес получает /32. Маршруты не разбираются --
 * платформа кладёт в туннель всё (0.0.0.0/0), как и в конфиге box.
 */
fun openvpnTunnelParams(profile: VpnProfile, vpn: VpnConfig): VpnTunnelParams? {
    val words = profile.rawConfig.lineSequence()
        .map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.firstOrNull().equals("ifconfig", ignoreCase = true) }
        ?: return null
    if (words.size < 3) return null
    val local = ipv4Octets(words[1]) ?: return null
    val prefix = netmaskPrefix(words[2]) ?: 32
    val dns = vpn.dnsList().filter { it.isNotBlank() }
        .ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
    val bypass = if (vpn.routing.allowPerAppBypass) vpn.routing.bypassAppList() else emptyList()

    return VpnTunnelParams(
        session = profile.name.ifBlank { "ZAPP" },
        addresses = listOf("${local.joinToString(".")}/$prefix"),
        routes = listOf("0.0.0.0/0"),
        dns = dns,
        mtu = profile.mtu,
        bypassApps = bypass
    )
}

/** Четыре октета 0..255, либо null -- это не IPv4-адрес. */
private fun ipv4Octets(value: String): List<Int>? {
    val octets = value.split('.').map { it.toIntOrNull() ?: return null }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return null
    return octets
}

/**
 * Длина префикса, если второй аргумент ifconfig -- непрерывная маска
 * (255...255,0...0); пир-адрес и прочий мусор дают null -> /32.
 */
private fun netmaskPrefix(value: String): Int? {
    val octets = ipv4Octets(value) ?: return null
    val bits = octets.joinToString("") { it.toString(2).padStart(8, '0') }
    val firstZero = bits.indexOf('0')
    if (firstZero >= 0 && bits.substring(firstZero).contains('1')) return null
    return bits.count { it == '1' }.takeIf { it > 0 }
}
