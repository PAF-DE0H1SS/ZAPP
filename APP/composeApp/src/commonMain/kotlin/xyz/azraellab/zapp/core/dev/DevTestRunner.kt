package xyz.azraellab.zapp.core.dev

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.log.platformNowMs

/** Результат одного теста. */
data class DevTestResult(
    val test: DevTest,
    val ok: Boolean,
    /** Короткое сообщение для UI: первая строка исключения. */
    val message: String,
    /** Полный стек для лога: подробности нужны только в файле. */
    val trace: String,
    val durationMs: Long
)

/** Состояние прогона. */
sealed interface DevRunState {
    /** Ещё не запускалось. */
    data object Idle : DevRunState

    /** Идёт: [done] уже выполнено из [total], [currentId] -- текущий тест. */
    data class Running(val done: Int, val total: Int, val currentId: String) : DevRunState

    /** Завершён: [logPath] -- основной лог (записан автоматически). */
    data class Done(
        val results: List<DevTestResult>,
        val totalMs: Long,
        val startedAtMs: Long,
        val logPath: String?,
        val logText: String
    ) : DevRunState
}

/**
 * Раннер тестов: гоняет список последовательно на [Dispatchers.Default].
 *
 * Последовательно -- потому что тесты быстрые (сотни микросекунд каждый) и
 * детерминированные: параллелизм здесь купил бы только гонки за лог-файл и
 * перепутанный прогресс, а ничего не ускорил бы заметно.
 *
 * Живёт вне композиции, состояние -- [StateFlow]: UI подписывается, раннер
 * пишет из фонового потока. Scope приходит снаружи -- при уходе со страницы
 * прогон честно прерывается вместе с ней.
 */
class DevTestRunner {

    private val _state = MutableStateFlow<DevRunState>(DevRunState.Idle)
    val state: StateFlow<DevRunState> = _state.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Запускает прогон; false -- уже идёт или список пуст. */
    fun run(tests: List<DevTest>, scope: CoroutineScope): Boolean {
        if (tests.isEmpty() || isRunning) return false
        job = scope.launch(Dispatchers.Default) { execute(tests) }
        return true
    }

    /**
     * Сохраняет копию лога уже завершённого прогона: основной файл
     * `zapp-tests.log` перезаписывается следующим прогоном, а копия --
     * с меткой времени -- остаётся.
     */
    fun saveCopy(done: DevRunState.Done): String? =
        writeDevTestLog("zapp-tests-${done.startedAtMs}.log", done.logText)

    private suspend fun execute(tests: List<DevTest>) {
        val started = platformNowMs()
        val results = ArrayList<DevTestResult>(tests.size)
        _state.value = DevRunState.Running(0, tests.size, "")

        for (test in tests) {
            _state.value = DevRunState.Running(results.size, tests.size, test.id)
            val t0 = platformNowMs()
            val result = try {
                test.body()
                DevTestResult(test, true, "", "", platformNowMs() - t0)
            } catch (e: Throwable) {
                val message = e.message?.lineSequence()?.firstOrNull()
                    ?: e::class.simpleName ?: "error"
                val trace = e.stackTraceToString()
                DevTestResult(test, false, message, trace, platformNowMs() - t0)
            }
            results += result
        }

        val totalMs = platformNowMs() - started
        val log = buildLog(results, totalMs, started)
        val path = writeDevTestLog("zapp-tests.log", log)

        val failed = results.count { !it.ok }
        AppLog.log(
            "devtests",
            "run ${results.size} tests: ${results.size - failed} ok, " +
                "$failed failed, $totalMs ms"
        )
        _state.value = DevRunState.Done(results, totalMs, started, path, log)
    }

    /**
     * Полный отчёт: шапка, строка на каждый тест, хвост со сводкой.
     *
     * Формат -- один тест на строку с префиксом `[OK]`/`[FAIL]`: по такому
     * логу можно грепать (`grep FAIL zapp-tests.log`) и читать глазами,
     * а провалы дополнительно несут стек -- отдельный разбор устройства
     * для этого не нужен.
     */
    private fun buildLog(results: List<DevTestResult>, totalMs: Long, startedAtMs: Long): String {
        val failed = results.count { !it.ok }
        return buildString {
            appendLine("ZAPP dev tests")
            appendLine("started=$startedAtMs duration=${totalMs}ms")
            appendLine("-".repeat(60))
            results.forEach { r ->
                if (r.ok) {
                    appendLine("[OK]   ${r.test.id} (${r.durationMs} ms)")
                } else {
                    appendLine("[FAIL] ${r.test.id} (${r.durationMs} ms): ${r.message}")
                    r.trace.lineSequence().forEach { appendLine("    $it") }
                }
            }
            appendLine("-".repeat(60))
            appendLine(
                "total=${results.size} ok=${results.size - failed} " +
                    "failed=$failed duration=${totalMs}ms"
            )
            if (failed > 0) {
                appendLine("failed ids:")
                results.filter { !it.ok }.forEach {
                    appendLine("  ${it.test.id}: ${it.message}")
                }
            }
        }
    }
}
