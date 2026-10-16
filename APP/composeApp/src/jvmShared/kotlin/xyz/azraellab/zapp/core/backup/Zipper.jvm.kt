package xyz.azraellab.zapp.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Общая реализация zip для обеих JVM-платформ.
 *
 * Текстовые записи: весь архив -- JSON, бинарным он становится только
 * внутри контейнера. Повреждённые байты -- null, вызывающий сам решает,
 * как это показать.
 */
actual fun zipPack(entries: List<Pair<String, String>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

actual fun zipUnpack(bytes: ByteArray): Map<String, String>? {
    val result = LinkedHashMap<String, String>()
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
    } catch (_: Exception) {
        return null
    }
    return result
}
