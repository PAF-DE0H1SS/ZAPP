package xyz.azraellab.zapp.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.TorBridges
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.daemon.DaemonEngine
import xyz.azraellab.zapp.core.daemon.DaemonEvent
import xyz.azraellab.zapp.core.daemon.DaemonId
import xyz.azraellab.zapp.core.daemon.DaemonSpec
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.daemon.createDaemonEngine
import xyz.azraellab.zapp.core.log.errorEvent
import xyz.azraellab.zapp.core.log.stripAnsi
import xyz.azraellab.zapp.core.net.DnsFailover
import xyz.azraellab.zapp.core.net.ProxyUri
import xyz.azraellab.zapp.core.net.SingboxConfig
import xyz.azraellab.zapp.core.net.WireGuardIni
import xyz.azraellab.zapp.core.net.probeDns
import xyz.azraellab.zapp.core.native.BinaryResolver
import xyz.azraellab.zapp.core.perApp.AppFamily
import xyz.azraellab.zapp.core.perApp.AppSelection
import xyz.azraellab.zapp.core.perApp.PerAppConfig
import xyz.azraellab.zapp.core.perApp.RouteMode
import xyz.azraellab.zapp.core.perApp.installedApps
import xyz.azraellab.zapp.core.root.RootShell
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

actual fun createTunnelEngine(): TunnelEngine = JvmTunnelEngine()

/**
 * Поднятие туннеля внешними бэкендами от root.
 *
 * Одна схема для всех протоколов: есть ядро (WireGuard/Amnezia -- разовая
 * команда, интерфейс живёт сам), есть демоны (box, openvpn, tor -- живой
 * процесс с журналом), а «подключено» -- состояние реально поднятого
 * механизма, а не нажатой кнопки. Права не прячутся: если `su` нет,
 * каждая ветка честно отдаёт ошибку в журнал туннеля.
 *
 * Kill switch, автопереподключение и per-app исключения -- здесь же, а не
 * в UI: они свойства соединения, а не экрана.
 */
private class JvmTunnelEngine : BaseTunnelEngine() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var config: VpnConfig = VpnConfig()

    /** Выборы приложений по функции; передаются AppState через applyPerApp. */
    @Volatile
    private var perApp: PerAppConfig = PerAppConfig()

    /**
     * Выбор приложений текущей сессии.
     *
     * VPN и Tor делят один туннель, поэтому скоуп выбирается в
     * performConnect по режиму подключения, а не в момент передачи
     * конфига: только там видно, чей это туннель. DPI-сессия скоупа
     * не имеет -- пассивный туннель обязан пропускать весь трафик,
     * иначе релей не увидит соединений.
     */
    @Volatile
    private var sessionApps: AppSelection = AppSelection.NONE

    /** Ядерный интерфейс; null, если не поднимали. */
    @Volatile
    private var iface: String? = null

    /**
     * Сервисный туннель поднят и fd у бэкенда: флаг живого kill switch.
     *
     * Снимается только при явной уборке (cleanupQuiet/performDisconnect):
     * падение демона туннель не трогает -- в этом весь смысл крышки.
     */
    @Volatile
    private var tunnelHeld = false

    private var boxDaemon: DaemonEngine? = null
    private var openvpnDaemon: DaemonEngine? = null
    private var torDaemon: DaemonEngine? = null

    /**
     * Последний собранный конфиг box: нужен для перезапуска демона
     * без переподнятия туннеля (kill switch через живой TUN).
     *
     * Обнуляется в [cleanupQuiet]/[performDisconnect]: после снятия
     * интерфейса перезапуск бэкенда на старом конфиге бессмыслен.
     */
    @Volatile
    private var lastBoxBuilt: SingboxConfig.Built? = null

    private var killSwitchEndpoints: List<String> = emptyList()
    private var uidZeroBypassOn = false

    @Volatile
    private var stopping = false

    /**
     * Режим пассивной DPI-сессии: туннель без прокси, только сплит-релей.
     *
     * Ставится [connectDpi] (демоны обхода живут, туннель нет) и снимается
     * обычным [connect] (пользователь поднимает VPN/Tor) -- чужой режим
     * начатого подключения переключать нельзя молча.
     */
    @Volatile
    private var dpiMode = false

    private var watchJob: Job? = null

    /** Одна попытка подключения: параллельный СТАРТ второго не начинает. */
    private val inFlight = AtomicBoolean(false)

    /** Текущая попытка, чтобы СТОП мог её отменить до спавна демонов. */
    @Volatile
    private var connectJob: Job? = null

    override val supported: Boolean = true

    private val reapJob: Job

    init {
        // Демоны -- отдельные процессы от root: убил приложение (свайп,
        // обновление, крэш) -- tor и box продолжают жить, держат порты
        // и tun. Свежий запуск тогда падает с «Address already in use»,
        // а старый туннель работает молча, вне контроля приложения.
        // Чистка по каталогу, куда кладутся все бинарии, -- она же
        // снимает сирот любого протокола, а не только tor'а. Job
        // сохраняется: автозапуск ждёт её завершения перед спавном
        // демонов (см. awaitStartup).
        reapJob = scope.launch { reapOrphanDaemons() }
    }

    /**
     * Снятие демонов, переживших смерть прошлого процесса приложения.
     *
     * TERM, пауза и KILL: тор закрывается по TERM аккуратно, но бывает
     * занят в операции, и KILL гарантирует, что порт 9050 свободен к
     * моменту следующего старта. На платформе без каталога команда
     * просто ничего не находит.
     */
    private fun reapOrphanDaemons() {
        // Паттерн -- конкретные бинарии, а не общий каталог: cmdline самой
        // команды (sh -c) содержит каталог, и паттерн по каталогу убивает
        // собственную обёртку вместе с детьми. Список бинариев в cmdline
        // не встречается подряд с префиксом каталога -- сам себя pkill
        // не находит.
        // Два префикса путей: root-сборка кладёт бинари в
        // /data/local/tmp/zapp/bin, noroot-запускает их из nativeLibraryDir
        // (jniLibs, lib<tool>.so). Паттерн в cmdline сам себя не находит:
        // после префикса в собственной команде стоит скобка, а не имя
        // бинаря.
        val own = "(/data/local/tmp/zapp/bin/|/lib/[^ ]*lib)" +
            "(tor|box|openvpn|lyrebird|wg|nfqws|goodbyedpi|ovpntun)"
        runCatching {
            RootShell.run("pkill -TERM -f '$own'; sleep 1; pkill -KILL -f '$own'", 4000)
        }
    }

    /**
     * Завершается, когда стартовая уборка сирот закончена.
     *
     * Зовётся из автозапуска до спавна демонов: сирота прошлой сессии
     * держит свою NFQUEUE-очередь, и первый цикл zapret умирает с
     * «Operation not permitted», пока уборка ещё идёт в фоне.
     */
    override suspend fun awaitStartup() {
        reapJob.join()
    }

    override fun applyConfig(config: VpnConfig) {
        this.config = config
    }

    override fun applyPerApp(perApp: PerAppConfig) {
        this.perApp = perApp
    }

    override fun connect() {
        val job = scope.launch {
            // Двойной тап или VPN+Tor почти одновременно: два
            // performConnect бились бы за порт 9050 и за демон TUNNEL,
            // второй падал с «Address already in use» (и портит состояние
            // первому). Одна попытка -- одна, пока не закончится.
            if (state.value == TunnelState.CONNECTING) return@launch
            if (!inFlight.compareAndSet(false, true)) return@launch
            stopping = false
            // Обычное подключение всегда выигрывает у DPI-режима: юзер
            // явно выбрал профиль, пассивной сессии больше нет места.
            dpiMode = false
            try {
                // Живая DPI-сессия под пользовательским конфигом: старая
                // снимается, новая поднимается -- иначе connect() молча
                // проигрывал бы состоянию CONNECTED и VPN не стартовал бы.
                if (state.value == TunnelState.CONNECTED) {
                    log("reconnecting with new config")
                    performDisconnect(userInitiated = false)
                }
                performConnect()
            } finally {
                inFlight.set(false)
            }
        }
        connectJob = job
    }

    override fun connectDpi() {
        val job = scope.launch {
            if (state.value == TunnelState.CONNECTING || state.value == TunnelState.CONNECTED) {
                return@launch
            }
            if (!inFlight.compareAndSet(false, true)) return@launch
            stopping = false
            dpiMode = true
            try {
                performConnect()
            } finally {
                inFlight.set(false)
            }
        }
        connectJob = job
    }

    override fun refreshDpi() {
        val job = scope.launch {
            if (state.value != TunnelState.CONNECTED) return@launch
            if (!inFlight.compareAndSet(false, true)) return@launch
            stopping = false
            try {
                log("dpi rules changed -- restarting session")
                performDisconnect(userInitiated = false)
                performConnect()
            } finally {
                inFlight.set(false)
            }
        }
        connectJob = job
    }

    override fun disconnect() {
        scope.launch {
            // СТОП во время подключения: сначала рвём попытку, иначе она
            // после уборки доспавнит демон -- сирота держит порт 9050.
            // Отмена видна только на ближайшей suspension-точке, поэтому
            // в точках спавна стоит проверка stopping.
            connectJob?.cancel()
            performDisconnect(userInitiated = true)
        }
    }

    // --- Подключение ---

    private suspend fun performConnect() {
        setState(TunnelState.CONNECTING)
        log("connecting")

        val vpn = withAutoDns(config)
        val profile = vpn.activeProfile()
        // DPI-режим игнорирует профиль: пассивной сессии нужен только
        // туннель и конфиг обхода, а выбор пользователя применится при
        // следующем обычном connect().
        val dpiSession = dpiMode
        if (!dpiSession && profile == null && !vpn.groupMode) {
            log("error: no active profile")
            setState(TunnelState.ERROR)
            return
        }

        val protocol = VpnProtocol.of(profile?.protocol)
        // Владелец туннеля текущей сессии: пассивная DPI-сессия скоупа
        // не имеет (релей обязан видеть весь трафик), у Tor -- свой
        // выбор приложений, всё остальное -- VPN.
        sessionApps = when {
            dpiSession -> AppSelection.NONE
            protocol == VpnProtocol.TOR -> perApp.tor
            else -> perApp.vpn
        }
        try {
            when {
                dpiSession -> boxDpi(vpn)
                // Tor -- раньше groupMode: мост, выбранный на вкладке Tor,
                // обязан вести в torUp, а не в urltest-группу остальных
                // коннектов. groupMode -- режим списка VPN, он не отменяет
                // явный выбор моста.
                profile != null && protocol == VpnProtocol.TOR -> torUp(profile, vpn)
                vpn.groupMode -> boxGroup(vpn)
                profile == null -> error("no active profile")
                protocol == VpnProtocol.WIREGUARD -> kernelWireGuard(profile, awg = false)
                protocol == VpnProtocol.AMNEZIA_WG -> kernelWireGuard(profile, awg = true)
                protocol == VpnProtocol.OPENVPN -> openvpnUp(profile)
                protocol == VpnProtocol.TOR -> torUp(profile, vpn)
                protocol == VpnProtocol.SINGBOX -> boxRaw(profile.rawConfig, vpn, profile)
                protocol == VpnProtocol.XRAY -> boxBuilt(SingboxConfig.build(profile, vpn), vpn, profile)
                else -> boxBuilt(SingboxConfig.build(profile, vpn), vpn, profile)
            }
        } catch (e: CancellationException) {
            // СТОП отменил попытку: это не ошибка, уборку уже делает
            // performDisconnect, состояние ERROR ставить нельзя.
            throw e
        } catch (e: Exception) {
            if (stopping) {
                // СТОП успел прийти во время подключения: разбор уже идёт,
                // ошибка пользователя -- не сбой.
                cleanupQuiet()
                return
            }
            log("error: ${e.message ?: e::class.simpleName.orEmpty()}")
            cleanupQuiet()
            setState(TunnelState.ERROR)
            return
        }

        // Отмена видна не на suspension-точке: между концом try и этой
        // строкой СТОП мог уже убрать туннель -- CONNECTED соврал бы.
        coroutineContext.ensureActive()
        if (stopping) {
            cleanupQuiet()
            return
        }
        setState(TunnelState.CONNECTED)
        log("connected")
        startWatch()
    }

    /**
     * Автозамена DNS-сервера при пустом списке пользователя.
     *
     * Ядро при пустом списке стартует с 8.8.8.8 вслепую: оператор может
     * резать или подменять UDP/53 к чужим резолверам, и туннель, поднявшись,
     * умирает на первом же домене. Поэтому кандидаты пробуются настоящим
     * DNS-запросом до старта демона и берётся первый ответивший.
     *
     * Пользовательский список не трогается никогда: если человек задал
     * свои серверы, он знает лучше, и замена молча подменила бы его выбор.
     * Ни один кандидат не ответил -- конфиг уходит как есть, ядро
     * сработает по своему фолбэку, а ошибка адреса будет видна в логе.
     */
    private suspend fun withAutoDns(config: VpnConfig): VpnConfig {
        if (config.dnsList().isNotEmpty()) return config
        val picked = try {
            coroutineScope {
                DnsFailover.candidates
                    .map { server ->
                        async { server to probeDns(server, DnsFailover.probeName, DnsFailover.probeTimeoutMs) }
                    }
                    .awaitAll()
                    .let { DnsFailover.pick(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (picked == null) {
            log("note: dns auto-probe found nothing, keeping default")
            return config
        }
        log("note: dns auto -> $picked")
        return config.copy(dnsServers = picked)
    }

    // --- Ядерный WireGuard / Amnezia WG ---

    private suspend fun kernelWireGuard(profile: VpnProfile, awg: Boolean) {
        val vpn = config
        // Noroot-сборка: ядерный интерфейс не поднимается, но профиль
        // работает -- тем же конфигом через sing-box endpoint (wg/awg
        // outbound). Механизм другой, результат для пользователя тот же.
        if (!RootShell.available()) {
            log("note: kernel mode unavailable -- sing-box endpoint")
            boxBuilt(SingboxConfig.build(profile, vpn), vpn, profile)
            return
        }
        val binary = BinaryResolver.resolve("wg")
            ?: error("binary not found: wg")
        val name = ifaceName(profile)
        val conf = WireGuardIni.write(profile)
        val confPath = writeSharedFile("wg/$name.conf", conf) ?: error("cannot write wg config")
        val keyPath = writeSharedFile("wg/$name.key", profile.privateKey) ?: error("cannot write key file")

        // Старый интерфейс с тем же именем снимаем молча: переподключение.
        RootShell.run("ip link del dev $name", 2000)

        val type = if (awg) "awg" else "wireguard"
        val add = RootShell.run("ip link add dev $name type $type", 3000)
        if (!add.ok) {
            if (awg) {
                log("note: kernel module '$type' unavailable, falling back to sing-box endpoint")
                boxBuilt(SingboxConfig.build(profile, vpn), vpn, profile)
                return
            }
            error("ip link add failed: ${lastLine(add.output)}")
        }
        iface = name

        // Устройство целиком через `wg set`: конфиг wg-quick (Address, DNS)
        // ядру не нужен, а amnezia-опции jc/s1/h1... -- опции устройства.
        val setDevice = buildString {
            append("wg set $name private-key ${RootShell.quote(keyPath)}")
            if (profile.gatewayPort in 1..65535) append(" listen-port ${profile.gatewayPort}")
            for (key in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")) {
                profile.extras[key]?.toIntOrNull()?.let { append(" $key $it") }
            }
            for (key in listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")) {
                profile.extras[key]?.takeIf { it.isNotBlank() }?.let { v ->
                    append(" $key ${RootShell.quote(v)}")
                }
            }
        }
        must(RootShell.run(setDevice, 4000), "wg set device")

        val peer = buildString {
            append("wg set $name peer ${RootShell.quote(profile.publicKey)}")
            if (profile.presharedKey.isNotBlank()) {
                val pskPath = writeSharedFile("wg/$name.psk", profile.presharedKey)
                    ?: error("cannot write psk file")
                append(" preshared-key ${RootShell.quote(pskPath)}")
            }
            if (profile.gateway.isNotBlank()) {
                append(" endpoint ${RootShell.quote(formatEndpoint(profile.gateway, profile.gatewayPort))}")
            }
            val allowed = profile.allowedIpList().joinToString(",")
            if (allowed.isNotBlank()) append(" allowed-ips ${RootShell.quote(allowed)}")
            if (profile.keepaliveSeconds > 0) append(" persistent-keepalive ${profile.keepaliveSeconds}")
        }
        must(RootShell.run(peer, 4000), "wg set peer")

        val address = profile.address.split(',').map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?: error("profile has no address")
        must(RootShell.run("ip address add $address dev $name", 3000), "ip address add")
        must(RootShell.run("ip link set mtu ${profile.mtu} up dev $name", 3000), "ip link up")

        applyKernelRoutes(profile, vpn, name)

        if (vpn.routing.interceptDns) {
            log("note: dns interception is not available in kernel mode; system resolver stays")
        }
        if (vpn.routing.allowPerAppBypass && vpn.routing.bypassAppList().isNotEmpty()) {
            log("note: per-app bypass is supported by the sing-box backend only")
        }
        if (sessionApps.active()) {
            // Ядро маршрутизирует устройство целиком, per-app-список живёт
            // только в VpnService -- молчать нельзя: пользователь выбрал
            // приложения, а уйдёт весь трафик.
            log("note: per-app routing is not available in kernel mode; whole device goes through the tunnel")
        }
        if (vpn.killSwitch) enableKillSwitch(endpointsOf(profile), vpn)
    }

    private fun applyKernelRoutes(profile: VpnProfile, vpn: VpnConfig, name: String) {
        val routesAll = vpn.routing.routesAllTraffic
        if (routesAll && vpn.routing.excludePrivateRanges) {
            val main = defaultRouteDevice()
            if (main != null) {
                for (cidr in PRIVATE_V4) {
                    val r = RootShell.run("ip route replace $cidr dev $main", 3000)
                    if (!r.ok) log("warning: route $cidr: ${lastLine(r.output)}")
                }
            } else {
                log("warning: default route not found; LAN exclusions skipped")
            }
        }

        val targets = if (routesAll) {
            listOf("0.0.0.0/1", "128.0.0.0/1") + if (vpn.ipv6) listOf("::/1", "8000::/1") else emptyList()
        } else {
            profile.allowedIpList()
        }
        for (cidr in targets) {
            val r = RootShell.run("ip route replace $cidr dev $name", 3000)
            if (!r.ok) log("warning: route $cidr: ${lastLine(r.output)}")
        }
    }

    // --- sing-box (прокси, группа, raw-конфиг, endpoint'ы) ---

    private suspend fun boxGroup(vpn: VpnConfig) {
        val links = vpn.visibleProfiles().mapNotNull { p ->
            val raw = p.rawConfig
            if (raw.isBlank() || "://" !in raw) return@mapNotNull null
            if (VpnProtocol.of(p.protocol) in setOf(
                    VpnProtocol.TOR, VpnProtocol.WIREGUARD,
                    VpnProtocol.AMNEZIA_WG, VpnProtocol.OPENVPN,
                    VpnProtocol.SINGBOX, VpnProtocol.XRAY
                )
            ) return@mapNotNull null
            if (p.health.alive == false) return@mapNotNull null
            ProxyUri.parse(raw)?.let { raw }
        }
        boxBuilt(SingboxConfig.buildGroup(vpn, links), vpn, null)
    }

    private suspend fun boxRaw(raw: String, vpn: VpnConfig, profile: VpnProfile?) {
        check(raw.isNotBlank()) { "sing-box profile has no config text" }
        boxBuilt(SingboxConfig.Built(raw, listOf("engine: sing-box config as provided")), vpn, profile)
    }

    /** Пассивная DPI-сессия: туннель поднимается, но прокси в нём нет. */
    private suspend fun boxDpi(vpn: VpnConfig) {
        log("dpi passthrough session (userland relay)")
        boxBuilt(SingboxConfig.buildDpi(vpn), vpn, null)
    }

    /**
     * Пакеты allow-режима текущей сессии, расширенные до uid-семей.
     *
     * Выбранный пакет тянет за собой соседей по sharedUserId (движок
     * зовёт AppFamily в момент establish, когда каталог установленных
     * ещё свежий), иначе обход поймал бы только часть процессов.
     * Выключенный выбор, exclude-режим или пустой список -- пустой
     * список, туннель без ограничений. Ошибка каталога не роняет
     * подключение: пакеты уходят без расширения, худший случай --
     * усечённый семейство.
     */
    private fun allowedPackages(): List<String> = expandedPackages(RouteMode.INCLUDE)

    /**
     * Пакеты exclude-режима: их Builder уводит мимо туннеля.
     *
     * Тот же принцип расширения до семейств, что и в allow-режиме:
     * соседи по sharedUserId пришли бы вместе с приложением, которого
     * пользователь исключал.
     */
    private fun excludedPackages(): List<String> = expandedPackages(RouteMode.EXCLUDE)

    /** Общий разбор выбора: mode фильтрует, семейства расширяют. */
    private fun expandedPackages(mode: RouteMode): List<String> {
        if (!sessionApps.active() || sessionApps.mode != mode) return emptyList()
        val catalog = runCatching { installedApps() }.getOrDefault(emptyList())
        return AppFamily.expand(sessionApps.packages, catalog)
    }

    private suspend fun boxBuilt(built: SingboxConfig.Built, vpn: VpnConfig, profile: VpnProfile?) {
        built.notes.forEach { log(it) }
        if (vpn.killSwitch) enableKillSwitch(endpointsOf(profile), vpn)
        lastBoxBuilt = built

        // Android: туннель поднимает VpnService до старта ядра, а fd ядро
        // забирает из unix-сокета сервиса (номер в окружении ненадёжен:
        // ProcessBuilder вырезает унаследованные fd при спавне). Desktop:
        // establish() вернёт null, и ядро откроет /dev/net/tun само.
        val tunnel = vpnTunnelParams(
            built.json, vpn, profile?.name?.takeIf { it.isNotBlank() } ?: "ZAPP",
            allowedPackages(),
            excludedPackages()
        )
        if (stopping) error("connection stopped before tunnel start")
        val fd = tunnel?.let { VpnTunnel.establish(it) }
        tunnelHeld = fd != null
        if (fd != null && RootShell.available()) enableUidZeroBypass()

        val daemon = createDaemonEngine(DaemonId.TUNNEL)
        boxDaemon = daemon
        if (stopping) error("connection stopped before box start")
        daemon.start(boxSpec(built, useTunSocket = fd != null))
        if (!awaitState(daemon, DaemonState.RUNNING, START_TIMEOUT_MS)) {
            // Причина снимается до остановки: stop добавит в журнал свою
            // строку, а процесс, не успевший выйти к таймауту, убит -- без
            // этого поздний спавн оставлял сироту.
            val reason = daemonReason(daemon)
            runCatching { daemon.stop() }
            error("box failed to start: $reason")
        }
        log("box is running")
    }

    /**
     * Спецификация запуска box: одна на первый старт и на перезапуск
     * при живом kill switch.
     *
     * [useTunSocket] говорит, что туннель поднят через сервис и бэкенд
     * должен забрать fd по unix-сокету: при respawn fd уже не наш, а
     * сервис (accept-цикл по одному пути) отдаст новому box'у ту же
     * самую туннель-сессию.
     */
    private fun boxSpec(built: SingboxConfig.Built, useTunSocket: Boolean): DaemonSpec = DaemonSpec(
        binary = "box",
        args = listOf("run", "-c", "{{config}}"),
        files = mapOf("config" to built.json),
        env = if (useTunSocket && VpnTunnel.fdSocketPath != null) {
            buildMap {
                put("ZAPP_TUN_SOCK", VpnTunnel.fdSocketPath!!)
                // Go x509 ищет корни в /etc/ssl/certs, которого на
                // Android нет: корневые лежат в
                // /system/etc/security/cacerts. Без этой переменной
                // TLS-проверка сертификатов падает с «unknown
                // authority».
                put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            }
        } else {
            emptyMap()
        }
    )

    /**
     * Перезапуск box на уже поднятом туннеле: kill switch-режим, когда
     * падение бэкенда не должно поднимать весь трафик наружу.
     *
     * Туннель не трогается: пока fd жив, пакеты уходят в него и
     * теряются (чёрная дыра), а новый box снова подключается к сервису.
     * true -- демон снова RUNNING.
     */
    private suspend fun respawnBox(): Boolean {
        val built = lastBoxBuilt ?: return false
        runCatching { boxDaemon?.stop() }
        val daemon = createDaemonEngine(DaemonId.TUNNEL)
        boxDaemon = daemon
        daemon.start(boxSpec(built, useTunSocket = true))
        val alive = awaitState(daemon, DaemonState.RUNNING, START_TIMEOUT_MS)
        if (alive) log("box is running (kill switch kept the tunnel)")
        else {
            val reason = daemonReason(daemon)
            runCatching { daemon.stop() }
            log("box restart failed: $reason")
        }
        return alive
    }

    // --- OpenVPN ---

    private suspend fun openvpnUp(profile: VpnProfile) {
        val vpn = config
        check(profile.rawConfig.isNotBlank()) { "openvpn profile has no config text" }

        // Noroot + Android: у приложения нет /dev/net/tun, туннель
        // поднимает VpnService, а пакеты мостит бинарь ovpntun -- openvpn
        // спавнит его сам как tun-ребёнка (--dev-node unix:...) и передаёт
        // ему номер своей стороны socketpair. Root и desktop: openvpn
        // открывает tun сам, как раньше.
        val sockPath = VpnTunnel.fdSocketPath
        val afunix = !RootShell.available() && sockPath != null
        val tunnel = if (afunix) {
            openvpnTunnelParams(profile, vpn, allowedPackages(), excludedPackages())
                ?: error("openvpn profile has no ifconfig")
        } else {
            null
        }
        val bridge = if (afunix) {
            BinaryResolver.resolve("ovpntun") ?: error("binary not found: ovpntun")
        } else {
            null
        }

        if (vpn.killSwitch) enableKillSwitch(endpointsOf(profile), vpn)
        if (tunnel != null) {
            if (stopping) error("connection stopped before tunnel start")
            tunnelHeld = VpnTunnel.establish(tunnel) != null
        }

        val extra = if (afunix) {
            log("note: afunix -- tun via VpnService, bridge ovpntun")
            listOf(
                "--dev-node", "unix:$bridge",
                // S_SCRIPT требует script-security >= 2; после --config,
                // чтобы перебрать уровень, заданный в конфиге.
                "--script-security", "2",
                // Единственный путь переменной в окружение ребёнка:
                // openvpn спавнит тун-ребёнка через execve из своих es,
                // а не через наследуемый environ.
                "--setenv", "ZAPP_TUN_SOCK", sockPath.orEmpty()
            )
        } else {
            emptyList()
        }

        val daemon = createDaemonEngine(DaemonId.TUNNEL)
        openvpnDaemon = daemon
        if (stopping) error("connection stopped before openvpn start")
        daemon.start(
            DaemonSpec(
                binary = "openvpn",
                args = listOf("--config", "{{config}}") + extra,
                files = mapOf("config" to profile.rawConfig)
            )
        )
        if (!awaitState(daemon, DaemonState.RUNNING, START_TIMEOUT_MS)) {
            val reason = daemonReason(daemon)
            runCatching { daemon.stop() }
            error("openvpn failed to start: $reason")
        }
        log("openvpn is running")
    }

    // --- Tor ---

    private suspend fun torUp(profile: VpnProfile, vpn: VpnConfig) {
        check(profile.rawConfig.isNotBlank()) { "tor profile has no bridges" }
        val tor = BinaryResolver.resolve("tor") ?: error("binary not found: tor")
        val transport = BinaryResolver.resolve("lyrebird")
        val socksPort = vpn.torSocksPort.coerceIn(1024, 65535)

        // Каталог состояния tor: с root -- общий /data/local/tmp (мир
        // читает всех), без root -- приватный временный каталог приложения:
        // tor пишет туда ключи и состояние и должен иметь права на запись.
        val dataDir = if (RootShell.available()) {
            "/data/local/tmp/zapp/tor-data"
        } else {
            File(File(System.getProperty("java.io.tmpdir")), "zapp/tor-data")
                .apply { runCatching { mkdirs() } }.absolutePath
        }

        val torrc = buildString {
            appendLine("DataDirectory $dataDir")
            appendLine("SocksPort 127.0.0.1:$socksPort")
            appendLine("UseBridges 1")
            appendLine("ClientOnly 1")
            appendLine("Log notice stdout")
            if (transport != null) {
                // lyrebird -- все клиентские транспорты одним бинарём:
                // каждая строка моста со своим именем транспорта требует
                // своей регистрации, но исполняемый файл у них общий.
                for (name in listOf("obfs4", "webtunnel", "scramblesuit", "snowflake", "meek_lite")) {
                    appendLine("ClientTransportPlugin $name exec ${RootShell.quote(transport)}")
                }
            }
            // Сначала мосты выбранного профиля, потом -- живых остальных:
            // tor пробует список по порядку, и неподготовленный фейловер
            // (мёртвый основной мост) экономит минуты bootstrap'а. Дубли
            // строк между источниками схлопываются -- списки часто одни
            // и те же, отличаясь порядком.
            //
            // Доменные имена разрешаются здесь, а не lyrebird: у того Go
            // без cgo и /etc/resolv.conf на Android нет, поэтому доменные
            // мосты у него мертвы. Кэш один на запуск -- одни и те же
            // имена в списках повторяются десятками.
            val hostCache = HashMap<String, String>()
            fun resolved(raw: String) = TorBridges.resolveHosts(raw) { host ->
                hostCache.getOrPut(host) {
                    runCatching { InetAddress.getAllByName(host).firstOrNull()?.hostAddress }
                        .getOrNull().orEmpty()
                }.ifEmpty { null }
            }
            val seen = mutableSetOf<String>()
            fun appendBridges(raw: String) {
                for (line in resolved(raw).lineSequence().map { it.trim() }) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val normalized = if (line.startsWith("Bridge ", ignoreCase = true)) line
                    else "Bridge $line"
                    if (seen.add(normalized)) appendLine(normalized)
                }
            }
            appendBridges(profile.rawConfig)
            for (other in vpn.profiles) {
                if (other.id == profile.id) continue
                if (other.protocol != VpnProtocol.TOR.code) continue
                if (other.health.alive == false) continue
                appendBridges(other.rawConfig)
            }
        }

        if (RootShell.available()) {
            RootShell.run("mkdir -p ${RootShell.quote(dataDir)}", 2000)
        }

        if (stopping) error("connection stopped before tor start")
        ensureSocksPortFree(socksPort)
        val daemon = createDaemonEngine(DaemonId.TUNNEL)
        torDaemon = daemon
        daemon.start(DaemonSpec(binary = tor, args = listOf("-f", "{{torrc}}"), files = mapOf("torrc" to torrc)))
        if (!awaitState(daemon, DaemonState.RUNNING, START_TIMEOUT_MS)) {
            val reason = daemonReason(daemon)
            runCatching { daemon.stop() }
            error("tor failed to start: $reason")
        }

        // Bootstrap: SOCKS-порт открывается сразу, но без 100% трафик через
        // мост ещё не проходит -- Tor отвергает запросы до готовности.
        // Готовность -- строка самого tor'а, а не пинг порта: пинг дал бы
        // «ready» на мгновение раньше реальной работы. Прогресс идёт в
        // журнал туннеля десятками процентов: мосты на телефоне собираются
        // десятки секунд, и тишина в журнале выглядит как зависание.
        log("tor bootstrap, socks:$socksPort ...")
        val deadline = System.currentTimeMillis() + TOR_BOOTSTRAP_MS
        // -10, а не -1: иначе первый десяток (0..9) не проходит проверку
        // «новый десяток» (5/10 == -1/10 == 0) и прогресс молчит до 10%.
        var lastPct = -10
        var lastStage: String? = null
        while (System.currentTimeMillis() < deadline) {
            if (stopping) error("tor bootstrap cancelled")
            if (daemon.state.value == DaemonState.ERROR) {
                error("tor exited during bootstrap: ${daemonReason(daemon)}")
            }
            val pct = torProgressPercent(daemon.log.value)
            if (pct == 100) {
                log("tor is ready")
                if (vpn.killSwitch) enableKillSwitch(bridgeAddresses(profile.rawConfig), vpn)
                boxBuilt(SingboxConfig.buildTor(vpn, socksPort), vpn, null)
                return
            }
            // Процент пишется десятками, стадия -- при каждой смене: между
            // «requesting_descriptors» и «loading_descriptors» процент один,
            // а для моста это разные минуты ожидания (B1).
            val stage = torProgressStage(daemon.log.value)
            if (pct in 0..99 && (pct / 10 > lastPct / 10 || stage != lastStage)) {
                lastPct = pct
                lastStage = stage
                log("tor bootstrap $pct%" + (stage?.let { " ($it)" } ?: ""))
            }
            delay(500)
        }
        error("tor bootstrap timeout (${TOR_BOOTSTRAP_MS / 1000}s)")
    }

    /**
     * Порт SOCKS должен принадлежать нам, а не сироте прошлой попытки.
     *
     * Двойной СТАРТ или убитое приложение оставляли старый tor на 9050:
     * новый тогда падал с «Address already in use» и портил состояние.
     * Свой висящий процесс убиваем сами и даём порту освободиться; чужой
     * не трогаем, но называем в ошибке -- молчаливый запуск упал бы
     * позже и непонятно почему.
     */
    private fun ensureSocksPortFree(port: Int) {
        if (!RootShell.available()) return
        val holder = RootShell.run("netstat -tlnp 2>/dev/null | grep ':$port '", 3000)
        if (!holder.ok || holder.output.isBlank()) return
        // Только слушатели мешают: netstat отдаёт и клиентские TIME_WAIT
        // на этот же порт (сокет tor'а прошлой сессии), а они bind'у не
        // мешают. Порог -- наличие состояния LISTEN в строке.
        val listening = holder.output.lineSequence()
            .filter { "LISTEN" in it }
            .joinToString("\n")
        if (listening.isBlank()) return
        if ("/data/local/tmp/zapp/bin/" in listening) {
            log("note: socks port $port held by orphan tor; killing it")
            runCatching {
                RootShell.run(
                    "pkill -TERM -f '/data/local/tmp/zapp/bin/tor'; sleep 1; " +
                        "pkill -KILL -f '/data/local/tmp/zapp/bin/tor'",
                    4000
                )
            }
            return
        }
        error("socks port $port busy: ${lastLine(listening)}")
    }

    /**
     * Последняя осмысленная причина сбоя журнала демона для ошибки запуска.
     *
     * Выбор -- тот же, что в UI (`core.log.errorEvent`): сначала сырой
     * вывод с явным признаком ошибки, затем событие-причина (код выхода,
     * отсутствующий бинарь). Текст события рендерится по-английски: строки
     * журнала туннеля -- технический текст, и «Exit code 1» читается
     * лучше, чем имя ключа («DAEMON_LOG_EXIT 1»), которое раньше утекало
     * в интерфейс как есть.
     */
    private fun daemonReason(daemon: DaemonEngine): String {
        val event = daemon.log.value.errorEvent() ?: return "no output"
        val text = event.text
        val reason = when {
            event.raw.isNotBlank() -> event.raw
            text != null -> (text.of(AppLang.EN) + " " + event.arg).trim()
            else -> "no output"
        }
        // sing-box/lyrebird пишут в stderr с ANSI-цветами; в журнале это
        // пригодится, но в сообщении под кнопкой пользователь читает мусор
        // вида `[31mFATAL[0m[0000]` вместо чистого текста.
        return stripAnsi(reason)
    }

    // --- Kill switch ---

    private fun enableKillSwitch(endpoints: List<String>, vpn: VpnConfig) {
        if (!RootShell.available()) {
            // Noroot-сборка: эквивалент kill switch -- сама сессия
            // VpnService: пока туннель поднят, весь трафик идёт в tun, а
            // упавший бэкенд молча отбрасывает пакеты (чёрная дыра).
            // iptables-цепочка здесь не нужна и не создаётся.
            log("note: kill switch -- tun blackhole (session mode)")
            return
        }
        killSwitchEndpoints = endpoints
        val setup = RootShell.run(
            """
            iptables -N ZAPP_KS 2>/dev/null
            iptables -F ZAPP_KS
            """.trimIndent().replace("\n", "; "),
            3000
        )
        if (!setup.ok) {
            log("note: iptables unavailable; kill switch skipped")
            return
        }
        val rules = mutableListOf(
            "iptables -A ZAPP_KS -o lo -j RETURN",
            "iptables -A ZAPP_KS -o tun+ -j RETURN",
            "iptables -A ZAPP_KS -o utun+ -j RETURN",
            "iptables -A ZAPP_KS -m state --state ESTABLISHED,RELATED -j RETURN"
        )
        for (endpoint in endpoints) {
            rules += "iptables -A ZAPP_KS -d $endpoint/32 -j RETURN"
        }
        // Свои DNS при kill switch: без них мёртв не только трафик, но и
        // резолвер -- переподключение по доменному имени не поднимется.
        // Пропускаются только серверы самого конфига, не «любой DNS».
        if (vpn.killSwitchDns) {
            for (dns in vpn.dnsList()) {
                val addr = runCatching { InetAddress.getByName(dns) }.getOrNull() ?: continue
                val mask = if (addr is Inet6Address) 128 else 32
                rules += "iptables -A ZAPP_KS -d $dns/$mask -j RETURN"
            }
        }
        rules += "iptables -A ZAPP_KS -j REJECT --reject-with icmp-port-unreachable"
        for (rule in rules) {
            val r = RootShell.run(rule, 3000)
            if (!r.ok) log("warning: kill switch: ${lastLine(r.output)}")
        }
        val jump = RootShell.run("iptables -C OUTPUT -j ZAPP_KS 2>/dev/null || iptables -I OUTPUT 1 -j ZAPP_KS", 3000)
        if (jump.ok) log("kill switch: on")
        else log("note: cannot attach kill switch chain")
    }

    private fun disableKillSwitch() {
        if (killSwitchEndpoints.isEmpty()) return
        killSwitchEndpoints = emptyList()
        if (!RootShell.available()) return
        RootShell.run(
            "while iptables -D OUTPUT -j ZAPP_KS 2>/dev/null; do : done; iptables -F ZAPP_KS 2>/dev/null; iptables -X ZAPP_KS 2>/dev/null",
            4000
        )
        log("kill switch: off")
    }

    /** Выводит демоны root мимо туннеля; см. [UidZeroBypass]. */
    private fun enableUidZeroBypass() {
        if (uidZeroBypassOn) return
        uidZeroBypassOn = UidZeroBypass.install { log(it) }
        if (!uidZeroBypassOn) log("note: uid0 bypass unavailable")
    }

    private fun disableUidZeroBypass() {
        if (!uidZeroBypassOn) return
        uidZeroBypassOn = false
        UidZeroBypass.remove()
    }

    // --- Наблюдение и автопереподключение ---

    /**
     * Живой TUN: kill switch и поднятый сервисный туннель.
     *
     * Пока fd цел, падение бэкенда можно лечить перезапуском демона --
     * трафик всё это время уходит в туннель и теряется (чёрная дыра),
     * наружу ничего не утекает. Снятие туннеля при падении как раз и
     * пробивает kill switch, поэтому в этом режиме reconnect идёт без
     * cleanupQuiet.
     */
    private fun liveTunKillSwitch(): Boolean =
        config.killSwitch && tunnelHeld && lastBoxBuilt != null

    private fun startWatch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            var attempts = 0
            val profile = config.activeProfile()
            val delayMs = (profile?.reconnectDelaySeconds ?: 5).coerceIn(1, 300) * 1000L
            val max = profile?.maxReconnectAttempts ?: 0
            val auto = profile?.autoReconnect ?: true

            while (isActive && !stopping) {
                delay(WATCH_TICK_MS)
                when (state.value) {
                    TunnelState.CONNECTED -> {
                        if (connectionAlive()) {
                            attempts = 0
                            continue
                        }
                        log("connection lost")
                        if (!auto) {
                            setState(TunnelState.ERROR)
                            return@launch
                        }
                        if (liveTunKillSwitch()) {
                            // Туннель остаётся: крышка kill switch не
                            // снимается ни на мгновение, рестарт идёт в
                            // отдельной ветке RECONNECTING.
                            log("kill switch: tunnel stays up, restarting box")
                            setState(TunnelState.RECONNECTING)
                            continue
                        }
                        attempts++
                        if (max > 0 && attempts > max) {
                            log("error: reconnect attempts exhausted ($attempts)")
                            setState(TunnelState.ERROR)
                            return@launch
                        }
                        log("reconnect in ${delayMs / 1000}s (attempt $attempts)")
                        delay(delayMs)
                        if (stopping) return@launch
                        cleanupQuiet()
                        setState(TunnelState.CONNECTING)
                        log("reconnecting")
                        performConnect()
                        if (state.value != TunnelState.CONNECTED) {
                            delay(delayMs)
                        } else {
                            attempts = 0
                        }
                    }

                    TunnelState.RECONNECTING -> {
                        delay(delayMs)
                        if (stopping) return@launch
                        if (respawnBox()) {
                            attempts = 0
                            setState(TunnelState.CONNECTED)
                        } else {
                            attempts++
                            if (max > 0 && attempts > max) {
                                log("error: reconnect attempts exhausted ($attempts); kill switch keeps blocking")
                                setState(TunnelState.ERROR)
                                return@launch
                            }
                            log("box restart failed (attempt $attempts)")
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun connectionAlive(): Boolean = when {
        iface != null -> RootShell.run("ip link show dev ${iface}", 2000).ok
        boxDaemon != null -> boxDaemon?.state?.value == DaemonState.RUNNING
        openvpnDaemon != null -> openvpnDaemon?.state?.value == DaemonState.RUNNING
        torDaemon != null -> torDaemon?.state?.value == DaemonState.RUNNING
        else -> false
    }

    // --- Отключение и уборка ---

    private suspend fun performDisconnect(userInitiated: Boolean) {
        if (userInitiated) stopping = true
        watchJob?.cancel()
        watchJob = null

        disableKillSwitch()
        disableUidZeroBypass()

        val name = iface
        iface = null
        if (name != null) {
            val r = RootShell.run("ip link del dev $name", 3000)
            if (r.ok) log("interface $name removed")
            else log("warning: interface removal: ${lastLine(r.output)}")
        }

        boxDaemon?.stop(); boxDaemon = null
        openvpnDaemon?.stop(); openvpnDaemon = null
        torDaemon?.stop(); torDaemon = null
        VpnTunnel.close()
        tunnelHeld = false
        lastBoxBuilt = null

        if (userInitiated) {
            log("disconnected")
            setState(TunnelState.DISCONNECTED)
        }
    }

    private fun cleanupQuiet() {
        disableKillSwitch()
        disableUidZeroBypass()
        val name = iface
        iface = null
        if (name != null) RootShell.run("ip link del dev $name", 3000)
        boxDaemon?.let { runCatching { it.stop() } }
        boxDaemon = null
        openvpnDaemon?.let { runCatching { it.stop() } }
        openvpnDaemon = null
        torDaemon?.let { runCatching { it.stop() } }
        torDaemon = null
        runCatching { VpnTunnel.close() }
        tunnelHeld = false
        lastBoxBuilt = null
    }

    // --- Мелкая механика ---

    private suspend fun awaitState(daemon: DaemonEngine, target: DaemonState, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = daemon.state.value
            if (current == target) return true
            if (current == DaemonState.ERROR && target != DaemonState.ERROR) return false
            delay(200)
        }
        return daemon.state.value == target
    }

    private fun must(result: RootShell.Result, what: String) {
        if (!result.ok) error("$what failed: ${lastLine(result.output)}")
    }

    private fun lastLine(output: String): String =
        output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "no output"

    private fun ifaceName(profile: VpnProfile): String {
        val base = (profile.id.ifBlank { profile.name }).filter { it.isLetterOrDigit() }
        return ("z" + base.take(7)).take(15).ifBlank { "zappwg" }
    }

    private fun formatEndpoint(host: String, port: Int): String =
        if (':' in host) "[$host]:$port" else "$host:$port"

    private fun writeSharedFile(relative: String, content: String): String? {
        val dir = "/data/local/tmp/zapp"
        val path = "$dir/$relative"
        val parent = path.substringBeforeLast('/')
        if (!RootShell.run("mkdir -p ${RootShell.quote(parent)}", 2000).ok) return null
        val payload = java.util.Base64.getEncoder().encodeToString(content.toByteArray())
        val write = RootShell.run("echo $payload | base64 -d > ${RootShell.quote(path)}", 3000)
        if (!write.ok) return null
        RootShell.run("chmod 600 ${RootShell.quote(path)}", 2000)
        return path
    }

    /** Адреса-цели для kill switch: из профиля, из ссылок, из мостов. */
    private fun endpointsOf(profile: VpnProfile?): List<String> {
        if (profile == null) return emptyList()
        val hosts = LinkedHashSet<String>()
        if (profile.gateway.isNotBlank()) hosts += profile.gateway
        ProxyUri.parse(profile.rawConfig)?.let { hosts += it.server }
        if (profile.protocol == VpnProtocol.OPENVPN.code) {
            Regex("(?m)^\\s*remote\\s+(\\S+)").findAll(profile.rawConfig)
                .forEach { hosts += it.groupValues[1] }
        }
        if (hosts.isEmpty()) return emptyList()
        return runCatching {
            hosts.flatMap { host ->
                runCatching { InetAddress.getAllByName(host).map { it.hostAddress ?: "" } }
                    .getOrDefault(emptyList())
            }.filter { it.isNotEmpty() && !it.startsWith("127.") }.distinct()
        }.getOrDefault(emptyList())
    }

    /** IPv4-адреса из текста мостов: `obfs4 1.2.3.4:443 cert=...`. */
    private fun bridgeAddresses(bridges: String): List<String> =
        Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d{1,5}")
            .findAll(bridges)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    private fun defaultRouteDevice(): String? {
        val r = RootShell.run("ip route show default", 2000)
        if (!r.ok) return null
        return Regex("dev\\s+(\\S+)").find(r.output)?.groupValues?.get(1)
    }

    private companion object {
        const val WATCH_TICK_MS = 3000L

        // Таймаут идёт от вызова start() и покрывает запись файлов
        // демона, а не только сам процесс: групповой конфиг sing-box на
        // тысячах профилей -- мегабайты, и порционная запись через `su`
        // занимает десятки вызовов. 10s здесь обрывали подключение на
        // «no output»: демон ещё писал конфиг, а журнала уже требовали.
        const val START_TIMEOUT_MS = 30_000L

        // Мосты на телефоне собираются десятки секунд: lyrebird отбрасывает
        // мёртвые хосты по таймауту, а до 100% нужно пройти несколько
        // цепочек. Прогон 2.7.3: норут завис на 50%, рут дошёл до 95% --
        // оба упёрлись в двухминутный предел, и оба имели ещё куда идти.
        // Пять минут -- предел, после которого сеть либо есть, либо её
        // нет, и держать пользователя в «Подключении» дольше бессмысленно.
        const val TOR_BOOTSTRAP_MS = 300_000L
        val PRIVATE_V4 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")
    }
}

/**
 * Последний процент bootstrap Tor из строк демона; -1, если tor ещё не
 * печатал прогресс.
 *
 * Ищется с конца: строки приходят потоком, и «Bootstrapped 100%» могло
 * быть перекрыто новыми notice'ами. Чистая функция по журналу -- её же
 * покрывают тесты: неправильный парсер означает либо вечное ожидание,
 * либо преждевременный «ready».
 */
internal fun torProgressPercent(lines: List<DaemonEvent>): Int {
    for (i in lines.indices.reversed()) {
        val marker = "Bootstrapped "
        val idx = lines[i].raw.lastIndexOf(marker)
        if (idx < 0) continue
        val digits = lines[i].raw.substring(idx + marker.length).takeWhile { it.isDigit() }
        val pct = digits.toIntOrNull() ?: continue
        return pct.coerceIn(0, 100)
    }
    return -1
}

/**
 * Тег стадии bootstrap из последней строки `Bootstrapped N% (tag)`.
 *
 * Без скобок -- null: строка без стадии не значит, что стадии не было, она
 * просто пришла в другом формате. Ищется с конца вместе с процентом, чтобы
 * пара «процент + стадия» всегда была из одной строки.
 */
internal fun torProgressStage(lines: List<DaemonEvent>): String? {
    for (i in lines.indices.reversed()) {
        val raw = lines[i].raw
        val marker = "Bootstrapped "
        val idx = raw.lastIndexOf(marker)
        if (idx < 0) continue
        val rest = raw.substring(idx + marker.length)
        val digits = rest.takeWhile { it.isDigit() }
        if (digits.isEmpty()) continue
        val open = rest.indexOf('(', digits.length)
        if (open < 0) continue
        val close = rest.indexOf(')', open)
        if (close < 0) continue
        return rest.substring(open + 1, close).trim().ifEmpty { null }
    }
    return null
}

/**
 * Правило маршрута, выводящее демоны root мимо собственного туннеля.
 *
 * Правила VpnService исключают из tun только uid самого приложения
 * (`addDisallowedApplication`), а правила Android гонят в tun всех
 * остальных:
 *
 *     13000: from all fwmark 0x0/0x20000 iif lo uidrange 0-10236 lookup tun
 *
 * Демоны root (box/tor/wg) сидят на uid0, которого дыра не покрывает:
 * их дайлы входят в собственный tun и зацикливаются. Пока петля жива,
 * Clash API отдаёт `downloadTotal = 0`, реальное скачивание -- 0 байт,
 * а `zapp-errors.log` растёт на мегабайты за полчаса.
 *
 * iptables-марка в mangle OUTPUT здесь **не работает** и раньше вела в
 * заблуждение: маршрут локального соединения выбирается в `connect()`
 * по `sk_mark`, а netfilter меняет mark уже у готового skb. Проверено
 * на устройстве: правило срабатывало на сотни пакетов, а сокеты
 * оставались на 172.19.0.1. Работает только `ip rule` по uid: он
 * исполняется при выборе маршрута и стоит выше tun-правила.
 *
 * Таблица не зашивается по имени (`wlan0`): её отдаёт `ip route get`,
 * выполненный **без** `su`, то есть от uid приложения. Приложение из
 * туннеля исключено, поэтому запрос всегда указывает на действующую
 * дефолтную сеть: на Wi-Fi -- `wlan0`, на симке -- `ccmni0`. Отсюда и
 * [refresh]: после переключения сети старая таблица пустеет, правило
 * перестаёт работать и демоны снова уходят в tun.
 *
 * Ставится только при поднятом VpnService-tun и только в root-билде.
 */
internal object UidZeroBypass {

    /** Между tethering/tun (11000/12000) и tun-правилом VPN (13000). */
    private const val PREF = 12500

    /**
     * Подсеть tun -- на приоритете выше [PREF].
     *
     * Правило [PREF] обязано пропускать мимо туннеля всё подряд, но подсеть
     * самой VPN остаётся рабочей: иначе ответ system-стека sing-box на SYN
     * уходит в дефолтную сеть вместо tun0 и рукопожатие умирает. gvisor
     * такой маршрут не требует, однако правило верно при любом стеке.
     */
    private const val PREF_TUN_SUBNET = 12400

    // Цели вне RFC1918, чтобы маршрут точно ушёл в дефолтную сеть,
    // а не в локальную таблицу.
    private const val PROBE_V4 = "1.1.1.1"
    private const val PROBE_V6 = "2606:4700:4700::1111"

    /** Правило установлено и ждёт снятия; пустой [refresh] работает только с ним. */
    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    /**
     * Ставит правила на текущую дефолтную сеть.
     *
     * true -- правила могли появиться, то есть `remove()` обязан быть
     * вызван при остановке. Судьба отдельных семейств логируется, но
     * на общий результат не влияет: если таблицы не нашлось, повторная
     * попытка нужнее, чем пропуск снятия.
     */
    fun install(logger: (String) -> Unit = {}): Boolean {
        if (!RootShell.available()) return false
        clear()
        active = true
        tunSubnet(logger)?.let { (prefix, table) ->
            val r = RootShell.run("ip rule add pref $PREF_TUN_SUBNET iif lo to $prefix lookup $table", 3000)
            if (r.ok) logger("uid0 bypass: tun subnet $prefix -> $table")
        }
        tableOf(PROBE_V4, v6 = false, logger)?.let { table ->
            val r = RootShell.run("ip rule add pref $PREF iif lo uidrange 0-0 lookup $table", 3000)
            logger(if (r.ok) "uid0 bypass: ipv4 via table $table" else "note: uid0 bypass ipv4 failed: ${r.output.trim().take(80)}")
        }
        tableOf(PROBE_V6, v6 = true, logger)?.let { table ->
            val r = RootShell.run("ip -6 rule add pref $PREF iif lo uidrange 0-0 lookup $table", 3000)
            if (r.ok) logger("uid0 bypass: ipv6 via table $table")
        }
        return true
    }

    /** Снимает оба правила; зовётся при остановке туннеля. */
    fun remove() {
        active = false
        if (!RootShell.available()) return
        clear()
    }

    /**
     * Перечитывает таблицу после смены дефолтной сети.
     *
     * Ничего не делает, пока туннель не поднят: правило нужно ровно
     * столько, сколько живёт tun.
     */
    fun refresh(logger: (String) -> Unit = {}) {
        if (active) install(logger)
    }

    private fun clear() {
        RootShell.run("ip rule del pref $PREF 2>/dev/null", 3000)
        RootShell.run("ip -6 rule del pref $PREF 2>/dev/null", 3000)
        RootShell.run("ip rule del pref $PREF_TUN_SUBNET 2>/dev/null", 3000)
    }

    /**
     * Подсеть tun и её таблица маршрутов.
     *
     * Ищем в таблицах маршрут вида `172.19.0.0/30 dev tun0 ...` -- так kernel
     * описывает адресную зону VPN-интерфейса. Таблица у Android называется
     * по имени самого интерфейса (`tun0`), поэтому имя `dev` и есть ответ.
     *
     * `null` -- tun ещё не поднялся; вызывающий просто пропускает правило.
     */
    private fun tunSubnet(logger: (String) -> Unit): Pair<String, String>? = runCatching {
        val r = RootShell.run("ip -4 route show table all", 4000)
        if (!r.ok) return@runCatching null
        for (line in r.output.lineSequence()) {
            val tokens = line.trim().split(Regex("\\s+"))
            val dev = tokens.indexOf("dev")
            // Первое поле -- префикс (172.19.0.0/30); `local`/`broadcast`/`default`
            // и одиночные адреса вида 8.8.8.8 отбрасываем по отсутствию «/».
            if (dev < 1 || dev + 1 >= tokens.size || !tokens[0].contains('/')) continue
            val name = tokens[dev + 1]
            if (!name.startsWith("tun")) continue
            return@runCatching tokens[0] to name
        }
        logger("note: uid0 bypass: tun subnet not found")
        null
    }.getOrNull()

    /**
     * Таблица маршрута для uid самого приложения.
     *
     * Первый путь -- `ip route get` **без** `su`: процесс остаётся на
     * uid приложения, который правила VPN исключают, поэтому запрос
     * честно отвечает, куда уйдёт трафик в обход туннеля. Второй --
     * правило дефолтной сети из `ip rule show` под root: некоторые
     * прошивки не пускают untrusted_app в netlink-запрос маршрута,
     * а само правило netd переписывает при смене сети сам.
     *
     * `null` -- сети нет; вызывающий обязан сообщить об этом в журнал.
     */
    private fun tableOf(target: String, v6: Boolean, logger: (String) -> Unit): String? {
        routeTable(target, v6)?.let { return it }
        ruleTable(v6)?.let { return it }
        logger("uid0 bypass: no default route for $target")
        return null
    }

    /** Путь 1: маршрут, который получит uid приложения. */
    private fun routeTable(target: String, v6: Boolean): String? = runCatching {
        val argv = buildList {
            // Абсолютный путь: у приложений PATH не гарантирован,
            // а искать `ip` по PATH -- лишний повод не найти.
            add("/system/bin/ip")
            if (v6) add("-6")
            addAll(listOf("route", "get", target))
        }
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        val line = out.trim().lineSequence().firstOrNull().orEmpty()
        val tokens = line.split(Regex("\\s+"))
        val at = tokens.indexOf("table")
        if (code == 0 && at >= 0 && at + 1 < tokens.size) tokens[at + 1] else null
    }.getOrNull()

    /**
     * Путь 2: таблица из правила дефолтной сети.
     *
     * `29998: from all fwmark 0x0/0xffff iif lo uidrange 0-2147483647
     * lookup wlan0` -- именно его netd указывает на текущий дефолтный
     * выход; берём последнее поле (значение после `lookup`).
     */
    private fun ruleTable(v6: Boolean): String? = runCatching {
        val r = RootShell.run(if (v6) "ip -6 rule show" else "ip rule show", 4000)
        if (!r.ok) return@runCatching null
        val fallback = r.output.lineSequence().firstOrNull { it.contains("fwmark 0x0/0xffff") }
        val line = r.output.lineSequence().firstOrNull { it.contains("uidrange 0-2147483647") }
            ?: fallback
        line?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.takeIf { it.isNotBlank() && it != "lookup" }
    }.getOrNull()
}
