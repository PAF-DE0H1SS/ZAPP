package xyz.azraellab.zapp.core.net

import xyz.azraellab.zapp.core.AutoStrategy

/** Результат одной проверки с её идентификатором из списка шагов. */
data class ProbeStepResult(
    val id: String,
    val result: AutoStrategy.ProbeResult,
    val detail: String = ""
)

/**
 * Итог живой проверки сети.
 *
 * [probe] -- то, что понимает алгоритм выбора стратегии, [steps] -- то, что
 * показывается человеку по шагам, [notes] -- технические пояснения для
 * журнала (почему проверка прервалась, какой хост не ответил).
 */
data class StrategyReport(
    val probe: AutoStrategy.Probe,
    val steps: List<ProbeStepResult>,
    val notes: List<String>
) {
    fun step(id: String): AutoStrategy.ProbeResult =
        steps.firstOrNull { it.id == id }?.result ?: AutoStrategy.ProbeResult.Unknown
}

/**
 * Живая проверка сети для автоподбора стратегии.
 *
 * expect/actual по той же причине, что и проверка ссылок: замеры -- это
 * сокеты и TLS, доступные только на JVM, а сами гипотезы и их смысл --
 * общий код страниц.
 */
expect object NetworkProbe {

    /**
     * Прогоняет проверки и собирает отчёт.
     *
     * Никогда не бросает: недоступная сеть -- это валидный отчёт с
     * неизвестными шагами, а не исключение в UI.
     */
    suspend fun run(): StrategyReport
}
