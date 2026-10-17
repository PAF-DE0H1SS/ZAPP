package xyz.azraellab.zapp.core.connections

import xyz.azraellab.zapp.core.ProcessCmd
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket

/**
 * Rust-демон монитора соединений и его protobuf-IPC.
 *
 * Демон (native/mon, бинарь zappmon) сам читает /proc -- без спавна
 * ss/lsof на каждый опрос -- и шлёт снимки по TCP loopback; номер порта
 * печатает первой строкой stdout, поэтому клиент подключается без
 * файлов и гонок за свободный порт. Первое письмо -- Hello: демон молча
 * закрывает чужих держателей порта.
 *
 * Бинарь ищется по цепочке: свойство zapp.mon (упаковка и тесты),
 * каталог приложения, native/out (разработка), PATH. Не найден --
 * вызывающий честно остаётся на опросном пути.
 */
internal object ZappMon {

    private const val BINARY = "zappmon"

    /** Путь к бинарю; null -- нигде не найден. */
    fun binary(): String? {
        System.getProperty("zapp.mon")?.let { path ->
            if (runCatching { File(path).canExecute() }.getOrDefault(false)) return path
        }
        val appDir = System.getProperty("jpackage.app-path")
            ?.let { path -> File(path).parentFile }
        val arch = when (val raw = System.getProperty("os.arch").orEmpty()) {
            "amd64" -> "x86_64"
            "aarch64" -> "aarch64"
            else -> raw
        }
        val candidates = listOfNotNull(
            appDir?.let { File(it, BINARY) },
            File("native/out/$arch/$BINARY"),
            File("../native/out/$arch/$BINARY")
        )
        candidates.firstOrNull { runCatching { it.canExecute() }.getOrDefault(false) }
            ?.let { return it.absolutePath }
        return which(BINARY)
    }

    /** `which` системными средствами; null -- в PATH нет. */
    private fun which(name: String): String? {
        val result = runCatching { ProcessCmd.run(listOf("which", name)) }.getOrNull() ?: return null
        val path = result.output.lineSequence().firstOrNull()?.trim().orEmpty()
        return path.takeIf { result.ok && runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    /**
     * Подключение к демону: спавн, порт из stdout, Hello.
     *
     * null -- бинарь не найден, демон не поднялся или закрылся. Каждая
     * стадия снимает процесс сразу: сирота на порту была бы видна только
     * через минуты.
     */
    fun connect(): MonSession? {
        val binary = binary() ?: return null
        val process = runCatching {
            ProcessBuilder(binary).redirectErrorStream(false).start()
        }.getOrNull() ?: return null
        val port = runCatching {
            BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine() }
        }.getOrNull()?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            process.destroyForcibly()
            return null
        }
        val socket = runCatching {
            val socket = Socket("127.0.0.1", port)
            socket.tcpNoDelay = true
            socket
        }.getOrNull() ?: run {
            process.destroyForcibly()
            return null
        }
        val session = runCatching { MonSession(socket, process) }.getOrNull() ?: run {
            process.destroyForcibly()
            return null
        }
        // JVM не убивает дочерние процессы сама: без хука сирота
        // пережила бы закрытый экран соединений и само приложение.
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching { process.destroyForcibly() } }
        )
        return session
    }
}

/**
 * Живое подключение к демону.
 *
 * [readSnapshot] -- блокирующее чтение одного снимка с таймаутом:
 * «демон молчит» -- это тоже ответ, и вызывающий откатывается на
 * опросный путь. Сессия снимает демона при закрытии.
 */
internal class MonSession(
    private val socket: Socket,
    private val process: Process?
) : java.io.Closeable {

    private val input = socket.getInputStream()

    init {
        // Hello обязателен и в рамке, как и все письма: демон молча
        // закрывает чужих держателей порта. Сокет на отказе снимается
        // сразу -- висячее соединение на порту демона выглядело бы как
        // живой клиент.
        runCatching {
            MonWire.writeFrame(socket.getOutputStream(), MonWire.hello())
        }.onFailure {
            runCatching { socket.close() }
            throw IOException("zappmon: hello failed: ${it.message ?: it::class.simpleName.orEmpty()}")
        }
    }

    /** Один снимок; IOException по обрыву, SocketTimeoutException по таймауту. */
    fun readSnapshot(timeoutMs: Int): MonSnapshot {
        socket.soTimeout = timeoutMs
        return MonWire.snapshot(MonWire.readFrame(input))
            ?: throw IOException("zappmon: bad frame")
    }

    override fun close() {
        runCatching { socket.close() }
        process?.let { runCatching { it.destroyForcibly() } }
    }
}
