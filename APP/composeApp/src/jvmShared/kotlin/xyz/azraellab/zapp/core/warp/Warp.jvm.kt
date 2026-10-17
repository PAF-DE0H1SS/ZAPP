package xyz.azraellab.zapp.core.warp

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.XECPublicKey
import kotlin.io.encoding.Base64

/**
 * Генерация пары ключей WireGuard на JDK X25519.
 *
 * Общая для Android (API 33+) и десктопа. `getScalar()` не используется:
 * на Android он возвращает `Optional<byte[]>`, на JDK 21 -- `ByteBuffer`,
 * и одна реализация под обе платформы невозможна. Приватный ключ берётся
 * из PKCS#8 (`encoded`), публичный -- из `getU()`, и оба есть на обеих.
 *
 * null, если провайдер недоступен -- вызывающий честно отвечает отказом.
 */
actual fun generateWireGuardKeyPair(): Pair<String, String>? = runCatching {
    val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
    // PKCS#8 X25519 фиксированной длины: последние 32 байта -- скаляр.
    val priv = pair.private.encoded.takeLast(32).toByteArray()
    val pub = (pair.public as XECPublicKey).u.littleEndian32()
    Base64.Default.encode(priv) to Base64.Default.encode(pub)
}.getOrNull()

/** Точка u как BigInteger; WireGuard ждёт little-endian 32 байта. */
private fun BigInteger.littleEndian32(): ByteArray {
    val big = toByteArray()
    val out = ByteArray(32)
    val n = minOf(big.size, 32)
    for (i in 0 until n) out[i] = big[big.size - 1 - i]
    return out
}
