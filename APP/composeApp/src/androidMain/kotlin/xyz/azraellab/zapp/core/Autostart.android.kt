package xyz.azraellab.zapp.core

/**
 * Android: отдельной регистрации в системе нет.
 *
 * Фоновый старт после перезагрузки делает BroadcastReceiver по флагу
 * `bootAutostart`, а автозапуск «добавить приложение» у
 * производителей живёт в личных настройках системы, в которые
 * приложение не пишет. Тумблер в UI остаётся флагом конфига.
 */
actual object AutostartCtl {
    actual val supported: Boolean get() = false
    actual fun status(): Boolean = false
    actual fun enable(): Boolean = false
    actual fun disable(): Boolean = false
}
