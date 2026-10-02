package xyz.azraellab.zapp.core

/**
 * Возможности десктопа.
 *
 * Подмены координат здесь нет: системного способа задать тестового провайдера
 * у Windows и Linux не существует, и приложение не будет делать вид, что он
 * есть. Поэтому раздел GPS на десктопе просто не появляется в навигации.
 */
actual fun platformCaps(): PlatformCaps = object : PlatformCaps {
    override val gpsSpoofingSupported: Boolean = false
}
