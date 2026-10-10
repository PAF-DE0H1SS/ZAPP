package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.dpi.SplitRelay
import xyz.azraellab.zapp.core.probe.socks5Ready

/**
 * Обход DPI без root: не процесс с NFQUEUE, а userland-релей.
 *
 * nfqws и goodbyedpi живут только с правами (netlink и NFQUEUE --
 * запрещены untrusted_app'у), поэтому в noroot-сборке тот же DaemonSpec
 * никуда не ведёт: бинарь не нужен, нужен поднятый туннель, чей конфиг
 * отдаёт целевые порты на [SplitRelay]. Движок в состоянии отражает
 * доступность релея -- «Работает» означает «релей держит порт», и
 * карточка демона, и автоперезапуск остаются прежними.
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
     * заимствованную ссылку на релей сам.
     */
    private val lock = Any()
    private var generation = 0

    override fun start(spec: DaemonSpec) {
        val gen: Int
        synchronized(lock) {
            // Перезапуск: ссылка на старый релей снимается до новой заимствования.
            if (state.value == DaemonState.STARTING || state.value == DaemonState.RUNNING) {
                stopLocked()
            }
            gen = ++generation
            setState(DaemonState.STARTING)
            log(DaemonEvent(raw = "no root: nfqueue unavailable -- userland relay 127.0.0.1:${DpiOverlay.RELAY_PORT}"))
        }
        Thread({ probe(gen) }, "zapp-userland-dpi").apply {
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
        if (prev == DaemonState.RUNNING) SplitRelay.release()
    }

    /**
     * Воркер одного запуска: acquire -> рукопожатие -> RUNNING либо ERROR.
     *
     * Любая ошибка -- текст для журнала; отставшее поколение молчит,
     * но баланс заимствований сохраняет: что взял -- то и снимает.
     */
    private fun probe(gen: Int) {
        runCatching {
            val error = SplitRelay.acquire()
            if (error != null) {
                finish(gen, error)
                return
            }
            // Готовность -- рукопожатие, а не занятый порт: слушать умеет
            // что угодно, а работать -- только отвечающий по протоколу сервер.
            // Приветствие не отвечает -- снимаем ссылку и честно ERROR.
            if (!socks5Ready("127.0.0.1", DpiOverlay.RELAY_PORT)) {
                SplitRelay.release()
                finish(gen, "relay did not answer socks greeting")
                return
            }
            val fresh = synchronized(lock) {
                if (gen != generation) return@synchronized false
                setState(DaemonState.RUNNING)
                log(DaemonEvent(raw = "relay running (split clienthello on ${DpiOverlay.tcpPorts.joinToString(", ").ifEmpty { "all tcp" }})"))
                true
            }
            // Пока пробовали, старт отменили -- чужую ссылку снимаем сами.
            if (!fresh) SplitRelay.release()
        }.onFailure { e ->
            finish(gen, e.message?.ifBlank { null } ?: e::class.simpleName.orEmpty())
        }
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
