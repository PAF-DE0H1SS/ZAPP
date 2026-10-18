package xyz.azraellab.zapp.core.traffic

import xyz.azraellab.zapp.core.InterfaceCounters
import xyz.azraellab.zapp.core.TrafficSample
import xyz.azraellab.zapp.core.net.ClashTraffic

/**
 * Стоит ли брать clash_api вместо интерфейсных счётчиков.
 *
 * Настоящие счётчики (native/proc/sysfs/su, живой TrafficStats) точнее:
 * они видят весь трафик устройства. Но фолбэк netd помечен
 * [InterfaceCounters.estimated]: на части прошивок он мёртв при живом
 * трафике (netd не видит туннель, бакеты ленивые), и тогда единственный
 * честный источник -- сам box: он владеет tun и знает свою скорость.
 */
fun preferClash(sample: TrafficSample?): Boolean =
    sample == null || sample.interfaces.isEmpty() || sample.interfaces.all { it.estimated }

/**
 * Мост скоростей clash_api в накопленные счётчики для метера.
 *
 * `/traffic` sing-box отдаёт скорость (дельта Total() за последнюю
 * секунду), а метер считает дельты накопленных байт. Поэтому скорость
 * интегрируется во «виртуальный» счётчик `zapp-box`: оценка для графика,
 * но живая -- в отличие от мёртвых netd-бакетов.
 */
class ClashBridge {

    private var rxBytes = 0L
    private var txBytes = 0L

    /** Момент прошлого среза; null -- база, прироста ещё не было. */
    private var lastMs: Long? = null

    /** Новый срез: приращивает оценку и отдаёт её метеру. */
    fun sample(clash: ClashTraffic, nowMs: Long): TrafficSample {
        val previous = lastMs
        if (previous != null) {
            val seconds = (nowMs - previous).coerceAtLeast(0L) / 1000.0
            rxBytes += (clash.down * seconds).toLong()
            txBytes += (clash.up * seconds).toLong()
        }
        lastMs = nowMs
        return TrafficSample(
            timestampMs = nowMs,
            interfaces = listOf(
                InterfaceCounters(name = "zapp-box", rxBytes = rxBytes, txBytes = txBytes, tunnelled = true)
            )
        )
    }
}
