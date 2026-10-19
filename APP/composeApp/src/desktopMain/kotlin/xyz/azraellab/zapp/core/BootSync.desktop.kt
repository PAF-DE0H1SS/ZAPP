package xyz.azraellab.zapp.core

/**
 * Desktop: реестра нет -- там автозапуск это регистрация в системе
 * (Run-ключ, XDG, Login Items), которую держит `AutostartCtl`.
 */
actual object BootSync {
    actual fun onConfigSaved(config: AppConfig) = Unit
}
