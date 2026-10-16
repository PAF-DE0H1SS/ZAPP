package xyz.azraellab.zapp.core.routing

/**
 * Разбор IP-адресов для детектора маршрутизации.
 *
 * Библиотеки в commonMain не дают `InetAddress` (на JS и Wasm его
 * вообще нет), поэтому адреса разбираются вручную: детектору нужны
 * только два вопроса -- «это литерал IP?» и «адрес в подсети?».
 * IPv6 поддержан сжатием `::` и хвостовым IPv4 (`::ffff:1.2.3.4`).
 */

/** IPv4 в 32 бита или null: неверный формат, лишние нули, мусор. */
fun parseIpv4(text: String): UInt? {
    val parts = text.trim().split('.')
    if (parts.size != 4) return null
    var value = 0u
    for (part in parts) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        val octet = part.toInt() ?: return null
        if (octet > 255) return null
        value = (value shl 8) or octet.toUInt()
    }
    return value
}

/**
 * IPv6 в восемь групп по 16 бит или null.
 *
 * Сжатие нулей `::` занимает любое число групп (включая все восемь),
 * хвостовой IPv4 превращается в две группы -- в том числе после `::`
 * (`::1.2.3.4` -> `::1:2`).
 */
fun parseIpv6(text: String): List<Int>? {
    var s = text.trim().lowercase()
    if (s.isEmpty()) return null
    if ('.' in s) {
        val cut = s.lastIndexOf(':')
        if (cut < 0) return null
        val v4 = parseIpv4(s.substring(cut + 1)) ?: return null
        val hi = ((v4 shr 16) and 0xFFFFu).toInt().toString(16)
        val lo = (v4 and 0xFFFFu).toInt().toString(16)
        s = s.substring(0, cut + 1) + "$hi:$lo"
    }
    fun groups(piece: String): List<Int>? {
        if (piece.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        for (g in piece.split(':')) {
            if (g.isEmpty() || g.length > 4 || g.any { it !in "0123456789abcdef" }) return null
            out += g.toInt(16)
        }
        return out
    }
    if ("::" !in s) {
        return groups(s)?.takeIf { it.size == 8 }
    }
    val parts = s.split("::", limit = 2)
    if (parts.size != 2) return null
    val head = groups(parts[0]) ?: return null
    val tail = groups(parts[1]) ?: return null
    if (head.size + tail.size > 7) return null
    return head + List(8 - head.size - tail.size) { 0 } + tail
}

/** Строк -- литерал IPv4 или IPv6, а не домен. */
fun isIpLiteral(text: String): Boolean =
    parseIpv4(text) != null || parseIpv6(text) != null

/**
 * Лежит ли адрес в подсети CIDR: `10.0.0.0/8` ловит `10.1.2.3`.
 *
 * Семейства должны совпасть (v4-подсеть против v6-адреса -- false),
 * неверные значения с обеих сторон -- false.
 */
fun cidrContains(cidr: String, address: String): Boolean {
    val slash = cidr.indexOf('/')
    if (slash <= 0) return false
    val base = cidr.substring(0, slash)
    val bits = cidr.substring(slash + 1).toIntOrNull() ?: return false

    val ip4 = parseIpv4(address)
    val base4 = parseIpv4(base)
    if (ip4 != null && base4 != null) {
        if (bits !in 0..32) return false
        if (bits == 0) return true
        val mask = 0xFFFFFFFFu shl (32 - bits)
        return (ip4 and mask) == (base4 and mask)
    }

    val ip6 = parseIpv6(address)
    val base6 = parseIpv6(base)
    if (ip6 != null && base6 != null) {
        if (bits !in 0..128) return false
        var left = bits
        for (i in 0 until 8) {
            if (left <= 0) break
            val take = minOf(left, 16)
            val mask = (0xFFFF shr (16 - take)) shl (16 - take)
            if ((ip6[i] and mask) != (base6[i] and mask)) return false
            left -= take
        }
        return true
    }
    return false
}
