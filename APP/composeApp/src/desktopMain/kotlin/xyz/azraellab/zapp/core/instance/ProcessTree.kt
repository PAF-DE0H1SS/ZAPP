package xyz.azraellab.zapp.core.instance

/**
 * Добивает процессное дерево при выходе -- десктопный эквивалент job object.
 *
 * Демоны (box, zapret, byeDpi) -- дочерние процессы приложения: без этой
 * зачистки они переживают родителя, продолжают держать порты и туннель, а
 * следующий запуск падает на «адрес уже занят». Вызывается из shutdown-хука
 * после [xyz.azraellab.zapp.core.AppState.dispose]: dispose гасит то, что
 * умеет остановиться штатно, дерево -- страховка для всего остального.
 *
 * Сначала TERM (шанс закрыть сокеты и выгрузиться), по истечении [graceMs]
 * уцелевшим -- KILL. [includeRoot] = false -- добиваем только потомков:
 * в shutdown-хуке корень это сам JVM, который и так завершается.
 */
fun killProcessTree(
    root: ProcessHandle,
    graceMs: Long = DEFAULT_GRACE_MS,
    includeRoot: Boolean = true
) {
    val handles = mutableListOf<ProcessHandle>()
    runCatching { root.descendants().forEach { handles += it } }
    if (includeRoot) handles += root
    if (handles.isEmpty()) return

    handles.forEach { runCatching { it.destroy() } }
    if (graceMs > 0) {
        val deadline = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < deadline && handles.any { alive(it) }) {
            runCatching { Thread.sleep(POLL_MS) }
        }
    }
    handles.forEach { if (alive(it)) runCatching { it.destroyForcibly() } }
    // SIGKILL доставляется асинхронно, а JVM вычитывает зомби не мгновенно:
    // без ожидания вызывающий увидит ещё живой процесс.
    val settle = System.currentTimeMillis() + SETTLE_MS
    while (System.currentTimeMillis() < settle && handles.any { alive(it) }) {
        runCatching { Thread.sleep(POLL_MS) }
    }
}

private fun alive(handle: ProcessHandle): Boolean = runCatching { handle.isAlive }.getOrDefault(false)

/** Сколько ждать TERM перед KILL. */
const val DEFAULT_GRACE_MS: Long = 500L

private const val POLL_MS: Long = 25L

/** Сколько ждать, пока KILL доставится и зомби вычитается. */
private const val SETTLE_MS: Long = 600L
