package xyz.azraellab.zapp.core.daemon

import kotlin.concurrent.Volatile

/**
 * Состояние userland-обхода DPI для сборок без root.
 *
 * С root обход -- это NFQUEUE-правила в ядре ([DpiFirewall]), и ядро
 * знает о них только при пересборке iptables. Без root NFQUEUE
 * untrusted_app'у недоступен, поэтому обход живёт в конфиге туннеля:
 * `SingboxConfig` читает отсюда порты и добавляет правила маршрутов, а
 * по трафику идёт локальный релей с разрывом ClientHello ([SplitRelay]).
 *
 * Обновляется только из AppState для noroot-сборки; в root-сборке объект
 * остаётся неактивным, и генератор конфига не добавляет лишних правил --
 * поведение root-билда не меняется ни на строку.
 */
object DpiOverlay {

    /** Локальный порт SOCKS5-релея, на который box отдаёт TCP-порты обхода. */
    const val RELAY_PORT = 19050

    /** Тег outbound'а релея в конфиге sing-box. */
    const val RELAY_TAG = "zapp-relay"

    /** Есть ли сейчас активный обход (жив хотя бы один демон). */
    @Volatile
    var active: Boolean = false
        private set

    /** TCP-порты, трафик которых идёт через релей (из фильтров демонов). */
    @Volatile
    var tcpPorts: List<String> = emptyList()
        private set

    /** Блокировать ли QUIC: заменяет udp-десинхронизацию отсутствием канала. */
    @Volatile
    var quicBlock: Boolean = false
        private set

    /**
     * Применяет свежие порты от демонов.
     *
     * Возвращает true, если состояние изменилось: вызывающий по этому
     * признаку переподнимает уже живую туннельную сессию, а молчаливые
     * повторы (демон дышит, конфиг тот же) переподключения не стоят.
     */
    fun update(tcp: Collection<String>, quicBlock: Boolean): Boolean {
        val ports = tcp.toList()
        val changed = active != ports.isNotEmpty() ||
            this.tcpPorts != ports ||
            this.quicBlock != quicBlock
        this.tcpPorts = ports
        this.quicBlock = quicBlock
        this.active = ports.isNotEmpty() || quicBlock
        return changed
    }
}
