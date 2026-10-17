package xyz.azraellab.zapp.core.warp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.azraellab.zapp.core.VpnProfile

/** Аккаунт Cloudflare WARP, полученный при регистрации. */
data class WarpAccount(
    val id: String,
    val license: String,
    /** Приватный ключ клиента (base64). */
    val privateKey: String,
    /** Публичный ключ пира Cloudflare (base64). */
    val peerPublicKey: String,
    /** Хост эндпоинта пира. */
    val endpointHost: String,
    /** Порт эндпоинта пира. */
    val endpointPort: Int,
    /** Назначенный IPv4 вида `172.16.0.2/32`. */
    val ipv4: String,
    /** Назначенный IPv6. */
    val ipv6: String,
    val clientId: String = ""
)

private const val WARP_REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"
private val JSON = Json { ignoreUnknownKeys = true }

/** Тело запроса регистрации: публичный ключ клиента и серийник устройства. */
fun buildRegisterRequest(publicKey: String, serial: String): String =
    """
    {
      "key": "$publicKey",
      "install_id": "",
      "fcm_token": "",
      "model": "PC",
      "serial_number": "$serial",
      "locale": "en_US"
    }
    """.trimIndent()

/**
 * Разбирает ответ регистрации; null -- битый ответ или нет ключевых полей.
 *
 * Приватный ключ не приходит с сервера -- он остаётся на клиенте, поэтому
 * передаётся сюда и вкладывается в аккаунт.
 */
fun parseRegisterResponse(text: String, privateKey: String): WarpAccount? = runCatching {
    val root = JSON.parseToJsonElement(text).jsonObject
    val config = root["config"]?.jsonObject ?: return null
    val peer = config["peers"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val endpoint = peer["endpoint"]?.jsonObject ?: return null
    val v4 = endpoint["v4"]?.jsonPrimitive?.contentOrNull ?: return null
    val addresses = config["interface"]?.jsonObject?.get("addresses")?.jsonObject ?: return null
    val ipv4 = addresses["v4"]?.jsonPrimitive?.contentOrNull ?: return null
    val (host, port) = splitHostPort(v4) ?: return null

    WarpAccount(
        id = root["id"]?.jsonPrimitive?.contentOrNull ?: "",
        license = root["account"]?.jsonObject?.get("license")?.jsonPrimitive?.contentOrNull ?: "",
        privateKey = privateKey,
        peerPublicKey = peer["public_key"]?.jsonPrimitive?.contentOrNull ?: return null,
        endpointHost = host,
        endpointPort = port,
        ipv4 = ipv4,
        ipv6 = addresses["v6"]?.jsonPrimitive?.contentOrNull ?: "",
        clientId = config["client_id"]?.jsonPrimitive?.contentOrNull ?: ""
    )
}.getOrNull()

/** `162.159.192.1:2408` -> хост и порт; null -- нет порта. */
internal fun splitHostPort(v4: String): Pair<String, Int>? {
    val idx = v4.lastIndexOf(':')
    if (idx <= 0 || idx == v4.length - 1) return null
    val port = v4.substring(idx + 1).toIntOrNull() ?: return null
    return v4.substring(0, idx) to port
}

/** Превращает аккаунт в обычный wireguard-профиль. */
fun WarpAccount.toProfile(name: String = "WARP"): VpnProfile = VpnProfile(
    name = name,
    gateway = endpointHost,
    gatewayPort = endpointPort,
    address = ipv4,
    privateKey = privateKey,
    publicKey = peerPublicKey,
    allowedIps = "0.0.0.0/0",
    dnsServers = "1.1.1.1,1.0.0.1",
    mtu = 1280,
    keepaliveSeconds = 25
)

/** URL эндпоинта регистрации. */
val warpRegisterUrl: String get() = WARP_REG_URL

/** Генерирует пару ключей WireGuard (base64); null -- криптография недоступна. */
expect fun generateWireGuardKeyPair(): Pair<String, String>?
