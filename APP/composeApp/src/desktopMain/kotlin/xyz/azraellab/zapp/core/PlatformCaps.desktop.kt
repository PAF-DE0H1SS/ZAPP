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
    override val apkUpdatesSupported: Boolean = false
    override val systemProxySupported: Boolean = true
    override val autostartSupported: Boolean = true
    override val uwpLoopbackSupported: Boolean =
        System.getProperty("os.name").orEmpty().contains("win", true)
    override val coreUpdateSupported: Boolean = true

    // Десктоп -- не TV: пульт тут ни при чём, даже если система
    // заявит о leanback-фиче (боксы на Android подключаются к TV).
    override val tvMode: Boolean = false
}
