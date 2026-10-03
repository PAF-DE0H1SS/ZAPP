package xyz.azraellab.zapp.core.root

import java.util.concurrent.TimeUnit

/**
 * Оболочка вокруг `su` для общей JVM-части.
 *
 * Формат `su` не одинаков везде: toybox (Android) принимает только
 * `su 0 <cmd>`, утилита coreutils/util-linux -- только `su -c <cmd>`, а
 * `su` без root и вовсе может зависнуть, ожидая ввод пароля на терминале.
 * Поэтому форма определяется один раз на сессию пробы с закрытым stdin и
 * таймаутом, а дальше все вызовы идут через найденную форму.
 *
 * Результат пробы -- не «есть ли root вообще», а «какая форма работает»:
 * режим [Mode.NONE] не означает, что демон запустить нельзя, -- он означает
 * только, что подниматься выше текущего пользователя придётся без `su`.
 */
object RootShell {

    /** Работающая форма вызова `su`. */
    enum class Mode {
        /** `su 0 sh -c <cmd>` -- toybox на Android. */
        USER_ARG,

        /** `su -c <cmd>` -- утилиты Linux, работает и когда мы уже root. */
        FLAG_C,

        /** `su` не помогает: запуск от текущего пользователя. */
        NONE
    }

    /** Итог одной команды. */
    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    @Volatile
    private var probed: Mode? = null

    private val probeLock = Any()

    /** Найденная форма вызова; пробуется один раз на сессию. */
    fun mode(): Mode {
        probed?.let { return it }
        synchronized(probeLock) {
            probed?.let { return it }
            val found = runCatching {
                when {
                    asksRoot(listOf("su", "0", "sh", "-c", "id")) -> Mode.USER_ARG
                    asksRoot(listOf("su", "-c", "id")) -> Mode.FLAG_C
                    else -> Mode.NONE
                }
            }.getOrDefault(Mode.NONE)
            probed = found
            return found
        }
    }

    /** Есть ли работающая форма повышения прав. */
    fun available(): Boolean = mode() != Mode.NONE

    /**
     * Собирает argv для запуска [command] через `sh`.
     *
     * На десктопе без root команда идёт от текущего пользователя -- это не
     * обход, а честная попытка: если бинарю нужны права, он сам ответит
     * ошибкой, и она попадёт в журнал демона.
     */
    fun shellArgv(command: String): List<String> = when (mode()) {
        Mode.USER_ARG -> listOf("su", "0", "sh", "-c", command)
        Mode.FLAG_C -> listOf("su", "-c", command)
        Mode.NONE -> listOf("sh", "-c", command)
    }

    /**
     * Выполняет [command] и ждёт итога.
     *
     * Всё на IO-потоке вызывающего: метод блокирующий и зовётся только из
     * фоновых корутин. По таймауту процесс убивается, output -- всё, что
     * успели прочитать.
     */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val argv = shellArgv(command)
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(-1, e.message ?: e::class.simpleName.orEmpty())
        }
        // stdin закрывается сразу: su не должен ждать ввод пароля.
        runCatching { process.outputStream.close() }

        val collected = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine {
                    synchronized(collected) { collected.append(it).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = runCatching {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
        }
        runCatching { reader.join(READER_JOIN_MS) }

        val output = synchronized(collected) { collected.toString() }
        val code = if (finished) process.exitValue() else -1
        return Result(code, output)
    }

    /**
     * Ищет исполняемый файл по PATH и стандартным каталогам.
     *
     * Кандидат `/data/local/tmp` нужен Android: туда кладут бинари через
     * `adb push`, но каталога в PATH приложения нет.
     */
    fun which(name: String): String? {
        if (name.contains('/')) {
            return name.takeIf { runCatching { java.io.File(it).exists() }.getOrDefault(false) }
        }
        val dirs = (System.getenv("PATH") ?: "")
            .split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() } + EXTRA_DIRS
        return dirs.asSequence()
            .map { java.io.File(it, name) }
            .firstOrNull { runCatching { it.exists() }.getOrDefault(false) }
            ?.absolutePath
    }

    /**
     * Экранирует аргумент для `sh -c`.
     *
     * Без кавычек обойтись нельзя: пути и содержимое списков доменов
     * содержат пробелы, а одинарные кавычки -- в комментариях профиля.
     */
    fun quote(arg: String): String {
        if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in SAFE_CHARS }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /** Проба одной формы: поднимается ли до root именно так. */
    private fun asksRoot(argv: List<String>): Boolean {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return false
        }
        // Без закрытия stdin su может ждать пароль вечно.
        runCatching { process.outputStream.close() }

        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine {
                    synchronized(out) { out.append(it).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = runCatching {
            process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
        }
        runCatching { reader.join(READER_JOIN_MS) }

        val text = synchronized(out) { out.toString() }
        return finished && process.exitValue() == 0 && "uid=0" in text
    }

    /** Каталоги, куда `adb push` кладёт бинари, но которых нет в PATH. */
    private val EXTRA_DIRS = listOf("/data/local/tmp", "/system/bin", "/system/xbin", "/vendor/bin")

    private const val DEFAULT_TIMEOUT_MS = 5000L
    private const val PROBE_TIMEOUT_MS = 1500L
    private const val READER_JOIN_MS = 300L
    private val SAFE_CHARS = ".,_-=/:@+%~"
}
