package xyz.azraellab.zapp.core

/**
 * Loopback-исключения UWP-пакетов (Windows).
 *
 * Песочница UWP не даёт пакетным приложениям соединяться с loopback,
 * пока для семейства не выдано исключение через CheckNetIsolation.
 * Без него UWP-приложения не видят локальный SOCKS/HTTP-прокси и
 * настройка прокси в них бессмысленна. На платформах без UWP
 * (Android, обычный десктоп) всё пустое, секция в UI не показывается.
 */
expect object UwpLoopbackCtl {

    /** Семейства установленных UWP-пакетов; пусто, если платформа не умеет. */
    suspend fun families(): List<String>

    /** Семейства, у которых loopback уже разрешён. */
    suspend fun exempt(): Set<String>

    /** Поставить (on) или снять исключение; false — не вышло. */
    suspend fun set(packageFamily: String, on: Boolean): Boolean
}
