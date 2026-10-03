package xyz.azraellab.zapp.core.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Base64 в том виде, в каком он встречается в подписках.
 *
 * Спецификация -- одна, а на практите в ссылках лежит три разных варианта:
 * стандартный алфавит, URL-safe ( `-` и `_` вместо `+` и `/`) и без концевого
 * заполнения `=`, потому что его не любят в URL. Ссылка, скопированная из
 * чужого приложения, почти всегда отличается от эталона хотя бы одним из
 * признаков, поэтому сюда кладётся нормализация, а не голый `Base64.decode`.
 */
object FlexibleBase64 {

    /** Декодирует строку, приводя её сначала к стандартному виду. `null` -- не base64. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeOrNull(text: String): ByteArray? {
        val cleaned = text.trim()
            .replace('-', '+')
            .replace('_', '/')
            .replace(Regex("\\s"), "")
        if (cleaned.isEmpty() || cleaned.length % 4 == 1) return null
        if (!cleaned.all { it in ALPHABET || it == '=' }) return null

        val padded = when (cleaned.length % 4) {
            0 -> cleaned
            2 -> cleaned + "=="
            3 -> cleaned + "="
            else -> return null
        }
        return runCatching { Base64.Default.decode(padded) }.getOrNull()
    }

    /** Декодирует в UTF-8, `null` -- не base64 или не текст. */
    fun decodeToStringOrNull(text: String): String? =
        decodeOrNull(text)?.let { bytes ->
            runCatching { bytes.decodeToString() }.getOrNull()
        }

    /** Кодирует без концевого заполнения: результат кладут в URL. */
    @OptIn(ExperimentalEncodingApi::class)
    fun encodeNoPadding(bytes: ByteArray): String =
        Base64.Default.encode(bytes).trimEnd('=')

    /** Кодирует строку без концевого заполнения. */
    fun encodeNoPadding(text: String): String = encodeNoPadding(text.encodeToByteArray())

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
