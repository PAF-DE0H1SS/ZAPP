package xyz.azraellab.zapp.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import xyz.azraellab.zapp.core.net.httpGetBytes

/** Официальный фид релизов sing-box: ядро обновляется отдельно от приложения. */
const val CORE_RELEASES_URL = "https://api.github.com/repos/SagerNet/sing-box/releases/latest"

/** Найденное обновление ядра: версия и прямая ссылка на архив платформы. */
data class CoreUpdateInfo(
    val version: String,
    val url: String,
    /** true -- zip (windows), false -- tar.gz. */
    val zip: Boolean
)

/** Архив фида под платформу. */
data class CoreAsset(val url: String, val zip: Boolean)

private val CORE_JSON = Json { ignoreUnknownKeys = true }

/**
 * Разбирает фид и выбирает архив под платформу; null -- свежего ядра нет.
 *
 * Обновление ограничено своей minor-веткой: конфиги приложения проверены
 * на версии, с которой собран дистрибутив, а прыжок minor у ядра меняет
 * схему конфига. Свежий patch той же ветки безопасен и предлагается.
 */
fun parseCoreRelease(
    text: String,
    currentVersion: String,
    os: String,
    arch: String
): CoreUpdateInfo? = runCatching {
    val root = CORE_JSON.parseToJsonElement(text).jsonObject
    // .content бросается на JSON-пустоте -- весь разбор под runCatching.
    val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty().removePrefix("v")
    if (tag.isEmpty() || !isNewerVersion(tag, currentVersion)) return null
    if (!coreGateOpen(currentVersion, tag)) return null
    val urls = root["assets"]?.jsonArray
        ?.mapNotNull { it.jsonObject["browser_download_url"]?.jsonPrimitive?.content }
        ?: return null
    val asset = pickCoreAsset(urls, tag, os, arch) ?: return null
    CoreUpdateInfo(version = tag, url = asset.url, zip = asset.zip)
}.getOrNull()

/** Тот же major.minor у текущей и кандидата; битая версия гейт не открывает. */
fun coreGateOpen(current: String, candidate: String): Boolean {
    fun line(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-')
    val a = line(current)
    val b = line(candidate)
    if (a.size < 2 || b.size < 2) return false
    return a[0] == b[0] && a[1] == b[1]
}

/** Архив платформы: `sing-box-<версия>-<os>-<arch>.tar.gz` или `.zip` (windows). */
fun pickCoreAsset(urls: List<String>, version: String, os: String, arch: String): CoreAsset? {
    val stem = "sing-box-$version-$os-$arch"
    urls.firstOrNull { it.endsWith("$stem.tar.gz") }?.let { return CoreAsset(it, false) }
    urls.firstOrNull { it.endsWith("$stem.zip") }?.let { return CoreAsset(it, true) }
    return null
}

/** Целевая пара по свойствам JVM; null -- ОС или архитектура не поддержаны. */
fun coreOsArch(osName: String, osArch: String): Pair<String, String>? {
    val os = when {
        osName.startsWith("Linux", ignoreCase = true) -> "linux"
        osName.startsWith("Windows", ignoreCase = true) -> "windows"
        osName.startsWith("Mac", ignoreCase = true) -> "darwin"
        else -> return null
    }
    val arch = when {
        osArch.startsWith("aarch64", ignoreCase = true) || osArch.startsWith("arm64", ignoreCase = true) -> "arm64"
        osArch.startsWith("amd64", ignoreCase = true) || osArch.startsWith("x86_64", ignoreCase = true) -> "amd64"
        else -> return null
    }
    return os to arch
}

/** Имя бинаря внутри архива платформы. */
fun coreBinaryName(os: String): String = if (os == "windows") "sing-box.exe" else "sing-box"

/** gunzip ответа; null -- поток битый. */
fun gunzip(bytes: ByteArray): ByteArray? = runCatching {
    GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
}.getOrNull()

/**
 * Записи tar-архива по путям; битый заголовок обрывает разбор.
 *
 * Формат тривиален: 512-байтовый заголовок (имя, размер восьмеричным),
 * затем данные, кратные 512.
 */
fun tarEntries(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    var offset = 0
    while (offset + 512 <= bytes.size) {
        val name = bytes.decodeToString(offset, offset + 100).trimEnd('\u0000').trim()
        if (name.isEmpty()) break
        val size = bytes.decodeToString(offset + 124, offset + 136)
            .trimEnd('\u0000', ' ').trim().toIntOrNull(8) ?: break
        val type = bytes[offset + 156].toInt().toChar()
        val start = offset + 512
        if (type == '0' || type == '\u0000') {
            out[name] = bytes.copyOfRange(start, (start + size).coerceAtMost(bytes.size))
        }
        offset = start + ((size + 511) / 512) * 512
    }
    return out
}

/** Записи tar.gz: gunzip плюс tar. */
fun tarGzEntries(bytes: ByteArray): Map<String, ByteArray> =
    gunzip(bytes)?.let { tarEntries(it) } ?: emptyMap()

/** Записи zip-архива; битый поток обрывает разбор. */
fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
            }
        }
    }
    return out
}

/** Бинарь среди записей архива; ищется по базовому имени внутри папки. */
fun findCoreBinary(entries: Map<String, ByteArray>, os: String): ByteArray? {
    val want = coreBinaryName(os)
    return entries.entries.firstOrNull { it.key.substringAfterLast('/') == want }?.value
}

/** Путь к обновлённому ядра; null -- платформа не обновляет ядро. */
expect fun coreBoxPath(): String?

/** Кладёт бинарь в хранилище обновлений; false -- платформа не умеет. */
expect fun writeUpdatedCore(binary: ByteArray, version: String): Boolean
