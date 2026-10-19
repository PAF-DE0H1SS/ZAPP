package xyz.azraellab.zapp.core.secure

import java.io.File
import java.security.MessageDigest

actual fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/**
 * Контрольные суммы скачанных файлов.
 *
 * Потоковый sha256 позаимствован у zaprett-app (DownloadUtils.getFileSha256):
 * файл читается кусками, а не целиком -- пресеты и geo-базы бывают
 * мегабайтами. Единая точка проверки для каталога, geo и бинарников:
 * скачанное не принимается на веру.
 */
object Sha256 {

    /** sha256 файла мелкими шестиричными; null -- не прочитали. */
    fun fileHex(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Совпадает ли sha256 файла с ожиданием; см. [normalizeExpected]. */
    fun matches(file: File, expected: String): Boolean {
        val want = normalizeExpected(expected) ?: return false
        return fileHex(file) == want
    }
}
