package xyz.azraellab.zapp.core.daemon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.azraellab.zapp.core.Str

/** Какой именно демон запускается: у каждого свой каталог файлов. */
enum class DaemonId(val defaultBinary: String) {
    /** Правила обхода DPI поверх nfqueue. */
    ZAPRET("zapret"),

    /** Рукопожатия поверх nfqueue. */
    GOODBYEDPI("goodbyedpi"),

    /**
     * Туннель: sing-box (в том числе amnezia-box) или openvpn.
     *
     * Один id на все туннельные бэкенды: у них общая жизненная схема
     * (длинноживущий процесс, журнал строк, остановка по pkill), а
     * различается только бинарь и аргументы, -- то есть ровно то, что уже
     * умеет [DaemonSpec].
     */
    TUNNEL("box")
}

/** Состояние демона. */
enum class DaemonState {
    /** Не запущен. */
    STOPPED,

    /** Процесс создан, ещё не подтверждён живой. */
    STARTING,

    /** Процесс жив и отдаёт вывод. */
    RUNNING,

    /** Запустить не удалось или процесс умер; причина -- в журнале. */
    ERROR
}

/**
 * Строка журнала.
 *
 * Два источника в одном типе: события приложения хранят ключ [Str] и
 * переводятся в UI, а вывод самого демона идёт как есть -- это технический
 * текст, и переводить его нельзя. Ровно один из [text]/[raw] заполнен.
 */
data class DaemonEvent(
    val text: Str? = null,
    val raw: String = "",
    val arg: String = ""
) {
    init {
        require(text != null || raw.isNotEmpty()) { "пустое событие демона" }
    }
}

/**
 * Что запустить.
 *
 * [files] -- имена файлов с содержимым (списки доменов): демону они нужны
 * путями, а пути появляются только на устройстве. Поэтому аргументы могут
 * ссылаться на файл как `{{имя}}`, и движок подставит абсолютный путь перед
 * запуском.
 */
data class DaemonSpec(
    /** Путь к бинарю; пусто -- имя из [DaemonId.defaultBinary] в PATH. */
    val binary: String = "",
    val args: List<String> = emptyList(),
    val files: Map<String, String> = emptyMap(),

    /**
     * Дополнительные переменные окружения процесса.
     *
     * Нужны для передачи fd туннеля: Android не даёт бинарю открыть
     * `/dev/net/tun`, поэтому VpnService создаёт интерфейс сам и номер
     * готового дескриптора уходит демону через `ZAPP_TUN_FD`.
     */
    val env: Map<String, String> = emptyMap()
)

/**
 * Движок демона.
 *
 * Общий код знает состояния и журнал; запуск процесса живёт в actual-
 * реализации. Движок сам проверяет бинарь и сам поднимает права -- вызывающий
 * передаёт только команду.
 */
interface DaemonEngine {

    val id: DaemonId

    /** Есть ли на платформе способ запускать процессы. */
    val supported: Boolean

    val state: StateFlow<DaemonState>

    /** Журнал: новые в конце, ограничен буфером. */
    val log: StateFlow<List<DaemonEvent>>

    /** Запускает демон; повторный вызов перезапускает его с новой командой. */
    fun start(spec: DaemonSpec)

    /** Останавливает демон. Без ошибок, даже если он и не запускался. */
    fun stop()

    /**
     * Добавляет событие в журнал, не меняя состояние.
     *
     * Нужен вызывающему коду: проверки до запуска (нет домена для Fake SNI)
     * возникают там, где отдельного движка нет.
     */
    fun note(event: DaemonEvent)
}

/** Базовая реализация с буфером журнала и общим состоянием. */
abstract class BaseDaemonEngine : DaemonEngine {

    private val _state = MutableStateFlow(DaemonState.STOPPED)
    override val state: StateFlow<DaemonState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<DaemonEvent>>(emptyList())
    override val log: StateFlow<List<DaemonEvent>> = _log.asStateFlow()

    override fun note(event: DaemonEvent) = log(event)

    protected fun setState(value: DaemonState) {
        _state.value = value
    }

    protected fun log(event: DaemonEvent) {
        val next = _log.value + event
        _log.value = if (next.size > LOG_LIMIT) next.takeLast(LOG_LIMIT) else next
        onEvent(event)
    }

    /**
     * Второй канал события мимо UI-журнала.
     *
     * Android дублирует строки в logcat, чтобы причину было видно, даже
     * когда экран с журналом закрыт, -- тот же приём, что у GPS-движка.
     */
    protected open fun onEvent(event: DaemonEvent) = Unit

    private companion object {
        const val LOG_LIMIT = 200
    }
}

/** Создаёт движок демона для текущей платформы. */
expect fun createDaemonEngine(id: DaemonId): DaemonEngine

/** Пишет строку журнала в системный лог платформы; на десктопе -- тишина. */
expect fun daemonLogcat(tag: String, line: String)
