package xyz.azraellab.zapp.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние туннеля. */
enum class TunnelState {
    /** Туннель не поднят. */
    DISCONNECTED,

    /** Система поднимает интерфейс, идёт запрос разрешения или настройка маршрутов. */
    CONNECTING,

    /** Интерфейс поднят, трафик идёт через приложение. */
    CONNECTED,

    /** Не получилось поднять или туннель упал; причина -- в логе. */
    ERROR
}

/**
 * Движок подключения.
 *
 * Один интерфейс на обе платформы: общий код знает только состояния и лог,
 * а то, как поднимается tun-интерфейс, живёт в actual-реализации. Так UI
 * одинаково показывает статус и причины отказа, где бы туннель ни работал.
 */
interface TunnelEngine {

    /** Есть ли на платформе способ поднять туннель. Ложь прячет кнопку подключения. */
    val supported: Boolean

    val state: StateFlow<TunnelState>

    /** Последние строки лога: newest в конце. Ограничены буфером. */
    val log: StateFlow<List<String>>

    /**
     * Передаёт настройки туннеля.
     *
     * Движок не читает хранилище сам: общий код владеет конфигом и здесь же
     * сообщает ему изменения, поэтому к моменту [connect] профиль уже свежий.
     */
    fun applyConfig(config: xyz.azraellab.zapp.core.VpnConfig)

    /** Поднимает туннель по настройкам, уже переданным через [applyConfig]. */
    fun connect()

    /** Снимает туннель. Без ошибок, даже если он и не поднимался. */
    fun disconnect()
}

/** Базовая реализация с буфером лога: actual-классы добавляют только работу с системой. */
abstract class BaseTunnelEngine : TunnelEngine {

    private val _state = MutableStateFlow(TunnelState.DISCONNECTED)
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    override val log: StateFlow<List<String>> = _log.asStateFlow()

    protected fun setState(value: TunnelState) {
        _state.value = value
    }

    protected fun log(line: String) {
        val next = _log.value + line
        _log.value = if (next.size > LOG_LIMIT) next.takeLast(LOG_LIMIT) else next
    }

    protected fun clearLog() {
        _log.value = emptyList()
    }

    private companion object {
        const val LOG_LIMIT = 200
    }
}

/** Создаёт движок подключения для текущей платформы. */
expect fun createTunnelEngine(): TunnelEngine
