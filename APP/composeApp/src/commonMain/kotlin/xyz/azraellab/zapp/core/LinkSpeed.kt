package xyz.azraellab.zapp.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Скорость туннеля, байт в секунду.
 *
 * [downBps] -- данные к приложению, [upBps] -- от приложения. Направление
 * одинаково для всех интерфейсов: rx счётчика -- это то, что вошло в
 * стек ядра (приём), tx -- то, что ушло из него (передача). Для tun это
 * значит: box пишет в tun полученные из сети пакеты -- это rx, то есть
 * download; чтение из tun -- исходящий трафик приложений, tx, то есть
 * upload. Менять стороны местами не нужно.
 */
data class LinkSpeed(
    val downBps: Long,
    val upBps: Long
)

/**
 * Общий канал скорости: публикует опрос в [AppState], читают плашка
 * статуса и уведомление сервиса.
 *
 * Отдельный объект, а не StateFlow в AppState: уведомление живёт в
 * Android-сервисе, у которого нет доступа к экранному состоянию, а
 * потеря скорости при разрыве означает «скорость неизвестна» -- null.
 */
object LinkSpeedBus {
    private val _current = MutableStateFlow<LinkSpeed?>(null)
    val current: StateFlow<LinkSpeed?> = _current.asStateFlow()

    fun publish(speed: LinkSpeed?) {
        _current.value = speed
    }
}

/**
 * Скорость туннеля из двух срезов счётчиков; null, если считать нечего.
 *
 * Чистая функция: два среза -- весь результат. Берутся только интерфейсы
 * туннеля (остальной трафик -- не туннель и в плашку не попадает),
 * уменьшение счётчика считается нулём (пересозданный интерфейс), а
 * первый вызов с пустой базой не выдаёт ложный всплеск.
 */
fun linkSpeedBetween(prev: TrafficSample, current: TrafficSample): LinkSpeed? {
    val elapsedSec = ((current.timestampMs - prev.timestampMs) / 1000.0)
        .coerceAtLeast(0.001)
    var downBytes = 0.0
    var upBytes = 0.0
    // Разные «нет данных»: туннеля в срезе нет вовсе -- плашке показывать
    // нечего (null); туннель есть, но интерфейс появился в этом же срезе
    // (нет базы) -- считаем ноль, а не прячем плашку.
    var tunnelSeen = false
    var baselineSeen = false
    for (iface in current.interfaces) {
        if (!iface.isTunnelSpeedInterface()) continue
        tunnelSeen = true
        val before = prev.interfaceByName(iface.name) ?: continue
        baselineSeen = true
        val rx = (iface.rxBytes - before.rxBytes).coerceAtLeast(0L)
        val tx = (iface.txBytes - before.txBytes).coerceAtLeast(0L)
        // rx -- приём в стек ядра (download), tx -- передача из него
        // (upload); для tun, wg и z* соглашение одинаково.
        downBytes += rx
        upBytes += tx
    }
    if (!tunnelSeen) return null
    if (!baselineSeen) return LinkSpeed(0L, 0L)
    return LinkSpeed(
        downBps = (downBytes / elapsedSec).toLong(),
        upBps = (upBytes / elapsedSec).toLong()
    )
}

/**
 * Интерфейс туннеля для плашки скорости.
 *
 * [InterfaceCounters.tunnelled] помечает служебные интерфейсы, но туда
 * не входит `z*` -- так называет свои kernel-WG интерфейсы сам движок,
 * и их скорость прятать нельзя. `lo` исключён: локальный трафик -- не
 * туннель.
 */
fun InterfaceCounters.isTunnelSpeedInterface(): Boolean {
    if (name == "lo") return false
    return tunnelled || (name.startsWith("z") && name.length in 2..15)
}

/**
 * Человекочитаемая скорость: 512 B/s, 1.5 KB/s, 12.0 MB/s.
 *
 * Целые байты и килобайты без дробной части: «512 B/s» привычнее, чем
 * «512.0 B/s»; с килобайта и выше дробная часть нужна -- иначе тихий
 * трафик выглядит как ноль.
 */
fun formatSpeed(bytesPerSecond: Long): String {
    val value = bytesPerSecond.coerceAtLeast(0L)
    return when {
        value < 1024L -> "$value B/s"
        value < 1024L * 1024L -> trimZero(value / 1024.0) + " KB/s"
        value < 1024L * 1024L * 1024L -> trimZero(value / (1024.0 * 1024.0)) + " MB/s"
        else -> trimZero(value / (1024.0 * 1024.0 * 1024.0)) + " GB/s"
    }
}

/** Один знак после запятой, хвостовой ноль отбрасывается: 1.0 -> 1, 1.5 -> 1.5. */
private fun trimZero(value: Double): String {
    val rounded = (value * 10.0).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

/** Строка плашки: «↓ 1.5 MB/s  ↑ 340 KB/s». */
fun LinkSpeed.toDisplayString(): String =
    "↓ ${formatSpeed(downBps)}  ↑ ${formatSpeed(upBps)}"
