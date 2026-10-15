package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.DpiBackend
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.dpi.ByeDpiRelay
import xyz.azraellab.zapp.core.dpi.SplitRelay
import xyz.azraellab.zapp.core.probe.socks5Ready

/**
 * Обход DPI без root: не процесс с NFQUEUE, а userland-механизм (§130).
 *
 * nfqws и goodbyedpi живут только с правами (netlink и NFQUEUE --
 * запрещены untrusted_app'у), поэтому в noroot-сборке тот же DaemonSpec
 * никуда не ведёт: бинарь не нужен, нужен поднятый туннель, чей конфиг
 * отдаёт целевые порты на локальный SOCKS-порт [DpiOverlay.RELAY_PORT].
 * Кто его держит -- решает [DpiOverlay.backend]:
 *
 * - [DpiBackend.RELAY] -- встроенный [SplitRelay] с разрывом ClientHello;
 * - [DpiBackend.BYEDPI] -- отдельный процесс [ByeDpiRelay] c более широким
 *   набором стратегий, подменяющий релей на том же порту.
 *
 * Движок в состоянии отражает доступность механизма: «Работает» означает
 * «порт отвечает по протоколу», и карточка демона, и автоперезапуск
 * остаются прежними. Ссылку держит поле [held]: что взял при старте --
 * то снимает при остановке, даже если выбор механизма за это время
 * успел смениться.
 *
 * Проба готовности блокируется до 1.5 с, а старт приходит из UI-потока,
 * поэтому рукопожатие живёт в отдельном воркере: главный поток свободен
 * с первой миллисекунды, и старт-намерение VPN-сервиса не задерживается.
 */
internal class UserlandDpiEngine(
    override val id: DaemonId
) : BaseDaemonEngine() {

    override val supported: Boolean = true

    /**
     * Поколение запуска под замком: воркер, чей номер устарел (стоп или
     * новый старт), не имеет права трогать состояние и обязан снять свою
     * заимствованную ссылку на механизм сам.
     */
    private val lock = Any()
    private var generation = 0

    /**
     * Какой механизм держится этим поколением; снимается тот же самый.
     *
     * Без этого поля остановка после смены конфига снимала бы уже новый
     * бэкенд, а старый остался бы висеть на порту до конца сессии.
     */
    private var held: DpiBackend? = null

    override fun start(spec: DaemonSpec) {
        val backend = DpiOverlay.backend
        val gen: Int
        synchronized(lock) {
            // Перезапуск: ссылка на старый механизм снимается до новой заимствования.
            if (state.value == DaemonState.STARTING || state.value == DaemonState.RUNNING) {
                stopLocked()
            }
            gen = ++generation
            setState(DaemonState.STARTING)
            // Причина (нет nfqueue) остаётся, формулировка -- без слова про
            // root: строка видна в журнале и в норут-сборке, где права
            // и так не запрашиваются. Для byedpi вместо релея -- процесс.
            log(DaemonEvent(raw = when (backend) {
                DpiBackend.RELAY ->
                    "nfqueue unavailable -- userland relay 127.0.0.1:${DpiOverlay.RELAY_PORT}"
                DpiBackend.BYEDPI ->
                    "byedpi backend -- local process on 127.0.0.1:${DpiOverlay.RELAY_PORT}"
            }))
        }
        Thread({ probe(gen, backend) }, "zapp-userland-dpi").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** Остановка под замком [lock]; поколение инвалидирует воркера. */
    private fun stopLocked() {
        val prev = state.value
        generation++
        setState(DaemonState.STOPPED)
        if (prev != DaemonState.STARTING && prev != DaemonState.RUNNING) return
        log(DaemonEvent(Str.DAEMON_LOG_STOPPED))
        // RUNNING: заимствование держится уже здесь, снимаем сами.
        // STARTING: ссылку может держать воркер в полёте -- он увидит
        // устаревшее поколение после пробы и снимет её сам.
        if (prev == DaemonState.RUNNING) releaseHeld()
    }

    /**
     * Воркер одного запуска: acquire -> рукопожатие -> RUNNING либо ERROR.
     *
     * Любая ошибка -- текст для журнала; отставшее поколение молчит,
     * но баланс заимствований сохраняет: что взял -- то и снимает.
     */
    private fun probe(gen: Int, backend: DpiBackend) {
        runCatching {
            // Списки и порты пишет AppState реактивно на само состояние
            // демона -- успеть раньше воркера нельзя, а без доменов -H
            // уходит пустым и byedpi обходит шире, чем просили. Ждём
            // свежего DpiOverlay: обычно один тик, по краю -- полсекунды.
            if (backend == DpiBackend.BYEDPI && !awaitOverlay(gen)) return
            val error = when (backend) {
                DpiBackend.RELAY -> SplitRelay.acquire()
                DpiBackend.BYEDPI -> ByeDpiRelay.acquire()
            }
            if (error != null) {
                finish(gen, error)
                return
            }
            // Готовность -- рукопожатие, а не занятый порт: слушать умеет
            // что угодно, а работать -- только отвечающий по протоколу сервер.
            // Приветствие не отвечает -- снимаем ссылку и честно ERROR.
            if (!socks5Ready("127.0.0.1", DpiOverlay.RELAY_PORT)) {
                // Для byedpi причина смерти -- до снятия ссылки: release
                // обнуляет процесс, и diagnose после него уже ничего не знает.
                val why = when (backend) {
                    DpiBackend.RELAY -> "relay did not answer socks greeting"
                    DpiBackend.BYEDPI ->
                        ByeDpiRelay.diagnose() ?: "byedpi did not answer socks greeting"
                }
                release(backend)
                finish(gen, why)
                return
            }
            val fresh = synchronized(lock) {
                if (gen != generation) return@synchronized false
                held = backend
                setState(DaemonState.RUNNING)
                // Подпись области -- честная: раньше пустые порты
                // подписывались «all tcp», хотя через релей в таком
                // случае не шло ни байта (C7).
                log(DaemonEvent(raw = when (backend) {
                    DpiBackend.RELAY -> "relay running (split clienthello on ${DpiOverlay.scopeLabel()})"
                    DpiBackend.BYEDPI -> "byedpi running (${ByeDpiRelay.argsLine()} on ${DpiOverlay.scopeLabel()})"
                }))
                true
            }
            // Пока пробовали, старт отменили -- чужую ссылку снимаем сами.
            if (!fresh) {
                release(backend)
                return
            }
            // У релея смерть исключена -- он в том же процессе, что и
            // приложение. Внешний процесс byedpi может упасть сам, и без
            // наблюдения карточка висела бы «Работает», пока туннель
            // стучится в закрытый порт.
            if (backend == DpiBackend.BYEDPI) watchDeath(gen)
        }.onFailure { e ->
            finish(gen, e.message?.ifBlank { null } ?: e::class.simpleName.orEmpty())
        }
    }

    /**
     * Следит за жизнью byedpi после RUNNING.
     *
     * Смерть снимает ссылку (счётчик сходится к нулю и процесс добивается)
     * и роняет состояние в ERROR -- дальше включается обычный
     * автоперезапуск, чей acquire поднимет процесс заново.
     */
    private fun watchDeath(gen: Int) {
        Thread({
            while (true) {
                Thread.sleep(DEATH_POLL_MS)
                val alive = synchronized(lock) {
                    if (gen == generation) ByeDpiRelay.isAlive() else false
                }
                // Поколение устарело (стоп/перезапуск) -- наблюдатель выходит:
                // новый цикл запустит и новое наблюдение.
                if (gen != generation) return@Thread
                if (alive) continue
                synchronized(lock) {
                    if (gen == generation) {
                        val why = ByeDpiRelay.diagnose() ?: "byedpi exited"
                        releaseHeld()
                        log(DaemonEvent(raw = why))
                        setState(DaemonState.ERROR)
                    }
                }
                return@Thread
            }
        }, "zapp-bye-dpi-watch").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Ждёт, пока AppState раскроет свежие порты и домены для демона.
     *
     * false -- поколение устарело (стоп/перезапуск), воркер обязан выйти,
     * ничего не взяв. true -- данные готовы либо так и остались пустыми:
     * пустые порты означают, что через механизм всё равно ничего не пойдёт,
     * и -H без доменов там ни при чём.
     */
    private fun awaitOverlay(gen: Int): Boolean {
        val deadline = System.currentTimeMillis() + OVERLAY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) { if (gen != generation) return false }
            if (DpiOverlay.tcpPorts.isNotEmpty() || DpiOverlay.domains.isNotEmpty()) return true
            Thread.sleep(OVERLAY_POLL_MS)
        }
        return true
    }

    /** Снимает заимствование текущего механизма; вызывается под [lock]. */
    private fun releaseHeld() {
        held?.let { release(it) }
        held = null
    }

    private fun release(backend: DpiBackend) = when (backend) {
        DpiBackend.RELAY -> SplitRelay.release()
        DpiBackend.BYEDPI -> ByeDpiRelay.release()
    }

    /** Пишет причину и ставит ERROR; устаревшее поколение -- тишина. */
    private fun finish(gen: Int, error: String) {
        synchronized(lock) {
            if (gen != generation) return
            log(DaemonEvent(raw = error))
            setState(DaemonState.ERROR)
        }
    }

    override fun onEvent(event: DaemonEvent) {
        val line = when {
            event.text != null -> "${event.text.name} ${event.arg}".trim()
            else -> event.raw
        }
        daemonLogcat("ZAPP-DAEMON", "${id.name}: $line")
    }
}

/** Период проверки живости byedpi после перехода в RUNNING. */
private const val DEATH_POLL_MS = 1_500L

/** Сколько ждём свежий DpiOverlay от реактивного обновления AppState. */
private const val OVERLAY_WAIT_MS = 500L
private const val OVERLAY_POLL_MS = 50L
