package xyz.azraellab.zapp.core

/**
 * Хранилище режима темы. Объявление здесь, реализации — в androidMain и desktopMain.
 *
 * AppThemeStore хранит выбор пользователя (system/light/dark) между запусками.
 * Значение не содержит секретов, но пишется атомарно через временный файл
 * на десктопе, чтобы не оставить битый файл при внезапном завершении процесса.
 */
expect object AppThemeStore {
    fun read(): String?
    fun write(mode: String): Boolean
}