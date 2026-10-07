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
 *
 * Строки с признаком ошибки дублируются в [ErrorLog] -- отдельный журнал
 * только для багрепортов, чтобы не собирать причину из тысячи строк.
 */
object AppLog {

    private const val LIMIT = 1000

    private const val FILE = "zapp.log"

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
        val line = "${platformNowMs()} [$tag] $message"
        synchronized(lock) {
            _lines.value = capLines(_lines.value + line, LIMIT)
        }
        appendToFile(FILE, line)
        if (ErrorLog.captures(message)) ErrorLog.record(line)
    }

    fun clear() {
        synchronized(lock) { _lines.value = emptyList() }
        clearFile(FILE)
    }
}

/** Текущее время (epoch millis) -- платформенная функция. */
internal expect fun platformNowMs(): Long

/** Платформенный вывод в файл лога (android: filesDir, jvm: ~/.config). */
internal expect fun appendToFile(fileName: String, line: String)

internal expect fun clearFile(fileName: String)
