package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.native.ZappNative
import xyz.azraellab.zapp.core.root.RootShell

/**
 * NFQUEUE-правила на iptables (общий JVM-слой: Android и десктоп).
 *
 * Две стратегии в одном enable: холодный старт (цепочки нет или она
 * схлопнулась после краша) -- полная пересборка скриптом, живая
 * цепочка -- точечный reconcile: прочитать факт, сверить с желанием
 * и двигать по одной мутации. Один su-вызов на операцию; сам текст
 * правил живёт в общем слое (DpiScript.kt) -- там он проверяется
 * тестами без устройства.
 */
actual object DpiFirewall {

    private const val TIMEOUT_MS = 15_000L

    /** Таймаут одной мутации: строка ставится быстрее, чем пачка. */
    private const val MUTATION_TIMEOUT_MS = 5_000L

    actual fun enable(
        tcpPorts: List<String>,
        udpPorts: List<String>,
        uids: List<Int>,
        excludeUids: List<Int>
    ) {
        if (!RootShell.available()) return
        val desired = dpiFirewallRuleLines(tcpPorts, udpPorts, uids, excludeUids)
        val prefixLen = dpiFirewallPrefixLen(desired)
        val actual = readChain()
        if (actual == null || actual.take(prefixLen) != desired.take(prefixLen)) {
            // Цепочки нет, не прочиталась или префикс (локальные сети,
            // исключения) нарушен: полная пересборка -- она же
            // восстанавливает порядок, который точечная сверка не даёт.
            RootShell.run(dpiFirewallScript(tcpPorts, udpPorts, uids, excludeUids), TIMEOUT_MS)
            return
        }
        // Живая цепочка: сверка идёт по одной мутации; проваленные
        // повторяются следующим проходом, удачные не откатываются
        // из-за чужой ошибки.
        var failed = applyPlan(planOf(desired, actual)) { runMutation(it) }
        if (failed.isNotEmpty()) {
            failed = applyPlan(failed) { runMutation(it) }
            if (failed.isNotEmpty()) {
                AppLog.log("dpi", "reconcile: ${failed.size} rules still failing")
            }
        }
        // Прыжок OUTPUT -- часть желаемого состояния: старые снимаются
        // все, один ставится заново (идемпотентно и дёшево).
        fixJumps()
    }

    actual fun disable() {
        if (!RootShell.available()) return
        RootShell.run(
            buildString {
                repeat(8) {
                    appendLine("iptables -w -t mangle -D OUTPUT -j ZAPP_DPI 2>/dev/null || true")
                }
                appendLine("iptables -w -t mangle -F ZAPP_DPI 2>/dev/null || true")
                appendLine("iptables -w -t mangle -X ZAPP_DPI 2>/dev/null || true")
            },
            TIMEOUT_MS
        )
    }

    /**
     * Сверка желаемого и фактического: план решает Rust-ядро через
     * JNI-мост, а при не поднявшейся библиотеке -- та же логика на
     * Kotlin (Reconcile.kt). Путь выбирается здесь, чтобы вызывающий
     * код не знал о языках под собой.
     */
    private fun planOf(desired: List<String>, actual: List<String>): List<Mutation> =
        ZappNative.plan(desired, actual) ?: reconcilePlan(desired, actual)

    /**
     * Тела правил живой цепочки; null -- цепочки нет или она не читается.
     *
     * Разбор по умолчанию -- нативный (C++); строковый фолбэк дублирует
     * его правило в правило и нужен, когда библиотека не поднялась.
     */
    private fun readChain(): List<String>? {
        val result = RootShell.run("iptables -w -t mangle -S ZAPP_DPI", TIMEOUT_MS)
        if (!result.ok) return null
        return ZappNative.parseChain(result.output) ?: parseChainLines(result.output)
    }

    /** Фолбэк разбора: тот же контракт, что у C++ ParseChainRules. */
    private fun parseChainLines(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-A ZAPP_DPI") }
            .map { it.removePrefix("-A ZAPP_DPI").trim() }
            .filter { it.isNotEmpty() }
            .toList()

    private fun runMutation(m: Mutation): Boolean =
        RootShell.run(
            "iptables -w -t mangle ${if (m.add) "-A" else "-D"} ZAPP_DPI ${m.rule}",
            MUTATION_TIMEOUT_MS
        ).ok

    private fun fixJumps() {
        RootShell.run(
            buildString {
                repeat(8) {
                    appendLine("iptables -w -t mangle -D OUTPUT -j ZAPP_DPI 2>/dev/null || true")
                }
                appendLine("iptables -w -t mangle -A OUTPUT -j ZAPP_DPI")
            },
            TIMEOUT_MS
        )
    }
}
