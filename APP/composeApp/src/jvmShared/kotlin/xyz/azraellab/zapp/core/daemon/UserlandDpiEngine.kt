package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.dpi.SplitRelay

/**
 * Обход DPI без root: не процесс с NFQUEUE, а userland-релей.
 *
 * nfqws и goodbyedpi живут только с правами (netlink и NFQUEUE --
 * запрещены untrusted_app'у), поэтому в noroot-сборке тот же DaemonSpec
 * никуда не ведёт: бинарь не нужен, нужен поднятый туннель, чей конфиг
 * отдаёт целевые порты на [SplitRelay]. Движок в состоянии отражает
 * доступность релея -- «Работает» означает «релей держит порт», и
 * карточка демона, и автоперезапуск остаются прежними.
 */
internal class UserlandDpiEngine(
    override val id: DaemonId
) : BaseDaemonEngine() {

    override val supported: Boolean = true

    override fun start(spec: DaemonSpec) {
        // Перезапуск: ссылка на старый релей снимается до новой заимствования.
        if (state.value == DaemonState.STARTING || state.value == DaemonState.RUNNING) {
            stop()
        }
        setState(DaemonState.STARTING)
        log(DaemonEvent(raw = "no root: nfqueue unavailable -- userland relay 127.0.0.1:${DpiOverlay.RELAY_PORT}"))
        val error = SplitRelay.acquire()
        if (error != null) {
            log(DaemonEvent(raw = error))
            setState(DaemonState.ERROR)
            return
        }
        setState(DaemonState.RUNNING)
        log(DaemonEvent(raw = "relay running (split clienthello on ${DpiOverlay.tcpPorts.joinToString(", ").ifEmpty { "all tcp" }})"))
    }

    override fun stop() {
        val wasActive = state.value == DaemonState.STARTING || state.value == DaemonState.RUNNING
        setState(DaemonState.STOPPED)
        if (wasActive) {
            log(DaemonEvent(Str.DAEMON_LOG_STOPPED))
            SplitRelay.release()
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
