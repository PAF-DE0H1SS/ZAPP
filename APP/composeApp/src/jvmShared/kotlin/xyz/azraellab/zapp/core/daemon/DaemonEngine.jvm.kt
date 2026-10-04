package xyz.azraellab.zapp.core.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.native.BinaryResolver
import xyz.azraellab.zapp.core.root.RootShell
import java.io.File

/** Метка logcat: одна на оба демона, различаются по префиксу строки. */
private const val TAG = "ZAPP-DAEMON"

actual fun createDaemonEngine(id: DaemonId): DaemonEngine = JvmDaemonEngine(id)

actual fun daemonLogcat(tag: String, line: String) {
    // Рефлексия вместо android.util.Log: класс лежит в общем JVM-слое,
    // и на десктопе его просто нет -- там вызов молча пропускается.
    runCatching {
        val log = Class.forName("android.util.Log")
        val method = log.getMethod("d", String::class.java, String::class.java)
        method.invoke(null, tag, line)
    }
}

/**
 * Запуск демонов на JVM.
 *
 * Модель одна для обеих платформ: демон -- дочерний процесс, за которым мы
 * следим живым/мёртвым, а его вывод идёт построчно в журнал. Права
 * поднимаются через [RootShell]: там же решается, нужен ли `su` и в какой
 * форме он вообще работает.
 *
 * Процесс живёт столько же, сколько приложение: при остановке он убивается
 * и по сигналу, и через `pkill` по пути к бинарю, чтобы не осталось сироты,
 * размножившейся от `sh -c`.
 */
private class JvmDaemonEngine(
    override val id: DaemonId
) : BaseDaemonEngine() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var resolvedBinary: String? = null

    private var monitorJob: Job? = null
    private var pkillJob: Job? = null

    override val supported: Boolean = true

    override fun start(spec: DaemonSpec) {
        scope.launch {
            // Перезапуск: старый процесс снимается до нового, иначе `pkill`
            // по пути к бинарю успел бы убить и свежий тоже.
            pkillJob?.cancel()
            reap()

            val binaryName = spec.binary.ifBlank { id.defaultBinary }
            val resolved = BinaryResolver.resolve(binaryName)
            if (resolved == null) {
                log(DaemonEvent(Str.DAEMON_NO_BINARY, arg = binaryName))
                setState(DaemonState.ERROR)
                return@launch
            }
            val args = materialize(spec) ?: run {
                // Не бинарь, а файлы: причины разные, и путать их в
                // журнале -- искать не ту поломку.
                log(DaemonEvent(Str.DAEMON_FILES_FAILED, arg = binaryName))
                setState(DaemonState.ERROR)
                return@launch
            }

            setState(DaemonState.STARTING)
            // Переменные окружения уходят префиксом команды, а не через
            // ProcessBuilder: команда может выполняться внутри `su -c`,
            // которое окружение родителя не сохраняет, а шелл-префикс
            // работает и там, и там.
            val envPrefix = spec.env.entries.joinToString("") { (key, value) ->
                "$key=${RootShell.quote(value)} "
            }
            val command = envPrefix + (listOf(resolved) + args).joinToString(" ") { RootShell.quote(it) }
            log(DaemonEvent(Str.DAEMON_LOG_STARTING, arg = command))

            val started = try {
                val argv = RootShell.shellArgv(command)
                val created = ProcessBuilder(argv).redirectErrorStream(true).start()
                // stdin закрыт: демону он не нужен, а открытый мог бы держать su.
                runCatching { created.outputStream.close() }
                created
            } catch (e: Exception) {
                log(DaemonEvent(raw = e.message ?: e::class.simpleName.orEmpty()))
                setState(DaemonState.ERROR)
                return@launch
            }

            process = started
            resolvedBinary = resolved
            startReader(started)
            monitor(started, resolved)
        }
    }

    override fun stop() {
        val wasActive = state.value == DaemonState.STARTING || state.value == DaemonState.RUNNING
        setState(DaemonState.STOPPED)
        if (wasActive) {
            log(DaemonEvent(Str.DAEMON_LOG_STOPPED))
        }
        // destroy с вызывающего потока -- быстрый, а pkill уходит в фон:
        // stop зовётся из UI, и блокировать его на su нельзя.
        process?.let { runCatching { it.destroyForcibly() } }
        process = null
        val binary = resolvedBinary
        resolvedBinary = null
        if (binary != null) {
            pkillJob = scope.launch { pkill(binary) }
        }
    }

    override fun onEvent(event: DaemonEvent) {
        val line = when {
            event.text != null -> "${event.text.name} ${event.arg}".trim()
            else -> event.raw
        }
        daemonLogcat(TAG, "${id.name}: $line")
    }

    /**
     * Читает stdout+stderr процесса построчно, пока поток не закроется.
     *
     * Две защиты, и обе обязательны. Пустые строки пропускаются:
     * [DaemonEvent] требует непустой payload, а исключение внутри
     * `forEachLine` закрывает reader -> закрывает pipe -> следующая же
     * запись демона -- SIGPIPE и смерть с кодом 141, которая в журнале
     * выглядит как «упал сам» и прячет настоящую ошибку. Перехват каждой
     * строки тем же защитным: читатель не имеет права ронять поток ни
     * при каком состоянии демона -- это его единственный канал вывода.
     */
    private fun startReader(process: Process) {
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isEmpty()) return@forEachLine
                    runCatching { log(DaemonEvent(raw = line)) }
                }
            }
        }.apply {
            isDaemon = true
            // Свойство id у Thread -- это Long потока, поэтому за именем
            // демона приходится заходить явно через контекст класса.
            name = "zapp-daemon-reader-${this@JvmDaemonEngine.id.name}"
            start()
        }
    }

    /**
     * Следит за жизнью процесса.
     *
     * «Запущен» -- не факт создания процесса, а полторы секунды жизни:
     * бинарь с несуществующей опцией успевает умереть сразу, и состояние
     * ERROR с кодом выхода честнее, чем мелькнувшее RUNNING.
     */
    private fun monitor(process: Process, binary: String) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var announced = false
            while (isActive) {
                delay(POLL_MS)
                if (!process.isAlive) {
                    val code = runCatching { process.exitValue() }.getOrDefault(-1)
                    if (state.value == DaemonState.STARTING || state.value == DaemonState.RUNNING) {
                        log(DaemonEvent(Str.DAEMON_LOG_EXIT, arg = code.toString()))
                        setState(DaemonState.ERROR)
                    }
                    return@launch
                }
                if (!announced && System.currentTimeMillis() - startedAt >= ALIVE_AFTER_MS) {
                    announced = true
                    setState(DaemonState.RUNNING)
                    log(DaemonEvent(Str.DAEMON_LOG_RUNNING, arg = binary))
                }
            }
        }
    }

    /**
     * Убивает процесс и любые остатки. Зовётся только из корутины.
     *
     * `destroy` убивает сам `su`: если тот породил дочерний `sh`, демон
     * переживёт родителя. Поэтому второй шаг -- `pkill` по пути к бинарю:
     * паттерн совпадает и с самим бинарем, и с его `sh -c`-обёрткой.
     */
    private fun reap() {
        monitorJob?.cancel()
        monitorJob = null
        process?.let { runCatching { it.destroyForcibly() } }
        process = null
        val binary = resolvedBinary
        resolvedBinary = null
        if (binary != null) pkill(binary)
    }

    private fun pkill(binary: String) {
        runCatching { RootShell.run("pkill -TERM -f ${RootShell.quote(binary)}", 2000) }
    }

    /**
     * Пишет файлы демона и подставляет пути в аргументы.
     *
     * Список доменов goodbyedpi читает из файла, и файл должен быть читаем
     * тем же пользователем, что и демон. При работающем `su` файлы ложатся в
     * `/data/local/tmp/zapp` (мир читает всех), без root -- во временный
     * каталог приложения. `null` -- записать не удалось, запускать бессмысленно.
     */
    private fun materialize(spec: DaemonSpec): List<String>? {
        if (spec.files.isEmpty()) return spec.args

        val substitutions = mutableMapOf<String, String>()
        if (RootShell.available()) {
            val dir = "/data/local/tmp/zapp/${id.name.lowercase()}"
            if (!RootShell.run("mkdir -p ${RootShell.quote(dir)}", 2000).ok) return null
            for ((name, content) in spec.files) {
                val path = "$dir/$name"
                val payload = java.util.Base64.getEncoder().encodeToString(content.toByteArray())
                val write = RootShell.run(
                    "echo $payload | base64 -d > ${RootShell.quote(path)}",
                    3000
                )
                if (!write.ok) return null
                substitutions[name] = path
            }
        } else {
            val dir = File(File(System.getProperty("java.io.tmpdir")), "zapp/${id.name.lowercase()}")
            if (!runCatching { dir.mkdirs() }.getOrDefault(false) && !dir.isDirectory) return null
            for ((name, content) in spec.files) {
                val file = File(dir, name)
                runCatching { file.writeText(content) }.getOrElse { return null }
                substitutions[name] = file.absolutePath
            }
        }

        return spec.args.map { arg ->
            substitutions.entries.fold(arg) { acc, (name, path) -> acc.replace("{{$name}}", path) }
        }
    }

    private companion object {
        const val POLL_MS = 500L
        const val ALIVE_AFTER_MS = 1500L
    }
}
