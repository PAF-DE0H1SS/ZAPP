package xyz.azraellab.zapp.core

/**
 * На Android нет системы прокси, за которую приложение могло бы
 * зацепиться: весь трафик идёт через VPN-сессию, а глобальные
 * прокси-переменные системы (http_proxy) здесь не участвуют в
 * маршрутизации. Вызовы -- тихие заглушки, чтобы общий код в AppState
 * не знал о разнице платформ.
 */
actual object SystemProxyCtl {
    actual suspend fun apply() = Unit
    actual suspend fun restore() = Unit
}
