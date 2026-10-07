package xyz.azraellab.zapp.core.gps

/**
 * Десктоп не подменяет координаты: там нет ни LocationManager, ни
 * способа повлиять на то, что приложения считают местоположением.
 *
 * Заглушка нужна, чтобы общий код не знал о платформах и не требовал
 * проверок `if (android)` в UI.
 */
actual fun createGpsEngine(): GpsEngine = object : BaseGpsEngine() {
    override val supported: Boolean = false

    override fun start(config: xyz.azraellab.zapp.core.GpsConfig, vpnGateway: String) {
        // Заглушка тоже с причиной: ERROR без строки в журнале -- та же
        // «голая ошибка», что и на Android.
        fail(GpsEvent(xyz.azraellab.zapp.core.Str.GPS_LOG_NO_PROVIDER))
    }

    override fun stop() {
        setState(GpsState.OFF)
        setFix(null)
    }

    override fun applyConfig(config: xyz.azraellab.zapp.core.GpsConfig) = Unit
}
