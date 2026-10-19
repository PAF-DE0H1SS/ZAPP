package xyz.azraellab.zapp.core.routing

import xyz.azraellab.zapp.core.native.ZappNative

/**
 * IP/CIDR-математика: натив (C, ipmath.c) первым, та же логика на
 * Kotlin -- фолбэк, когда библиотека не поднялась. Обе ветки обязаны
 * отдавать одинаковый результат -- это держит паритетный тест.
 */
actual fun parseIpv4(text: String): UInt? =
    ZappNative.parseIpv4(text) ?: parseIpv4Fallback(text)

actual fun parseIpv6(text: String): List<Int>? =
    ZappNative.parseIpv6(text) ?: parseIpv6Fallback(text)

actual fun isIpLiteral(text: String): Boolean =
    ZappNative.isIpLiteral(text)
        ?: (parseIpv4Fallback(text) != null || parseIpv6Fallback(text) != null)

actual fun cidrContains(cidr: String, address: String): Boolean =
    ZappNative.cidrContains(cidr, address) ?: cidrContainsFallback(cidr, address)

/** Фолбэк parseIpv4 без нативной библиотеки. */
internal fun parseIpv4Fallback(text: String): UInt? {
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

/** Фолбэк parseIpv6 без нативной библиотеки. */
internal fun parseIpv6Fallback(text: String): List<Int>? {
    var s = text.trim().lowercase()
    if (s.isEmpty()) return null
    if ('.' in s) {
        val cut = s.lastIndexOf(':')
        if (cut < 0) return null
        val v4 = parseIpv4Fallback(s.substring(cut + 1)) ?: return null
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

/** Фолбэк cidrContains без нативной библиотеки. */
internal fun cidrContainsFallback(cidr: String, address: String): Boolean {
    val slash = cidr.indexOf('/')
    if (slash <= 0) return false
    val base = cidr.substring(0, slash)
    val bits = cidr.substring(slash + 1).toIntOrNull() ?: return false

    val ip4 = parseIpv4Fallback(address)
    val base4 = parseIpv4Fallback(base)
    if (ip4 != null && base4 != null) {
        if (bits !in 0..32) return false
        if (bits == 0) return true
        val mask = 0xFFFFFFFFu shl (32 - bits)
        return (ip4 and mask) == (base4 and mask)
    }

    val ip6 = parseIpv6Fallback(address)
    val base6 = parseIpv6Fallback(base)
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
