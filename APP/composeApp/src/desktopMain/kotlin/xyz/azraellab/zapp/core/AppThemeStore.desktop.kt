package xyz.azraellab.zapp.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Режим темы на десктопе: `~/.config/zapp/theme`. Не секрет, поэтому отдельного
 * каталога с правами 700 не нужно, но запись идёт через временный файл: обрыв
 * на середине не должен оставить читаемый мусор, который сломает запуск.
 */
actual object AppThemeStore {
    private fun file(): File {
        val home = System.getProperty("user.home") ?: "."
        return File(home, ".config/zapp/theme")
    }

    actual fun read(): String? = runCatching {
        val f = file()
        if (!f.isFile) return null
        f.readText().trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    actual fun write(mode: String): Boolean = runCatching {
        val f = file()
        val parent = f.parentFile
        if (!parent.exists()) parent.mkdirs()
        val tmp = Path.of(f.absolutePath + ".tmp")
        Files.write(tmp, mode.toByteArray(Charsets.UTF_8))
        Files.move(tmp, Path.of(f.absolutePath), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}