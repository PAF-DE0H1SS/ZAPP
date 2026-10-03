package xyz.azraellab.zapp.core.net

import xyz.azraellab.zapp.core.VpnProfile

/**
 * Разбор конфига WireGuard в формате INI и обратно.
 *
 * WireGuard не ходит по ссылкам: его выгружают файлом и вставляют текстом.
 * Отсюда два направления -- `parse` для вставки и `write` для выгрузки,
 * зеркальных друг другу, чтобы файл, прошедший через приложение, остался
 * тем же файлом.
 *
 * В профиле один peer, поэтому при нескольких блоках `[Peer]` берётся
 * первый: пакетное хранение пиров -- это не про карточку одного профиля.
 */
object WireGuardIni {

    /** Разбирает текст конфига. `null` -- нет ни `[Interface]`, ни `[Peer]`. */
    fun parse(text: String, name: String = ""): VpnProfile? {
        var section = ""
        var privateKey = ""
        var publicKey = ""
        var presharedKey = ""
        var address = ""
        var dns = ""
        var mtu = 0
        var allowedIps = ""
        var endpoint = ""
        var keepalive = 0
        var sawPeer = false
        // Незнакомые ключи [Interface]: jc, jmin, s1..s4, h1..h4, i1..i5 и
        // всё прочее, что приходит из Amnezia-конфигов. Не выбрасываем --
        // без них конфиг перестаёт быть тем же конфигом.
        val extras = LinkedHashMap<String, String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

            if (line.startsWith("[")) {
                val title = line.trim('[', ']')
                section = when {
                    title.equals("interface", ignoreCase = true) -> "interface"
                    title.equals("peer", ignoreCase = true) -> {
                        if (sawPeer) break else "peer"
                    }
                    else -> ""
                }
                if (section == "peer") sawPeer = true
                continue
            }

            val (key, value) = splitProperty(line) ?: continue
            if (value.isEmpty()) continue

            val known = when {
                section == "interface" && key.equals("privatekey", ignoreCase = true) -> {
                    privateKey = value
                    true
                }
                section == "interface" && key.equals("address", ignoreCase = true) -> {
                    address = value
                    true
                }
                section == "interface" && key.equals("dns", ignoreCase = true) -> {
                    dns = value
                    true
                }
                section == "interface" && key.equals("mtu", ignoreCase = true) -> {
                    mtu = value.toIntOrNull() ?: 0
                    true
                }
                section == "peer" && key.equals("publickey", ignoreCase = true) -> {
                    publicKey = value
                    true
                }
                section == "peer" && key.equals("presharedkey", ignoreCase = true) -> {
                    presharedKey = value
                    true
                }
                section == "peer" && key.equals("allowedips", ignoreCase = true) -> {
                    allowedIps = value
                    true
                }
                section == "peer" && key.equals("endpoint", ignoreCase = true) -> {
                    endpoint = value
                    true
                }
                section == "peer" && key.equals("persistentkeepalive", ignoreCase = true) -> {
                    keepalive = value.removeSuffix("s").trim().toIntOrNull() ?: 0
                    true
                }
                section == "interface" -> false
                else -> true
            }
            if (!known) extras[key.lowercase()] = value
        }

        if (!sawPeer) return null

        val (host, port) = splitEndpoint(endpoint)
        return VpnProfile(
            id = "",
            name = name,
            gateway = host,
            gatewayPort = port,
            address = address,
            dnsServers = dns,
            privateKey = privateKey,
            publicKey = publicKey,
            presharedKey = presharedKey,
            allowedIps = allowedIps,
            mtu = if (mtu > 0) mtu else 1420,
            keepaliveSeconds = keepalive,
            extras = extras,
            rawConfig = text
        )
    }

    /** Собирает текст конфига из профиля. Порядок ключей -- как в WireGuard. */
    fun write(profile: VpnProfile): String = buildString {
        appendLine("[Interface]")
        if (profile.privateKey.isNotBlank()) appendLine("PrivateKey = ${profile.privateKey}")
        if (profile.address.isNotBlank()) appendLine("Address = ${profile.address}")
        if (profile.dnsServers.isNotBlank()) appendLine("DNS = ${profile.dnsServers}")
        appendLine("MTU = ${profile.mtu}")
        for ((key, value) in profile.extras) {
            appendLine("${key.replaceFirstChar { it.uppercase() }} = $value")
        }
        appendLine()
        appendLine("[Peer]")
        if (profile.publicKey.isNotBlank()) appendLine("PublicKey = ${profile.publicKey}")
        if (profile.presharedKey.isNotBlank()) appendLine("PresharedKey = ${profile.presharedKey}")
        if (profile.allowedIps.isNotBlank()) appendLine("AllowedIPs = ${profile.allowedIps}")
        if (profile.gateway.isNotBlank()) {
            appendLine("Endpoint = ${formatHost(profile.gateway)}:${profile.gatewayPort}")
        }
        if (profile.keepaliveSeconds > 0) {
            appendLine("PersistentKeepalive = ${profile.keepaliveSeconds}")
        }
    }

    private fun splitProperty(line: String): Pair<String, String>? {
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        return line.substring(0, eq).trim() to line.substring(eq + 1).trim()
    }

    private fun splitEndpoint(endpoint: String): Pair<String, Int> {
        if (endpoint.isBlank()) return "" to 0
        if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')
            if (close > 0) {
                val host = endpoint.substring(1, close)
                val port = endpoint.substring(close + 1).removePrefix(":").toIntOrNull() ?: 0
                return host to port
            }
        }
        val colon = endpoint.lastIndexOf(':')
        if (colon <= 0) return endpoint to 0
        return endpoint.substring(0, colon) to (endpoint.substring(colon + 1).toIntOrNull() ?: 0)
    }

    private fun formatHost(host: String): String = if (':' in host) "[$host]" else host
}
