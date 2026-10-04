package xyz.azraellab.zapp.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.daemon.DaemonEngine
import xyz.azraellab.zapp.core.daemon.DaemonEvent
import xyz.azraellab.zapp.core.daemon.DaemonId
import xyz.azraellab.zapp.core.daemon.DaemonSpec
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.daemon.createDaemonEngine
import xyz.azraellab.zapp.core.net.ProxyUri
import xyz.azraellab.zapp.core.net.SingboxConfig
import xyz.azraellab.zapp.core.net.WireGuardIni
import xyz.azraellab.zapp.core.native.BinaryResolver
import xyz.azraellab.zapp.core.root.RootShell
import java.io.File
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

    /** Ядерный интерфейс; null, если не поднимали. */
    @Volatile
    private var iface: String? = null

    private var boxDaemon: DaemonEngine? = null
    private var openvpnDaemon: DaemonEngine? = null
    private var torDaemon: DaemonEngine? = null

    private var killSwitchEndpoints: List<String> = emptyList()

    @Volatile
    private var stopping = false

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
        val own = "/data/local/tmp/zapp/bin/(tor|box|openvpn|lyrebird|wg|nfqws|goodbyedpi)"
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

    override fun connect() {
        val job = scope.launch {
            // Двойной тап или VPN+Tor почти одновременно: два
            // performConnect бились бы за порт 9050 и за демон TUNNEL,
            // второй падал с «Address already in use» (и портит состояние
            // первому). Одна попытка -- одна, пока не закончится.
            if (state.value == TunnelState.CONNECTING || state.value == TunnelState.CONNECTED) {
                return@launch
            }
            if (!inFlight.compareAndSet(false, true)) return@launch
            stopping = false
            try {
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

        val vpn = config
        val profile = vpn.activeProfile()
        if (profile == null && !vpn.groupMode) {
            log("error: no active profile")
            setState(TunnelState.ERROR)
            return
        }

        val protocol = VpnProtocol.of(profile?.protocol)
        try {
            when {
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

    // --- Ядерный WireGuard / Amnezia WG ---

    private suspend fun kernelWireGuard(profile: VpnProfile, awg: Boolean) {
        val vpn = config
        val binary = BinaryResolver.resolve("wg")
            ?: error("binary not found: wg")
        val name = ifaceName(profile)
        val conf = WireGuardIni.write(profile)
        val confPath = writeSharedFile("wg/$name.conf", conf) ?: error("cannot write wg config")
        val keyPath = writeSharedFile("wg/$name.key", profile.privateKey) ?: error("cannot write key file")

        if (!RootShell.available()) {
            log("note: no root -- kernel WireGuard cannot be configured")
        }
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

    private suspend fun boxBuilt(built: SingboxConfig.Built, vpn: VpnConfig, profile: VpnProfile?) {
        built.notes.forEach { log(it) }
        if (vpn.killSwitch) enableKillSwitch(endpointsOf(profile), vpn)

        // Android: туннель поднимает VpnService до старта ядра, а fd ядро
        // забирает из unix-сокета сервиса (номер в окружении ненадёжен:
        // ProcessBuilder вырезает унаследованные fd при спавне). Desktop:
        // establish() вернёт null, и ядро откроет /dev/net/tun само.
        val tunnel = vpnTunnelParams(
            built.json, vpn, profile?.name?.takeIf { it.isNotBlank() } ?: "ZAPP"
        )
        if (stopping) error("connection stopped before tunnel start")
        val fd = tunnel?.let { VpnTunnel.establish(it) }

        val daemon = createDaemonEngine(DaemonId.TUNNEL)
        boxDaemon = daemon
        if (stopping) error("connection stopped before box start")
        daemon.start(
            DaemonSpec(
                binary = "box",
                args = listOf("run", "-c", "{{config}}"),
                files = mapOf("config" to built.json),
                env = if (fd != null) {
                    buildMap {
                        VpnTunnel.fdSocketPath?.let { put("ZAPP_TUN_SOCK", it) }
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
        )
        if (!awaitState(daemon, DaemonState.RUNNING, START_TIMEOUT_MS)) {
            error("box failed to start: ${daemonReason(daemon)}")
        }
        log("box is running")
    }

    // --- OpenVPN ---

    private suspend fun openvpnUp(profile: VpnProfile) {
        val vpn = config
        check(profile.rawConfig.isNotBlank()) { "openvpn profile has no config text" }
        if (vpn.killSwitch) enableKillSwitch(endpointsOf(profile), vpn)
        val daemon = createDaemonEngine(DaemonId.TUNNEL)
        openvpnDaemon = daemon
        if (stopping) error("connection stopped before openvpn start")
        daemon.start(
            DaemonSpec(
                binary = "openvpn",
                args = listOf("--config", "{{config}}"),
                files = mapOf("config" to profile.rawConfig)
            )
        )
        if (!awaitState(daemon, DaemonState.RUNNING, START_TIMEOUT_MS)) {
            error("openvpn failed to start: ${daemonReason(daemon)}")
        }
        log("openvpn is running")
    }

    // --- Tor ---

    private suspend fun torUp(profile: VpnProfile, vpn: VpnConfig) {
        check(profile.rawConfig.isNotBlank()) { "tor profile has no bridges" }
        val tor = BinaryResolver.resolve("tor") ?: error("binary not found: tor")
        val transport = BinaryResolver.resolve("lyrebird")
        val socksPort = vpn.torSocksPort.coerceIn(1024, 65535)

        val torrc = buildString {
            appendLine("DataDirectory /data/local/tmp/zapp/tor-data")
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
            val seen = mutableSetOf<String>()
            fun appendBridges(raw: String) {
                for (line in raw.lineSequence().map { it.trim() }) {
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

        RootShell.run("mkdir -p /data/local/tmp/zapp/tor-data", 2000)

        if (stopping) error("connection stopped before tor start")
        ensureSocksPortFree(socksPort)
        val daemon = createDaemonEngine(DaemonId.TUNNEL)
        torDaemon = daemon
        daemon.start(DaemonSpec(binary = tor, args = listOf("-f", "{{torrc}}"), files = mapOf("torrc" to torrc)))
        if (!awaitState(daemon, DaemonState.RUNNING, START_TIMEOUT_MS)) {
            error("tor failed to start: ${daemonReason(daemon)}")
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
            if (pct in 0..99 && pct / 10 > lastPct / 10) {
                lastPct = pct
                log("tor bootstrap $pct%")
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
     * Последняя осмысленная строка журнала демона для ошибки запуска.
     *
     * `lastOrNull().raw` у события без raw (text-событие) даёт пустую
     * строку, и причина сбоя превращалась в «no output» -- ищем с конца
     * то, из чего можно составить причину: raw-строку, затем текст.
     */
    private fun daemonReason(daemon: DaemonEngine): String {
        for (event in daemon.log.value.asReversed()) {
            event.raw.takeIf { it.isNotBlank() }?.let { return it }
            val text = event.text ?: continue
            val rendered = "${text.name} ${event.arg}".trim()
            if (rendered.isNotBlank()) return rendered
        }
        return "no output"
    }

    // --- Kill switch ---

    private fun enableKillSwitch(endpoints: List<String>, vpn: VpnConfig) {
        if (!RootShell.available()) {
            log("note: kill switch requires root and was skipped")
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

    // --- Наблюдение и автопереподключение ---

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
                if (state.value != TunnelState.CONNECTED) continue
                if (connectionAlive()) {
                    attempts = 0
                    continue
                }

                log("connection lost")
                if (!auto) {
                    setState(TunnelState.ERROR)
                    return@launch
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

        if (userInitiated) {
            log("disconnected")
            setState(TunnelState.DISCONNECTED)
        }
    }

    private fun cleanupQuiet() {
        disableKillSwitch()
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
        const val START_TIMEOUT_MS = 10_000L

        // Мосты на телефоне собираются десятки секунд: lyrebird отбрасывает
        // мёртвые хосты по таймауту, а до 100% нужно пройти несколько
        // цепочек. За 60s не уложились на живых мостах -- минута запаса.
        const val TOR_BOOTSTRAP_MS = 120_000L
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
