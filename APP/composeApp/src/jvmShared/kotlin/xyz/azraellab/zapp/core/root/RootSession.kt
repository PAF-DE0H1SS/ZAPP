package xyz.azraellab.zapp.core.root

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Абстракция «живой shell»: пишем команды, читаем готовые строки. */
interface ShellChannel {

    /** Отправляет строку; false -- канал мёртв. */
    fun send(line: String): Boolean

    /** Читает строку; null -- поток закрыт. Блокирующий. */
    fun readLine(): String?

    /** Закрывает канал. */
    fun close()
}

/**
 * Персистентная root-сессия: один процесс `su`, команды пишутся в его stdin,
 * итог читается до сентинела.
 *
 * Дешевле и надёжнее `su -c` на каждую команду при частых вызовах (наблюдение
 * за туннелем, автоперезапуск демонов): процесс поднимается один раз, дальше
 * шлём только текст команды. Каждая команда сопровождается сентинелом с кодом
 * выхода, поэтому вывод читается ровно до границы, а смена nonce защищает от
 * «хвостов» предыдущей команды.
 *
 * Канал инжектируется ([open]), поэтому рамка (команда + сентинел) и цикл
 * жизни (переиспользование/пересоздание) тестируются без реального root.
 */
class RootSession(private val open: () -> ShellChannel?) {

    private val lock = Any()

    private var channel: ShellChannel? = null

    private var lines: LinkedBlockingQueue<String>? = null

    /** Выполняет [command] через живой канал; сбой -- пересоздание канала. */
    fun run(command: String, timeoutMs: Long = TIMEOUT_MS): RootShell.Result {
        synchronized(lock) {
            val ch = ensureChannel() ?: return RootShell.Result(UNAVAILABLE, "session unavailable")
            val mark = "ZAPP_R${System.nanoTime()}"
            if (!ch.send("$command; echo \"$mark \$?\"")) {
                reset()
                return RootShell.Result(UNAVAILABLE, "session write failed")
            }
            val out = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    reset()
                    return RootShell.Result(TIMEOUT, out.toString().trimEnd('\n'))
                }
                val line = lines!!.poll(remaining, TimeUnit.MILLISECONDS)
                if (line == null) {
                    reset()
                    return RootShell.Result(TIMEOUT, out.toString().trimEnd('\n'))
                }
                if (line.startsWith(mark)) {
                    val code = line.removePrefix(mark).trim().toIntOrNull() ?: -1
                    return RootShell.Result(code, out.toString().trimEnd('\n'))
                }
                out.append(line).append('\n')
            }
        }
    }

    /** Закрывает канал (выход, смена режима, сброс). */
    fun close() = synchronized(lock) { reset() }

    private fun ensureChannel(): ShellChannel? {
        channel?.let { return it }
        val ch = open() ?: return null
        val queue = LinkedBlockingQueue<String>()
        Thread {
            runCatching {
                while (!Thread.currentThread().isInterrupted) {
                    val line = ch.readLine() ?: break
                    queue.offer(line)
                }
            }
        }.apply {
            isDaemon = true
            name = "zapp-root-session"
            start()
        }
        channel = ch
        lines = queue
        return ch
    }

    private fun reset() {
        runCatching { channel?.close() }
        channel = null
        lines = null
    }

    companion object {
        private const val TIMEOUT_MS = 5000L

        /** Канал не открылся: нет рабочего root-шелла. */
        const val UNAVAILABLE = -2

        /** Команда не уложилась в таймаут; канал пересоздан. */
        const val TIMEOUT = -1
    }
}

/** Открывает живой root-шелл под найденную форму [RootShell.Mode]. */
fun openRootChannel(mode: RootShell.Mode): ShellChannel? {
    val argv = when (mode) {
        RootShell.Mode.USER_ARG -> listOf("su", "0", "sh")
        RootShell.Mode.FLAG_C -> listOf("su", "-c", "sh")
        RootShell.Mode.NONE -> listOf("sh")
    }
    return runCatching {
        ProcessChannel(ProcessBuilder(argv).redirectErrorStream(true).start())
    }.getOrNull()
}

/** Канал поверх долгоживущего процесса: пишем в stdin, читаем stdout. */
private class ProcessChannel(private val process: Process) : ShellChannel {

    private val writer = process.outputStream.bufferedWriter()

    private val reader = process.inputStream.bufferedReader()

    override fun send(line: String): Boolean = runCatching {
        writer.write(line)
        writer.newLine()
        writer.flush()
        true
    }.getOrDefault(false)

    override fun readLine(): String? = runCatching { reader.readLine() }.getOrNull()

    override fun close() {
        runCatching { process.destroyForcibly() }
    }
}
