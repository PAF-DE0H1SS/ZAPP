package xyz.azraellab.zapp.core

/**
 * Десктоп: тактов нет, отклик -- визуальный (цвет статуса и орба).
 *
 * Заглушка обязательна: `expect object Haptics` иначе не соберётся на JVM.
 */
actual object Haptics {
    actual fun pulse(event: HapticEvent) = Unit
}
