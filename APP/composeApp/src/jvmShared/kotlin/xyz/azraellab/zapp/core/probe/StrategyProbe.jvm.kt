package xyz.azraellab.zapp.core.probe

import kotlinx.coroutines.delay
import xyz.azraellab.zapp.core.AutoStrategy
import xyz.azraellab.zapp.core.BuildMode
import xyz.azraellab.zapp.core.GoodbyeDpiConfig
import xyz.azraellab.zapp.core.ZapretConfig
import xyz.azraellab.zapp.core.daemon.daemonLogcat
import xyz.azraellab.zapp.core.dpi.SplitRelayServer
import xyz.azraellab.zapp.core.native.BinaryResolver
import xyz.azraellab.zapp.core.root.RootShell

/**
 * JVM-оркестрация перебора: изолированные инстансы и сокетные пробы.
 *
 * Три пути, и выбор между ними -- не по системе, а по реальным правам:
 * root-сборка с работающим su поднимает пробный nfqws в своей очереди,
 * а если root нет (noroot-сборка или десктоп), весь обход -- это уровень
 * данных локального релея, и кандидаты становятся параметрами его
 * разрыва. GoodbyeDpi в root идёт отдельно: у него нет своего номера
 * очереди, поэтому боевой демон на время пробы останавливается хуками.
 */
actual object StrategyProbe {

    /** Номер очереди пробного nfqws: боевой слушает 0, пробный -- 1. */
    private const val PROBE_QUEUE = 1

    /** Готовность инстанса: nfqws/goodbyedpi встают синхронно, хватает полусекунды. */
    private const val STARTUP_DELAY_MS = 500L

    private const val TAG = "ZAPP-PROBE"

    actual suspend fun run(
        target: String,
        zapret: ZapretConfig,
        goodbyeDpi: GoodbyeDpiConfig,
        relay: RelayTuning,
        hooks: ProbeHooks
    ): StrategyProbeOutcome? {
        // Обе половины нужны и разного: !rootAllowed -- про сборку (в норуте
        // реле -- единственный путь в принципе), !available -- про устройство
        // (в рут-сборке без работающего su остаётся тот же рельеф). Правило
        // целиком -- в KDoc `BuildMode` (E9).
        val useRelay = !BuildMode.rootAllowed || !RootShell.available()
        val candidates = when {
            useRelay -> relayCandidates(relay)
            target == "zapret" -> zapretCandidates(zapret.strategy)
            else -> goodbyeDpiCandidates(goodbyeDpi.mode)
        }
        if (candidates.isEmpty()) return null
        if (!StrategyProbeCoordinator.begin(candidates.size)) return null
        return try {
            val notes = mutableListOf<String>()
            val outcome = when {
                useRelay -> runRelay(candidates, target, notes)
                target == "zapret" -> runZapretRoot(candidates, zapret, notes)
                else -> runGdpRoot(candidates, goodbyeDpi, hooks, notes)
            }
            daemonLogcat(TAG, "done target=$target outcome=${outcome?.candidate?.id ?: "none"} notes=$notes")
            outcome
        } catch (e: Exception) {
            // Проба не должна падать в UI: сбой инстанса -- это тоже итог.
            daemonLogcat(TAG, "run failed: ${e.message ?: e::class.simpleName.orEmpty()}")
            null
        } finally {
            StrategyProbeCoordinator.finish()
        }
    }

    /**
     * Root-путь zapret: пробный nfqws в очереди 1 + uid-правило.
     *
     * Правило вставляется позицией 1 в OUTPUT и шлёт только трафик
     * приложения на 443/tcp+udp в пробную очередь: боевой демон (если
     * запущен) продолжает слушать свою цепочку и не мешает. После
     * кандидата процесс и правило снимаются -- изоляция строго временная.
     */
    private suspend fun runZapretRoot(
        candidates: List<ProbeCandidate>,
        base: ZapretConfig,
        notes: MutableList<String>
    ): StrategyProbeOutcome? {
        val uid = appUid()
        val best = Best()
        for ((i, candidate) in candidates.withIndex()) {
            StrategyProbeCoordinator.tick(i, candidate)
            val config = AutoStrategy.applyZapret(
                base,
                AutoStrategy.Decision(candidate.id, "probe")
            )
            val rule = uid >= 0 && installQueueRule(uid, PROBE_QUEUE)
            val instance = ProbeInstance("nfqws")
            try {
                if (!instance.start("nfqws", config.toArgs(queueNum = PROBE_QUEUE))) {
                    notes += "${candidate.id}: instance not started"
                    continue
                }
                delay(STARTUP_DELAY_MS)
                if (!instance.alive()) {
                    notes += "${candidate.id}: instance died"
                    continue
                }
                best.offer(candidate, ProbeSockets.runDirect().score())
            } finally {
                instance.stop()
                if (rule) removeQueueRule(uid, PROBE_QUEUE)
            }
        }
        return best.toOutcome("zapret", notes)
    }

    /**
     * Root-путь goodbyedpi: очередь 0, боевой демон на время -- под хуками.
     *
     * У goodbyedpi нет флага очереди -- он всегда 0, поэтому параллельно
     * боевому не поднимешь: основной останавливается до перебора и
     * возвращается после (победителя применит вызывающая сторона).
     */
    private suspend fun runGdpRoot(
        candidates: List<ProbeCandidate>,
        base: GoodbyeDpiConfig,
        hooks: ProbeHooks,
        notes: MutableList<String>
    ): StrategyProbeOutcome? {
        val wasRunning = hooks.dpiRunning()
        if (wasRunning) hooks.stopDpi()
        val uid = appUid()
        val rule = uid >= 0 && installQueueRule(uid, 0)
        val best = Best()
        try {
            for ((i, candidate) in candidates.withIndex()) {
                StrategyProbeCoordinator.tick(i, candidate)
                val config = base.copy(mode = candidate.id)
                val instance = ProbeInstance("goodbyedpi")
                try {
                    if (!instance.start("goodbyedpi", config.toArgs())) {
                        notes += "${candidate.id}: instance not started"
                        continue
                    }
                    delay(STARTUP_DELAY_MS)
                    if (!instance.alive()) {
                        notes += "${candidate.id}: instance died"
                        continue
                    }
                    best.offer(candidate, ProbeSockets.runDirect().score())
                } finally {
                    instance.stop()
                }
            }
        } finally {
            if (rule) removeQueueRule(uid, 0)
            if (wasRunning) hooks.startDpi()
        }
        return best.toOutcome("gdp", notes)
    }

    /**
     * Noroot-путь: свой SOCKS-релей на свободном порту, кандидаты --
     * параметры разрыва.
     *
     * Боевой релей (если поднят) не трогается: пробный живёт на
     * ephemeral-порту и проверяет ровно то, что собирается применить --
     * разрез ClientHello здесь и есть весь обход без NFQUEUE.
     */
    private suspend fun runRelay(
        candidates: List<ProbeCandidate>,
        target: String,
        notes: MutableList<String>
    ): StrategyProbeOutcome? {
        val holder = TuningHolder()
        val server = SplitRelayServer(0) { holder.value }
        server.start()?.let { error ->
            notes += error
            return null
        }
        val best = Best()
        try {
            for ((i, candidate) in candidates.withIndex()) {
                StrategyProbeCoordinator.tick(i, candidate)
                holder.value = RelayTuning.of(candidate.id.removePrefix("relay:")) ?: RelayTuning()
                best.offer(candidate, ProbeSockets.runViaSocks(server.localPort).score())
            }
        } finally {
            server.stop()
        }
        val winner = best.candidate ?: return null
        return StrategyProbeOutcome(
            target = target,
            candidate = winner,
            score = best.score,
            noData = best.score?.successes == 0,
            strategyCode = null,
            relay = RelayTuning.of(winner.id.removePrefix("relay:")),
            notes = notes
        )
    }

    /**
     * Собирает итог из лучшего кандидата.
     *
     * null -- ни один инстанс не дал данных вообще (ни одного handshake):
     * применять нечего, и это честнее, чем «выбрать первого попавшегося».
     */
    private fun Best.toOutcome(target: String, notes: MutableList<String>): StrategyProbeOutcome? {
        val winner = candidate ?: return null
        return StrategyProbeOutcome(
            target = target,
            candidate = winner,
            score = score,
            noData = score?.successes == 0,
            strategyCode = winner.id,
            relay = null,
            notes = notes
        )
    }

    /** Лучший по [CandidateScore.isBetterThan]; ведётся по ходу перебора. */
    private class Best {
        var candidate: ProbeCandidate? = null
            private set
        var score: CandidateScore? = null
            private set

        fun offer(candidate: ProbeCandidate, score: CandidateScore) {
            if (score.isBetterThan(this.score)) {
                this.candidate = candidate
                this.score = score
            }
        }
    }

    /** Текущий тюнинг релея: лямбда сервера не может захватить var. */
    private class TuningHolder {
        @Volatile
        var value: RelayTuning = RelayTuning()
    }

    /**
     * Пробный инстанс бинаря: свой процесс, свой вывод в журнал.
     *
     * По образцу демонов, но без состояния и монитора: пробе нужна одна
     * проверка -- жив ли процесс после старта, и надёжное снятие вместе
     * с pkill (кандидатов несколько, сирота следующую пробу исказит).
     */
    private class ProbeInstance(private val label: String) {

        private var process: Process? = null
        private var resolvedBinary: String? = null

        fun start(binaryName: String, args: List<String>): Boolean {
            val resolved = BinaryResolver.resolve(binaryName) ?: return false
            val command = (listOf(resolved) + args).joinToString(" ") { RootShell.quote(it) }
            return try {
                val argv = RootShell.shellArgv(command)
                val created = ProcessBuilder(argv).redirectErrorStream(true).start()
                runCatching { created.outputStream.close() }
                readOutput(created)
                process = created
                resolvedBinary = resolved
                true
            } catch (e: Exception) {
                daemonLogcat(TAG, "$label start: ${e.message ?: e::class.simpleName.orEmpty()}")
                false
            }
        }

        fun alive(): Boolean = process?.isAlive == true

        fun stop() {
            process?.let { runCatching { it.destroyForcibly() } }
            process = null
            val binary = resolvedBinary
            resolvedBinary = null
            if (binary != null) {
                runCatching { RootShell.run("pkill -TERM -f ${RootShell.quote(binary)}", 2000) }
            }
        }

        /**
         * Читает вывод, пока процесс жив.
         *
         * Обязателен: непрочитанный pipe забивается -- nfqws встаёт на
         * write, а проба молча уходит в таймаут. Пустые строки -- в пустоту,
         * как у демонов.
         */
        private fun readOutput(process: Process) {
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotEmpty()) {
                            runCatching { daemonLogcat(TAG, "$label: $line") }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "zapp-probe-reader-$label"
                start()
            }
        }
    }

    /**
     * UID приложения для owner-правила iptables.
     *
     * Рефлексия вместо android.os.Process: класс лежит в общем JVM-слое,
     * на десктопе его нет, но десктоп в root-пути и не бывает.
     */
    private fun appUid(): Int = runCatching {
        Class.forName("android.os.Process").getMethod("myUid").invoke(null) as Int
    }.getOrDefault(-1)

    /** Ставит uid-правило OUTPUT -> NFQUEUE для 443/tcp+udp. */
    private fun installQueueRule(uid: Int, queue: Int): Boolean {
        var ok = true
        for (proto in listOf("tcp", "udp")) {
            val result = RootShell.run(
                "iptables -w -t mangle -I OUTPUT 1 -m owner --uid-owner $uid " +
                    "-p $proto --dport 443 -j NFQUEUE --queue-num $queue --queue-bypass",
                3000
            )
            if (!result.ok) {
                ok = false
                daemonLogcat(TAG, "rule install failed: ${result.output.ifBlank { "exit ${result.code}" }}")
            }
        }
        return ok
    }

    /** Снимает то, что ставил [installQueueRule]; двойной вызов безопасен. */
    private fun removeQueueRule(uid: Int, queue: Int) {
        for (proto in listOf("tcp", "udp")) {
            RootShell.run(
                "iptables -w -t mangle -D OUTPUT -m owner --uid-owner $uid " +
                    "-p $proto --dport 443 -j NFQUEUE --queue-num $queue --queue-bypass",
                3000
            )
        }
    }
}
