package xyz.azraellab.zapp.core.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.azraellab.zapp.core.log.appDataDir

// Общий для Android и Desktop: каталог данных и сеть у обеих платформ
// одинаковы, разнится только установка APK (отдельный actual в androidMain).

/** Имя файла в кеше: версия без `v` и без символов, мешающих пути. */
internal fun updateApkName(version: String): String {
    val clean = version.trim().removePrefix("v").removePrefix("V")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "unknown" }
    return "zapp-$clean.apk"
}

internal fun updateApkFile(version: String): File =
    File(File(appDataDir(), "updates"), updateApkName(version))

actual fun cachedUpdateApk(version: String): String? =
    updateApkFile(version).takeIf { it.exists() && it.length() > 0 }?.absolutePath

actual suspend fun downloadUpdateApk(version: String, url: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val dest = updateApkFile(version)
            if (dest.exists() && dest.length() > 0) return@runCatching true
            dest.parentFile?.mkdirs()

            // Каталог может быть непустым от упавшей загрузки: свежий
            // ответ пишется в .part и подменяет файл только целиком.
            val tmp = File(dest.parentFile, dest.name + ".part")
            runCatching { tmp.delete() }

            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode != 200) return@runCatching false
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                conn.disconnect()
            }

            if (tmp.length() <= 0) {
                runCatching { tmp.delete() }
                return@runCatching false
            }
            if (dest.exists()) runCatching { dest.delete() }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                runCatching { tmp.delete() }
            }
            dest.exists() && dest.length() > 0
        }.getOrDefault(false)
    }
