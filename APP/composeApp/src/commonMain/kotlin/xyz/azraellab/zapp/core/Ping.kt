package xyz.azraellab.zapp.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Пинг активного подключения, миллисекунды.
 *
 * [null] -- подключений нет, коннект не выбран или замерить нечего
 * (UDP-проба молчит -- отклик измерить не в чем). Та же причина
 * разделения, что и у [LinkSpeedBus]: уведомление живёт в сервисе без
 * доступа к экранному состоянию, а пропавший замер -- это «пинга нет»,
 * а не «0 ms».
 *
 * Проба идёт из приложения, чей трафик Android уводит мимо туннеля
 * (uidrange-дырка вокруг VPN-owner uid), поэтому пинг меряет путь до
 * сервера коннекта напрямую -- ровно то, что решает «быстрый ли этот
 * узел». Стоя в туннеле мерить нечего: собственные сокеты в него и так
 * не попадают.
 */
object PingBus {
    private val _current = MutableStateFlow<Int?>(null)
    val current: StateFlow<Int?> = _current.asStateFlow()

    fun publish(ms: Int?) {
        _current.value = ms
    }
}
