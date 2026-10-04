package xyz.azraellab.zapp.core

/**
 * Десктоп: иконка приложения -- свойство DE, а не пакета, подменить её
 * из кода нельзя. Функция есть, чтобы зовущий код был одинаковым.
 */
actual object LauncherIcon {
    actual fun setConnected(connected: Boolean) = Unit
}
