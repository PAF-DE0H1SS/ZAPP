package xyz.azraellab.zapp.core

/**
 * UWP на Android отсутствует: семейств пакетов нет, исключения
 * LoopbackExempt неприменимы. Секция в UI спрятана по caps.
 */
actual object UwpLoopbackCtl {

    actual suspend fun families(): List<String> = emptyList()

    actual suspend fun exempt(): Set<String> = emptySet()

    actual suspend fun set(packageFamily: String, on: Boolean): Boolean = false
}
