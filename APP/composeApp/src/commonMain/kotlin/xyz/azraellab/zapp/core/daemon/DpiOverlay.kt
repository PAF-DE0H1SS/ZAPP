package xyz.azraellab.zapp.core.daemon

import kotlin.concurrent.Volatile
import xyz.azraellab.zapp.core.ByeDpiConfig
import xyz.azraellab.zapp.core.DpiBackend
import xyz.azraellab.zapp.core.probe.RelayTuning

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

    /**
     * Домены, сузившие область обхода (hostlist демонов).
     *
     * Пусто -- область не сузжена. Нужно для журнала: подпись «all tcp»
     * врала, когда обход ограничен списком доменов.
     */
    @Volatile
    var domains: List<String> = emptyList()
        private set

    /** Блокировать ли QUIC: заменяет udp-десинхронизацию отсутствием канала. */
    @Volatile
    var quicBlock: Boolean = false
        private set

    /**
     * Тюнинг разрыва в релее: пауза между сегментами и число разрезов.
     *
     * Присылается из AppConfig при каждом обновлении конфига -- автоподбор
     * стратегии пишет подобранные значения в конфиг, а релей читает их
     * здесь без своего жизненного цикла.
     */
    @Volatile
    var relay: RelayTuning = RelayTuning()
        private set

    /** Принимает свежий тюнинг из конфига (нормализация -- на приёме). */
    fun updateRelay(tuning: RelayTuning) {
        relay = tuning.normalized()
    }

    /**
     * Кто держит порт релея: встроенный релей или бинарь byedpi.
     *
     * Порт у обоих механизмов один -- конфиг туннеля не меняется, меняется
     * только ответственный процесс. Читают ветки и воркеры демонов при
     * старте, поэтому поля -- под `Volatile`, а не под свой lock.
     */
    @Volatile
    var backend: DpiBackend = DpiBackend.RELAY
        private set

    /** Настройки byedpi: читает сам бинарь при старте (аргументы строки). */
    @Volatile
    var byeDpi: ByeDpiConfig = ByeDpiConfig()
        private set

    /** Принимает свежий выбор механизма из конфига. */
    fun updateByeDpi(backend: DpiBackend, config: ByeDpiConfig) {
        this.backend = backend
        this.byeDpi = config
    }

    /**
     * Применяет свежие порты от демонов.
     *
     * Возвращает true, если состояние изменилось: вызывающий по этому
     * признаку переподнимает уже живую туннельную сессию, а молчаливые
     * повторы (демон дышит, конфиг тот же) переподключения не стоят.
     */
    fun update(tcp: Collection<String>, quicBlock: Boolean, hosts: Collection<String> = emptyList()): Boolean {
        val ports = tcp.toList()
        val hostlist = hosts.toList()
        val changed = active != ports.isNotEmpty() ||
            this.tcpPorts != ports ||
            this.domains != hostlist ||
            this.quicBlock != quicBlock
        this.tcpPorts = ports
        this.domains = hostlist
        this.quicBlock = quicBlock
        this.active = ports.isNotEmpty() || quicBlock
        return changed
    }

    /**
     * Подпись области обхода для журнала.
     *
     * Портов нет -- трафик через релей не идёт вовсе, и раньше подпись
     * «all tcp» врала об этом. Домены сузили область -- они тоже
     * показываются, иначе по строке журнала нельзя понять, что обходится.
     */
    fun scopeLabel(): String = when {
        tcpPorts.isEmpty() && domains.isEmpty() -> "nothing routed"
        tcpPorts.isEmpty() -> "domains only: " + domains.joinToString(", ")
        domains.isEmpty() -> tcpPorts.joinToString(", ")
        else -> tcpPorts.joinToString(", ") + " for " + domains.joinToString(", ")
    }
}
