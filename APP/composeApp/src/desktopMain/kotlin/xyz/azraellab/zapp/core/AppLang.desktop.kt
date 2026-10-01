package xyz.azraellab.zapp.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Язык интерфейса на десктопе: `~/.config/zapp/lang`, вплотную к теме.
 *
 * Файл тот же, что и у темы: запись атомарная, потому что падение на середине
 * оставило бы файл, который следующий запуск не смог бы прочитать.
 */
actual object AppLangStore {
    private fun file(): File {
        val home = System.getProperty("user.home") ?: "."
        return File(home, ".config/zapp/lang")
    }

    actual fun read(): String? = runCatching {
        val f = file()
        if (!f.isFile) return null
        f.readText().trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    actual fun write(code: String): Boolean = runCatching {
        val f = file()
        val parent = f.parentFile
        if (!parent.exists()) parent.mkdirs()
        val tmp = Path.of(f.absolutePath + ".tmp")
        Files.write(tmp, code.toByteArray(Charsets.UTF_8))
        Files.move(tmp, Path.of(f.absolutePath), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    // toLanguageTag(), а не getLanguage(): регион нужен для разбора. В Молдове
    // язык системы -- `ro`, и без `ro-MD` правило «СНГ -> русский» не срабатывает.
    actual fun systemCode(): String? = runCatching {
        Locale.getDefault().toLanguageTag()
            .takeIf { it.isNotBlank() && it != "und" }
    }.getOrNull()
}
