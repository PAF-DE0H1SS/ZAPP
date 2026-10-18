package xyz.azraellab.zapp.core.update

import java.io.File
import xyz.azraellab.zapp.core.log.appDataDir

/**
 * Обновлённое ядро лежит в каталоге данных и перекрывает box из PATH.
 *
 * На десктопе бинари не запакованы и подмена честна: свежий файл
 * ложится рядом с кешем обновлений, и туннель берёт его при следующем
 * старте. Android сюда не попадает -- там бинари в jniLibs.
 */
actual fun coreBoxPath(): String? {
    val file = File(File(appDataDir(), "core"), coreFileName())
    return file.absolutePath.takeIf { it.isNotBlank() && file.exists() && file.canExecute() }
}

actual fun writeUpdatedCore(binary: ByteArray, version: String): Boolean = runCatching {
    val dir = File(appDataDir(), "core")
    dir.mkdirs()
    val dest = File(dir, coreFileName())
    // Свежий файл пишется в .part и подменяет прежний только целиком:
    // обрыв загрузки не должен оставить на месте ядра обрубок.
    val tmp = File(dir, dest.name + ".part")
    tmp.writeBytes(binary)
    if (!tmp.renameTo(dest)) {
        dest.delete()
        if (!tmp.renameTo(dest)) error("cannot place core binary")
    }
    dest.setExecutable(true, false)
    File(dir, "box.version").writeText(version.trim().removePrefix("v"))
    true
}.getOrDefault(false)

/** Имя файла под ОС: на windows бинарь без расширения не запускается. */
private fun coreFileName(): String =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", true)) "box.exe" else "box"
