package xyz.azraellab.zapp.core.secure

/** sha256 байтов мелкими шестиричными (actual -- JVM MessageDigest). */
expect fun sha256Hex(bytes: ByteArray): String

/** Совпадает ли sha256 байтов с ожиданием; см. [normalizeExpected]. */
fun sha256Matches(bytes: ByteArray, expected: String): Boolean {
    val want = normalizeExpected(expected) ?: return false
    return sha256Hex(bytes) == want
}

/**
 * Ожидание в канонический вид: мелкие шестиричные без разделителей.
 *
 * Фингерпринты принято писать с двоеточиями и в верхнем регистре --
 * такая форма принимается как есть. Пустое ожидание -- null: вызывающий
 * решает сам, пропускать ли проверку.
 */
internal fun normalizeExpected(expected: String): String? =
    expected.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { null }
