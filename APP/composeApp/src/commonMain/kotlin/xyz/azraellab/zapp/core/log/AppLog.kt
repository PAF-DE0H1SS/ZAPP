package xyz.azraellab.zapp.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Детальный журнал приложения: действия пользователя, состояния, ошибки.
 *
 * Отдельно от журналов туннеля и демонов -- они живут в своих карточках, а
 * здесь одна общая лента «что вообще происходило». Выключается настройкой
 * [enabled]: тумблер останавливает и запись в буфер, и запись в файл.
 */
object AppLog {

    private const val LIMIT = 1000

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** Мьютекс: и UI, и движки пишут из разных потоков. */
    private val lock = Any()

    /** Флаг из настроек; меняется из Settings. */
    @Volatile
    var enabled: Boolean = true

    /** Запись. Тег короткий (`vpn`, `zapret`, `err`), сообщение -- по делу. */
    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = "${nowMs()} [$tag] $message"
        synchronized(lock) {
            val next = _lines.value + line
            _lines.value = if (next.size > LIMIT) next.takeLast(LIMIT) else next
        }
        appendToFile(line)
    }

    fun clear() {
        synchronized(lock) { _lines.value = emptyList() }
        clearFile()
    }

    /** Метка времени -- epoch millis; часовой пояс не нужен, это лог. */
    private fun nowMs(): Long = platformNowMs()
}

/** Текущее время (epoch millis) -- платформенная функция. */
internal expect fun platformNowMs(): Long

/** Платформенный вывод в файл лога (android: filesDir, jvm: ~/.config). */
internal expect fun appendToFile(line: String)

internal expect fun clearFile()
