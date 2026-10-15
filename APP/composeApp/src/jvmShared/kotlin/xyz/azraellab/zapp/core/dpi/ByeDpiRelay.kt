package xyz.azraellab.zapp.core.dpi

import kotlin.concurrent.Volatile
import xyz.azraellab.zapp.core.daemon.DpiOverlay
import xyz.azraellab.zapp.core.daemon.daemonLogcat
import xyz.azraellab.zapp.core.native.BinaryResolver
import java.util.concurrent.TimeUnit

/**
 * Боевой экземпляр byedpi: свой SOCKS5-прокси обхода DPI без прав (§130).
 *
 * byedpi не трогает ядро -- он рвёт запросы на уровне данных, как и
 * встроенный [SplitRelay], но арсенал шире: disorder, fake, tlsrec,
 * триггеры авто-режима. Поэтому он подменяет релей на том же порту
 * [DpiOverlay.RELAY_PORT], и конфиг туннеля не меняется ни на строку:
 * box и не знает, что под релеем теперь другой процесс.
 *
 * Жизненный цикл -- счётчик заимствований, как у [SplitRelay]: zapret и
 * goodbyedpi держат один процесс, последняя остановка его убивает.
 * Единственный сценарий, которого нет у релея (там всё в том же процессе,
 * что и приложение) -- смерть внешнего процесса: её ловит воркер демона
 * через [isAlive]/[diagnose] после состояния RUNNING.
 *
 * Запуск -- без шелла и без su: бинарь лежит в nativeLibraryDir и и так
 * исполняется от имени приложения, это и есть норут-запуск.
 */
internal object ByeDpiRelay {

    private const val TAG = "ZAPP-BYEDPI"

    /** Сколько последних строк вывода помнит для диагностики смерти. */
    private const val MEMORY_LINES = 3

    /** Сколько ждём, пока старый процесс отдаст сокет (SIGKILL асинхронен). */
    private const val REAP_WAIT_MS = 500L

    private var process: Process? = null
    private var refs = 0
    private var lastLines: List<String> = emptyList()

    /** Аргументы последнего успешного спавна -- для журнала демона. */
    @Volatile
    private var spawned: List<String> = emptyList()

    /**
     * Держит byedpi запущенным.
     *
     * null -- взяли или уже держали; текст -- причина отказа для карточки
     * демона (бинарь не найден, спавн не удался).
     */
    @Synchronized
    fun acquire(): String? {
        val existing = process
        if (existing != null && existing.isAlive) {
            refs++
            return null
        }
        // Мёртвый остаток: ждём, пока он отдаст сокет, иначе новый процесс
        // спавнится на ещё занятом порту и умирает сразу после bind.
        if (existing != null) reap(existing)
        val binary = BinaryResolver.resolve("byedpi")
            ?: return "byedpi binary not found"
        val args = DpiOverlay.byeDpi.toArgs(DpiOverlay.domains)
        val created = try {
            ProcessBuilder(listOf(binary) + args).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "byedpi spawn failed: ${e.message ?: e::class.simpleName.orEmpty()}"
        }
        runCatching { created.outputStream.close() }
        process = created
        // Ссылки снимаются несимметрично: демон, державший уже мёртвый
        // процесс, всё равно числится -- новая заимствование добавляет к
        // его доле, а не сбрасывает счётчик в ноль под ним.
        refs = maxOf(refs, 0) + 1
        lastLines = emptyList()
        spawned = args
        startReader(created)
        daemonLogcat(TAG, "started: ${(listOf("byedpi") + args).joinToString(" ")}")
        return null
    }

    /** Отпускает одну ссылку; на нуле процесс добивается. */
    @Synchronized
    fun release() {
        if (refs <= 0) return
        refs--
        if (refs > 0) return
        val p = process
        process = null
        if (p == null) return
        runCatching { p.destroyForcibly() }
        // Порт обязан освободиться до следующего bind: смена механизма
        // (релей <-> byedpi) поднимает нового слушателя на том же месте.
        // SIGKILL убивает почти мгновенно, граница -- для случая D-state.
        runCatching { p.waitFor(REAP_WAIT_MS, TimeUnit.MILLISECONDS) }
        daemonLogcat(TAG, "stopped")
    }

    /** Жив ли процесс: воркер демона следит за этим после RUNNING. */
    @Synchronized
    fun isAlive(): Boolean = process?.isAlive == true

    /** Что именно заспавнено -- подпись состояния RUNNING в журнале. */
    fun argsLine(): String = spawned.joinToString(" ")

    /**
     * Причина смерти для журнала: код выхода и хвост вывода.
     *
     * null -- процесс ещё жив или о нём ничего не известно: своя причина
     * у отказа («не отвечает на рукопожатие») честнее чужого кода выхода.
     * Зовётся до [release]: снятие ссылки обнуляет процесс.
     */
    @Synchronized
    fun diagnose(): String? {
        val p = process ?: return null
        if (p.isAlive) return null
        val code = runCatching { p.exitValue() }.getOrDefault(-1)
        val tail = lastLines.joinToString(" | ")
        return if (tail.isEmpty()) "byedpi exited (code $code)"
        else "byedpi exited (code $code): $tail"
    }

    /** Добивает остаток: ждёт немного, потом принудительно. */
    private fun reap(p: Process) {
        runCatching {
            if (!p.waitFor(REAP_WAIT_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
        process = null
    }

    /** Последние строки вывода -- для причины смерти в журнале. */
    @Synchronized
    private fun remember(line: String) {
        lastLines = (lastLines + line).takeLast(MEMORY_LINES)
    }

    /**
     * Читает stdout+stderr построчно: в logcat и в память диагностики.
     *
     * Поток заканчивается вместе с процессом -- на этом же строится
     * наблюдение воркера: вывод кончился, значит, демон кончился.
     */
    private fun startReader(p: Process) {
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isEmpty()) return@forEachLine
                    remember(line)
                    daemonLogcat(TAG, line)
                }
            }
        }, "zapp-bye-dpi-reader").apply {
            isDaemon = true
            start()
        }
    }
}
