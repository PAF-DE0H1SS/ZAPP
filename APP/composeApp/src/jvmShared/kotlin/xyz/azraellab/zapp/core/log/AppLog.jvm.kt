package xyz.azraellab.zapp.core.log

import java.io.File

// Общее для Android и Desktop: время одинаково, каталог данных -- у платформы.

internal actual fun platformNowMs(): Long = System.currentTimeMillis()

internal actual fun platformFormatTime(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
        .format(java.util.Date(ms))

/** Каталог данных приложения (лог, временные файлы). */
internal expect fun appDataDir(): File

/**
 * Потолок файла журнала.
 *
 * Замерено на устройстве: за полчаса работы с включённым логом
 * `zapp-errors.log` дорос до 13 501 754 байт. Файл пишется дозаписью и
 * без ротации растёт, пока не съест всё свободное место.
 */
private const val MAX_LOG_BYTES = 1_000_000L

/**
 * Сколько строк оставляем при обрезке.
 *
 * Хвост, а не начало: в багрепорте нужны последние причины, а первые
 * строки -- это давно пережитая сессия, уже уехавшая в журнал событий.
 */
private const val KEEP_LOG_LINES = 2_000

internal actual fun appendToFile(fileName: String, line: String) {
    runCatching {
        val dir = appDataDir()
        dir.mkdirs()
        val file = File(dir, fileName)
        // Ротация своим runCatching: сбой обрезки не должен отменять
        // запись, иначе теряется ровно та строка, ради которой всё
        // и затевалось. Проверка -- раз в мегабайт, а не на каждой
        // строке: после обрезки файл снова становится маленьким.
        if (file.length() > MAX_LOG_BYTES) runCatching { rotateTail(file) }
        file.appendText(line + "\n")
    }
}

/**
 * Обрезает журнал до хвоста.
 *
 * Читается целиком, а не по частям: потолок в мегабайт -- это доля
 * секунды, и усложнять алгоритм ради неё не стоит.
 */
private fun rotateTail(file: File) {
    val lines = runCatching { file.readLines() }.getOrNull() ?: return
    if (lines.size <= KEEP_LOG_LINES) return
    val tail = lines.takeLast(KEEP_LOG_LINES).joinToString(separator = "\n", postfix = "\n")
    runCatching { file.writeText(tail) }
}

internal actual fun clearFile(fileName: String) {
    runCatching {
        val file = File(appDataDir(), fileName)
        if (file.exists()) file.writeText("")
    }
}
