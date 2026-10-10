package xyz.azraellab.zapp.core.engine

import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.gps.GpsState

/**
 * Формальная фаза жизненного цикла -- общая ось для всех трёх движков.
 *
 * Состояния туннеля, демона и подмены живут в трёх enum'ах, но у них
 * общая форма: «ничего не происходит» -> «поднимается» -> «работает».
 * Фаза -- это место, где формы сходятся: проверки вида «живо ли сейчас»
 * выражаются одной фразой, а не перечислением состояний в каждом месте.
 *
 * Фазы RELEASING в нашей модели нет: остановка синхронна, наблюдаемой
 * «закрытой» фазы между работой и остановом не существует.
 */
enum class LifecyclePhase {

    /** Ничего не поднято (и ошибка -- тоже «нет активной сессии»). */
    INACTIVE,

    /** Поднимается: ещё не работает, но уже не выключено. */
    ESTABLISHING,

    /** Работает -- или работает и переподключается (туннель жив). */
    ACTIVE
}

/** Фаза туннеля: DISCONNECTED/ERROR -- покой, RECONNECTING -- активность. */
val TunnelState.phase: LifecyclePhase
    get() = when (this) {
        TunnelState.DISCONNECTED, TunnelState.ERROR -> LifecyclePhase.INACTIVE
        TunnelState.CONNECTING -> LifecyclePhase.ESTABLISHING
        TunnelState.CONNECTED, TunnelState.RECONNECTING -> LifecyclePhase.ACTIVE
    }

/**
 * Туннель занимает интерфейс: кнопка СТОП должна быть доступна, а
 * «не трогай настройки» распространяется и на переподключение.
 *
 * Через фазу, а не списком состояний: любое новое «живое» состояние
 * подключается одной строкой здесь, а не правкой каждого вызова.
 */
val TunnelState.isLive: Boolean
    get() = phase != LifecyclePhase.INACTIVE

/** Фаза демона: ERROR -- покой (запуск не удался, работать нечему). */
val DaemonState.phase: LifecyclePhase
    get() = when (this) {
        DaemonState.STOPPED, DaemonState.ERROR -> LifecyclePhase.INACTIVE
        DaemonState.STARTING -> LifecyclePhase.ESTABLISHING
        DaemonState.RUNNING -> LifecyclePhase.ACTIVE
    }

/** Фаза подмены координат. */
val GpsState.phase: LifecyclePhase
    get() = when (this) {
        GpsState.OFF, GpsState.ERROR -> LifecyclePhase.INACTIVE
        GpsState.STARTING -> LifecyclePhase.ESTABLISHING
        GpsState.ACTIVE -> LifecyclePhase.ACTIVE
    }
