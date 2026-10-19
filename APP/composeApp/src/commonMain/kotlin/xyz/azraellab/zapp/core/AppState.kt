package xyz.azraellab.zapp.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import xyz.azraellab.zapp.core.daemon.DaemonEvent
import xyz.azraellab.zapp.core.backup.BackupContents
import xyz.azraellab.zapp.core.backup.BackupManifest
import xyz.azraellab.zapp.core.backup.BackupService
import xyz.azraellab.zapp.core.backup.RestoreMode
import xyz.azraellab.zapp.core.backup.WebDavConfig
import xyz.azraellab.zapp.core.backup.createWebDavClient
import xyz.azraellab.zapp.core.catalog.CatalogEntry
import xyz.azraellab.zapp.core.catalog.CatalogFeed
import xyz.azraellab.zapp.core.catalog.CatalogService
import xyz.azraellab.zapp.core.deeplink.DeepLink
import xyz.azraellab.zapp.core.net.httpPostJson
import xyz.azraellab.zapp.core.secure.platformSecureStorage
import xyz.azraellab.zapp.core.warp.buildRegisterRequest
import xyz.azraellab.zapp.core.warp.generateWireGuardKeyPair
import xyz.azraellab.zapp.core.warp.parseRegisterResponse
import xyz.azraellab.zapp.core.warp.toProfile
import xyz.azraellab.zapp.core.warp.warpRegisterUrl
import xyz.azraellab.zapp.core.daemon.DaemonId
import xyz.azraellab.zapp.core.geo.photonReverse
import xyz.azraellab.zapp.core.daemon.DaemonSpec
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.daemon.DpiAppFilter
import xyz.azraellab.zapp.core.daemon.DpiFirewall
import xyz.azraellab.zapp.core.daemon.DpiOverlay
import xyz.azraellab.zapp.core.daemon.createDaemonEngine
import xyz.azraellab.zapp.core.daemon.dpiAppFilter
import xyz.azraellab.zapp.core.domains.AppDomains
import xyz.azraellab.zapp.core.domains.DpiScope
import xyz.azraellab.zapp.core.perApp.PerAppConfig
import xyz.azraellab.zapp.core.perApp.installedApps
import xyz.azraellab.zapp.core.engine.KillSwitchCtl
import xyz.azraellab.zapp.core.engine.SessionKind
import xyz.azraellab.zapp.core.engine.TunnelEngine
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.engine.createTunnelEngine
import xyz.azraellab.zapp.core.engine.isLive
import xyz.azraellab.zapp.core.gps.GpsEngine
import xyz.azraellab.zapp.core.gps.GpsEvent
import xyz.azraellab.zapp.core.gps.GpsFix
import xyz.azraellab.zapp.core.gps.GpsState
import xyz.azraellab.zapp.core.gps.createGpsEngine
import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.log.ErrorLog
import xyz.azraellab.zapp.core.log.causeChain
import xyz.azraellab.zapp.core.log.diagnosticsZip
import xyz.azraellab.zapp.core.log.errorEvent
import xyz.azraellab.zapp.core.native.NativeBinaries
import xyz.azraellab.zapp.core.net.ConfigImport
import xyz.azraellab.zapp.core.net.ClashProbe
import xyz.azraellab.zapp.core.net.ClashStats
import xyz.azraellab.zapp.core.net.LinkProbe
import xyz.azraellab.zapp.core.net.LinkTarget
import xyz.azraellab.zapp.core.netcheck.NetCheckResult
import xyz.azraellab.zapp.core.netcheck.NetCheckService
import xyz.azraellab.zapp.core.traffic.ClashBridge
import xyz.azraellab.zapp.core.traffic.TrafficDay
import xyz.azraellab.zapp.core.traffic.TrafficHistory
import xyz.azraellab.zapp.core.traffic.preferClash
import xyz.azraellab.zapp.core.net.fetchClashNodeProbe
import xyz.azraellab.zapp.core.net.fetchClashProbe
import xyz.azraellab.zapp.core.net.fetchClashRoster
import xyz.azraellab.zapp.core.net.fetchClashTraffic
import xyz.azraellab.zapp.core.net.httpGetBytes
import xyz.azraellab.zapp.core.net.httpGetText
import xyz.azraellab.zapp.core.net.probeLink
import xyz.azraellab.zapp.core.net.probeTarget
import xyz.azraellab.zapp.core.notify.NotifierAction
import xyz.azraellab.zapp.core.notify.createNotifier
import xyz.azraellab.zapp.core.permissions.PermissionGateway
import xyz.azraellab.zapp.core.permissions.PermissionId
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.core.permissions.createPermissionGateway
import xyz.azraellab.zapp.core.probe.DeviceProbe
import xyz.azraellab.zapp.core.probe.ProbeHooks
import xyz.azraellab.zapp.core.probe.StrategyProbe
import xyz.azraellab.zapp.core.probe.StrategyProbeOutcome
import xyz.azraellab.zapp.core.probe.forgetRootProbe
import xyz.azraellab.zapp.core.probe.probeDevice
import xyz.azraellab.zapp.core.speedtest.DUEL_PROBES_PER_SIDE
import xyz.azraellab.zapp.core.speedtest.DuelResult
import xyz.azraellab.zapp.core.speedtest.NodeDuelRunner
import xyz.azraellab.zapp.core.update.APP_VERSION
import xyz.azraellab.zapp.core.update.ReleaseInfo
import xyz.azraellab.zapp.core.update.UpdateDownload
import xyz.azraellab.zapp.core.update.UpdateState
import xyz.azraellab.zapp.core.update.CORE_RELEASES_URL
import xyz.azraellab.zapp.core.update.CoreUpdateInfo
import xyz.azraellab.zapp.core.update.NoticeInfo
import xyz.azraellab.zapp.core.update.ZappComponent
import xyz.azraellab.zapp.core.update.cachedUpdateApk
import xyz.azraellab.zapp.core.update.coreOsArch
import xyz.azraellab.zapp.core.update.downloadUpdateApk
import xyz.azraellab.zapp.core.update.fetchLatestRelease
import xyz.azraellab.zapp.core.update.findCoreBinary
import xyz.azraellab.zapp.core.update.installUpdateApk
import xyz.azraellab.zapp.core.update.localDayEndMs
import xyz.azraellab.zapp.core.update.parseCoreRelease
import xyz.azraellab.zapp.core.update.parseNotice
import xyz.azraellab.zapp.core.update.probeComponentVersion
import xyz.azraellab.zapp.core.update.tarGzEntries
import xyz.azraellab.zapp.core.update.updateAvailable
import xyz.azraellab.zapp.core.update.writeUpdatedCore
import xyz.azraellab.zapp.core.update.zipEntries
import xyz.azraellab.zapp.core.connections.ConnectionsSample
import xyz.azraellab.zapp.core.connections.ConnectionsSource
import xyz.azraellab.zapp.core.connections.createConnectionsSource

/**
 * Состояние приложения.
 *
 * Одно место, где лежат настройки, пресеты и наблюдение за трафиком. Отдельный
 * объект, а не набор `remember`-переменных, потому что настройки должны
 * пережить пересоздание Activity, а пресеты -- быть доступны с любого экрана.
 *
 * Запись в файл отложенная: переключатели дёргаются часто, а синхронная запись
 * JSON на каждый чих -- это лишний ввод-вывод на пустом месте. Запись
 * объединяется и делается на фоне.
 */
class AppState(
    private val store: ConfigStore = createConfigStore(),
    private val gateway: PresetFileGateway = createPresetFileGateway(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        /**
         * Единственный экземпляр на процесс.
         *
         * Система может пересоздать Activity (смена activity-alias для
         * иконки, поворот, пересборка задачи лаунчером), и без этого
         * холдера каждый раз рождался бы второй AppState со своими
         * движками: два туннеля дрались бы за порт 9050, а подписки
         * тикали бы вдвое. Живёт он в Companion, а не в Activity, --
         * процесс переживает пересоздание, холдер тоже.
         */
        @Volatile
        private var instance: AppState? = null

        fun obtain(): AppState = instance ?: synchronized(this) {
            instance ?: AppState().also { instance = it }
        }

        /** Экземпляр, если уже создан; для shutdown-хука, чтобы не плодить второй. */
        fun peek(): AppState? = instance

        /** Отложенная запись: столько ждём после последнего изменения. */
        const val SAVE_DEBOUNCE_MS = 400L

        /** Как часто опрашивать счётчики трафика при включённом мониторинге */
        const val POLL_FLOOR_MS: Int = 200

        /** Период живой таблицы соединений: диагностический снимок, не график. */
        const val CONNECTIONS_POLL_MS: Long = 2_000L

        /** Сколько дней истории трафика держим в конфиге. */
        const val TRAFFIC_KEEP_DAYS: Int = 30

        /** Метки уведомлений: одна метка = одна строка в шторке. */
        const val TAG_GPS: String = "gps"
        const val TAG_UPDATE: String = "update"

        /** Как часто смотрим, не пора ли обновить источники коннектов. */
        const val SOURCE_REFRESH_TICK_MS: Long = 60_000L

        /** Сколько коннектов проверяем параллельно: 32 быстро меряет
         *  сотни ссылок и всё ещё щадит мобильную сеть. */
        const val CHECK_PARALLEL: Int = 32

        /** Через сколько результатов писать их в список: плавность против шума. */
        const val CHECK_FLUSH_BATCH: Int = 8

        /** Как часто пингуем активное подключение для плашки. */
        const val PING_TICK_MS: Long = 5_000L

        /**
         * Пауза сторожа «Подключено, но туннель мёртв» после подключения.
         *
         * Не «сколько ждём трафик» -- трафик при простое может не идти
         * вовсе, и раньше сторож на этом врал. Просто даём боксу время
         * разогреться перед единственной активной пробой.
         */
        /** Как часто пробуем узел на реальном обмене, пока подключены. */
        const val NODE_EXCHANGE_TICK_MS: Long = 60_000L

        /**
         * Сколько узлов ростера замеряется за тик.
         *
         * Группа -- сотни узлов, и все разом это шторм проб на минуту.
         * Восемь по кругу: за тик -- восемь замеров, полный проход
         * списка занимает время, а не кадр.
         */
        const val EXCHANGE_BATCH: Int = 8

        /** Бюджет на чтение ростера: локальный запрос, ответ -- быстро. */
        const val ROSTER_TIMEOUT_MS: Int = 5_000

        /** Разница задержки, при которой замер считается изменившимся. */
        const val HEALTH_TOLERANCE_MS: Int = 50

        /** Свежим замер считается 10 минут; дальше пишем всегда. */
        const val HEALTH_REFRESH_MS: Long = 600_000L

        const val TUNNEL_WATCHDOG_MS: Long = 15_000L

        /** Таймаут самой пробы: узел за границей на симке отвечает и за 4 с. */
        const val TUNNEL_PROBE_TIMEOUT_MS: Int = 8_000

        /** Сколько раз пробуем, прежде чем признать туннель мёртвым. */
        const val PROBE_ATTEMPTS: Int = 2

        /** Пауза между пробами: группа узлов могла ещё выбирать рабочий. */
        const val PROBE_RETRY_MS: Long = 5_000L

        /**
         * Куда шлётся проба: лёгкий ответ 204, без тела и без чужих доменов.
         *
         * Годится только адрес, до которого доходит большинство узлов:
         * недоступная цель дала бы «туннель мёртв» на исправной сети.
         */
        const val WATCHDOG_PROBE_URL: String = "https://www.gstatic.com/generate_204"

        /** Таймаут одного замера пинга: дольше -- это уже мёртвый узел. */
        const val PING_TIMEOUT_MS: Int = 2_000

        /** Сколько кандидатов группы пробуем, пока не найдётся живой. */
        const val GROUP_PING_CANDIDATES: Int = 4

        /** Как часто автовыбор пересматривает мосты Tor. */
        const val TOR_SWITCH_TICK_MS: Long = 60_000L

        /** Сколько ближайших кандидатов меряем за один пересмотр. */
        const val TOR_SWITCH_PROBES: Int = 4

        /**
         * Выигрыш пинга, ради которого имеет смысл обрывать соединение.
         * Больше гистерезиса sing-box urltest (10 ms): тор-мосты прыгают
         * сами по себе, и переподключение на 12 ms дешевле не станет.
         */
        const val TOR_SWITCH_TOLERANCE_MS: Int = 50

        /** Пауза перед автоперезапуском упавшего демона. */
        const val DAEMON_RESTART_DELAY_MS: Long = 3_000L

        /**
         * Пауза между остановкой карт при смене механизма обхода и их
         * повторным стартом: SIGKILL умирает асинхронно, и сокет должен
         * освободиться до bind нового слушателя на том же порту.
         */
        const val BACKEND_SWAP_SETTLE_MS: Long = 300L
    }

    /**
     * Текущие настройки. Меняются целиком -- так проще рассуждать и не забыть поле.
     *
     * Хранятся в snapshot-состоянии, а не обычной переменной: страницы читают
     * конфиг прямо в композиции, и без наблюдаемого поля ни одно изменение --
     * ни новый профиль, ни переключатель -- не перерисовало бы экран.
     */
    var config: AppConfig by mutableStateOf(AppConfig())
        private set

    var presets: List<Preset> by mutableStateOf(emptyList())
        private set

    /** Возможности платформы; считаются один раз, они не меняются за сессию. */
    val caps: PlatformCaps = platformCaps()

    // --- Разрешения, подмена, разведка, обновления ---

    /** Разрешения: единый шлюз, UI подписан на поток статусов. */
    val permissions: PermissionGateway = createPermissionGateway()

    /** Движок подмены координат. */
    val gpsEngine: GpsEngine = createGpsEngine()

    /** Демоны обхода: журнал и состояние каждого свои, запуск -- общий. */
    val zapretDaemon = createDaemonEngine(DaemonId.ZAPRET)
    val dpiDaemon = createDaemonEngine(DaemonId.GOODBYEDPI)

    /** Движок подключения туннеля. */
    val tunnel: TunnelEngine = createTunnelEngine()

    private val notifier = createNotifier()

    /**
     * Последняя причина, о которой GPS уже рассказал.
     *
     * Дедуп heads-up'а: одна и та же причина всплывает один раз на эпизод
     * (сбрасывается при ACTIVE/OFF), новая причина -- снова всплывает.
     */
    private var lastGpsAlertCause: String? = null

    /** Результат разведки устройства; null, пока не проверили. */
    private val _probe = MutableStateFlow<DeviceProbe?>(null)
    val probe: StateFlow<DeviceProbe?> = _probe.asStateFlow()

    /**
     * Зарегистрировано ли приложение на вход в систему (десктоп).
     *
     * Правда живёт в самой системе (Run-ключ, файл автозапуска, Login
     * Items), поэтому здесь -- результат последнего чтения, а не
     * конфиг: Android этот тумблер ведёт через bootAutostart.
     */
    private val _autostartOn = MutableStateFlow(false)
    val autostartOn: StateFlow<Boolean> = _autostartOn.asStateFlow()

    /** Итог проверки обновлений и последний найденный релиз. */
    private val _updateState = MutableStateFlow(UpdateState.UNKNOWN)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /**
     * Причина неудачной проверки обновлений.
     *
     * Состояние ERROR само по себе ничего не говорит: «Не удалось
     * проверить» без текста исключения -- та же голая ошибка, что и
     * «Ошибка» без причины. Пусто, если проверка не падала.
     */
    var updateError: String? by mutableStateOf(null)
        private set

    private val _release = MutableStateFlow<ReleaseInfo?>(null)
    val release: StateFlow<ReleaseInfo?> = _release.asStateFlow()

    /** Объявление разработчика из тела релиза; null -- блока нет. */
    private val _devNotice = MutableStateFlow<NoticeInfo?>(null)
    val devNotice: StateFlow<NoticeInfo?> = _devNotice.asStateFlow()

    /**
     * Загрузка APK последнего релиза в кеш.
     *
     * Отдельно от [updateState]: проверка могла пройти, а сеть на
     * загрузке упасть -- пользователь должен увидеть именно отказ
     * загрузки, а не «проверьте обновление снова».
     */
    private val _updateDownload = MutableStateFlow(UpdateDownload.NONE)
    val updateDownload: StateFlow<UpdateDownload> = _updateDownload.asStateFlow()

    /** Причина неудачной загрузки; пусто, если не качали или всё вышло. */
    var updateDownloadError: String? by mutableStateOf(null)

    /** Прогресс загрузки APK: проценты или null, если размер неизвестен. */
    var updateDownloadPercent: Int? by mutableStateOf(null)
        private set

    private val _netCheckResults = MutableStateFlow<List<NetCheckResult>>(emptyList())

    /** Итоги последнего сканера задержки; пусто, пока не проверяли. */
    val netCheckResults: StateFlow<List<NetCheckResult>> = _netCheckResults.asStateFlow()

    private val _netCheckRunning = MutableStateFlow(false)

    /** Идёт ли сейчас скан; кнопка гаснет, пока пробы не закончатся. */
    val netCheckRunning: StateFlow<Boolean> = _netCheckRunning.asStateFlow()

    private val netCheckService = NetCheckService.real()

    /** Лучший живой узел по задержке; null -- живых нет или не проверяли. */
    val netCheckBest: NetCheckResult?
        get() = netCheckService.best(_netCheckResults.value)

    /** Пробивает узлы параллельно и кладёт итоги в [netCheckResults]. */
    suspend fun runNetCheck() {
        if (_netCheckRunning.value) return
        _netCheckRunning.value = true
        val results = runCatching { netCheckService.check(NetCheckService.defaultNodes()) }
            .getOrDefault(emptyList())
        _netCheckResults.value = results
        _netCheckRunning.value = false
        AppLog.log("netcheck", "checked ${results.size} nodes, best=${results.size}")
    }

    private var trafficDays = TrafficHistory()

    private val _todayTraffic = MutableStateFlow(TrafficDay())

    /** Трафик за сегодня; пусто, пока не накопилось. */
    val todayTraffic: StateFlow<TrafficDay> = _todayTraffic.asStateFlow()

    private val _weekTraffic = MutableStateFlow(TrafficDay())

    /** Трафик за последние 7 дней. */
    val weekTraffic: StateFlow<TrafficDay> = _weekTraffic.asStateFlow()

    /** Складывает байты в историю по дням и обновляет «сегодня/неделя». */
    private fun accumulateTraffic(atMs: Long, downBytes: Long, upBytes: Long) {
        trafficDays.add(atMs, downBytes, upBytes)
        _todayTraffic.value = trafficDays.today(atMs)
        _weekTraffic.value = trafficDays.thisWeek(atMs)
    }

    /** Пишет историю в конфиг (без переприменения движков), старые дни режет. */
    private fun saveTrafficHistory() {
        trafficDays.prune(System.currentTimeMillis(), TRAFFIC_KEEP_DAYS)
        val encoded = trafficDays.encode()
        if (encoded == config.trafficHistory) return
        config = config.copy(trafficHistory = encoded)
        scheduleSave()
    }

    /** Текущая версия -- та же константа, что участвует в сравнении. */
    val appVersion: String = APP_VERSION

    /** Итог последней операции с пресетом: сообщение и признак успеха. */
    var lastMessage: PresetMessage? = null
        private set

    private var saveJob: Job? = null
    private var meter: TrafficMeter = TrafficMeter()
    private var pollJob: Job? = null

    /**
     * Была ли загрузка уже выполнена.
     *
     * Синглтон переживает пересоздание Activity (та же смена launcher-
     * иконки), а плитки шторки поднимают процесс вообще без окна --
     * повторный load() создал бы вторую подписку watchDaemons и
     * sourceRefreshLoop и сбросил бы живые счётчики трафика.
     */
    private var loaded = false

    /**
     * Конфиг прочитан и состояние готово к отрисовке.
     *
     * Первый кадр UI стартует до `load()`: без этого флага страницы
     * успевали показать «нет коннектов» как настоящее состояние, а это
     * лишь миллисекунды чтения конфига.
     */
    val booted = MutableStateFlow(false)

    // Объявлен до init: поле, объявленное после init-блока, инициализируется
    // ПОСЛЕ него, и ранний запуск подписки перезаписал бы только что
    // сохранённую ссылку нулём -- опрос скорости нельзя потерять так.
    private var linkJob: Job? = null

    // Те же два правила, что и у linkJob: объявлены до init, чтобы ранняя
    // подписка не обнулила только что созданные job'ы.
    private var pingJob: Job? = null
    private var torSwitchJob: Job? = null
    private var watchdogJob: Job? = null

    // Здоровье узла по реальному обмену: те же правила, что у
    // watchdogJob, -- объявлен до init, чтобы ранний старт не потерял
    // ссылку на живую корутину.
    private var nodeExchangeJob: Job? = null

    init {
        // Уведомления о подмене: движок сообщает только состояния, а что
        // именно сказать пользователю -- решает здесь общая подписка,
        // потому что события возникают из кода, где нет доступа к UI.
        recordGpsHistory()
        // Системный прокси: восстановление после краша. Снимок
        // остался с прошлой сессии -- значит, приложение упало, пока
        // прокси был взят на себя, и системе показывают прежнее
        // состояние. Без снимка вызов -- дешёвый no-op.
        scope.launch { runCatching { SystemProxyCtl.restore() } }
        // Kill switch после краша: цепочки/правила убитого туннеля
        // продолжают блокировать сеть, пока о них не вспомнят. Если
        // блокировка не ставилась -- вызов дешёвый no-op.
        scope.launch { runCatching { KillSwitchCtl.remove { AppLog.log("killswitch", it) } } }
        // Автозапуск: статус читается из системы при старте, тумблер
        // показывает реальную запись, а не былый клик. Платформы без
        // поддержки (Android) идут через bootAutostart, здесь нечего читать.
        if (AutostartCtl.supported) {
            scope.launch { _autostartOn.value = runCatching { AutostartCtl.status() }.getOrDefault(false) }
        }
        scope.launch {
            var previous = gpsEngine.state.value
            gpsEngine.state.collect { current ->
                if (current != previous) {
                    when (current) {
                        GpsState.ACTIVE -> {
                            notify(Str.NOTIFY_GPS_ON, tag = TAG_GPS)
                            lastGpsAlertCause = null
                        }
                        GpsState.ERROR -> {
                            // Честный отказ -- heads-up с причиной и кнопкой
                            // «открыть настройки», но один раз на эпизод:
                            // дребезжащий цикл «ошибка -> старт -> ошибка»
                            // не должен забивать шторку одной и той же строкой.
                            val cause = gpsErrorCause()
                            if (cause != lastGpsAlertCause) {
                                notify(
                                    Str.NOTIFY_GPS_ERROR,
                                    arg = cause,
                                    tag = TAG_GPS,
                                    action = NotifierAction.OPEN_SETTINGS,
                                    actionLabel = Str.PERM_OPEN_SETTINGS
                                )
                                lastGpsAlertCause = cause
                            }
                        }
                        GpsState.OFF -> if (previous == GpsState.ACTIVE || previous == GpsState.ERROR) {
                            notify(Str.NOTIFY_GPS_OFF, tag = TAG_GPS)
                            lastGpsAlertCause = null
                        }
                        GpsState.STARTING -> Unit
                    }
                    previous = current
                }
            }
        }

        // Статус туннеля: плашка скорости и иконка приложения живут на
        // одном событии. Подключено -- единственный момент, когда есть
        // что мерить и чем гордиться на рабочем столе.
        //
        // Обёртка в runCatching обязательна: scope без изоляции убивался
        // бы одним исключением в обработчике, и с ним вместе помирали бы
        // все остальные подписки -- скорость, иконка, GPS. Ошибка пишется
        // в журнал, а подписка продолжает жить.
        scope.launch {
            // Предыдущее состояние -- только для хаптика: подписка получает
            // и начальное значение, а на нём вибрировать не о чем.
            var previous: TunnelState? = null
            tunnel.state.collect { current ->
                AppLog.log("state", "tunnel -> $current")
                // Такт на смене состояния: подключение, разрыв, ошибка.
                // runCatching внутри actual -- здесь пусть тоже не валит
                // подписку из-за отсутствия мотора или запрета вибрации.
                previous?.let { from ->
                    val event = when {
                        current == TunnelState.CONNECTED -> HapticEvent.CONNECTED
                        current == TunnelState.ERROR -> HapticEvent.ERROR
                        from == TunnelState.CONNECTED &&
                            current == TunnelState.DISCONNECTED -> HapticEvent.DISCONNECTED
                        else -> null
                    }
                    if (event != null) runCatching { Haptics.pulse(event) }
                }
                previous = current
                runCatching {
                    if (current == TunnelState.CONNECTED) {
                        startLinkSpeed()
                        startPing()
                        startTorAutoSwitch()
                        startTunnelWatchdog()
                        startNodeExchange()
                        // Опрос счётчиков нужен спарклайну: пока туннель
                        // поднят, держим его живым, чтобы история копилась
                        // без открытого окна мониторинга. Здесь, а не в
                        // плашке: пассивную сессию обхода вкладки не
                        // показывают, но трафик по ней идёт.
                        ensureTrafficPolling()
                        // Системный прокси: включённый тумблер --
                        // сигнал браузерам уходить через локальные входы.
                        if (config.systemProxy) SystemProxyCtl.apply()
                    } else {
                        stopLinkSpeed()
                        stopPing()
                        stopTorAutoSwitch()
                        stopTunnelWatchdog()
                        stopNodeExchange()
                        // Остановка и любое промежуточное состояние --
                        // возврат по снимку; без снимка это no-op.
                        SystemProxyCtl.restore()
                    }
                    LauncherIcon.setConnected(current == TunnelState.CONNECTED)
                }.onFailure { e ->
                    AppLog.log("state", "handler failed: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    // --- Скорость туннеля для плашки и уведомления ---

    /**
     * Опрос счётчиков туннеля раз в секунду, пока подключены.
     *
     * Отдельный от мониторинга трафика цикл: мониторинг зависит от
     * пользовательской настройки и крутится только при открытом окне,
     * а плашка должна жить при любом подключении. Свой -- значит и свой
     * базовый срез: общий meter двойным опросом сломал бы дельты.
     */
    private fun startLinkSpeed() {
        if (linkJob?.isActive == true) return
        AppLog.log("spd", "ticker start")
        linkJob = scope.launch {
            val source = trafficSource()
            var previous: TrafficSample? = null
            var clashError: Throwable? = null
            var ticks = 0
            while (isActive) {
                val mark = source.nowMs()
                val sample = runCatching { source.read(mark) }.getOrNull()
                // clash_api -- источник, не требующий ни root, ни прав на
                // /proc: сам box владеет tun и отдаёт готовую скорость
                // байт/с. Если clash_api выключен или box не отвечает
                // -- падаем на интерфейсные счётчики. Причина сбоя -- в
                // журнал вместе с периодической строкой, а не молча в null.
                val clash = if (config.vpn.clashApiEnabled) {
                    runCatching {
                        fetchClashTraffic(config.vpn.clashApiPort, config.vpn.clashApiSecret, 3_000)
                    }
                        .onSuccess { clashError = null }
                        .onFailure { clashError = it }
                        .getOrNull()
                } else null
                var speedSrc = "-"
                val speed = when {
                    // Оценочные фолбэки (netd-бакеты, сумма устройства) на
                    // части прошивок мертвы при живом трафике, и тогда
                    // источник истины -- box: /traffic отдаёт скорость
                    // напрямую, вычитать из неё нечего.
                    preferClash(sample) && clash != null -> {
                        speedSrc = "clash"
                        LinkSpeed(downBps = clash.down, upBps = clash.up)
                    }
                    // Настоящие счётчики интерфейсов (native/proc/sysfs/su,
                    // живой TrafficStats) точнее: дельты по ним видны сразу
                    // и считают весь трафик устройства.
                    sample != null && sample.interfaces.isNotEmpty() -> {
                        speedSrc = "iface"
                        previous?.let { linkSpeedBetween(it, sample) }
                    }
                    else -> null
                }
                if (sample != null) previous = sample
                LinkSpeedBus.publish(speed)
                // Скорость -- байт/сек; за секундный тик складываем байты
                // в историю по дням («за сегодня/за неделю»).
                if (speed != null) accumulateTraffic(mark, speed.downBps / 8, speed.upBps / 8)
                // Первые тики и каждое десятое -- чтобы видеть и сам
                // факт работы, и не заливать журнал письмами в секунду.
                if (ticks < 3 || ticks % 10 == 0) {
                    AppLog.log(
                        "spd",
                        "tick=$ticks clash=${clash != null} err=${clashError?.causeText() ?: "-"} " +
                            "ifaces=${sample?.interfaces?.size ?: 0} src=$speedSrc speed=$speed"
                    )
                }
                ticks++
                delay(1000)
            }
        }
    }

    /** Опрос снят: скорость неизвестна, плашка прячется, уведомление чистится. */
    private fun stopLinkSpeed() {
        if (linkJob != null) AppLog.log("spd", "ticker stop")
        linkJob?.cancel()
        linkJob = null
        LinkSpeedBus.publish(null)
        // Сессия закончилась -- фиксируем накопленную историю по дням.
        saveTrafficHistory()
    }

    // --- Пинг активного подключения для плашки ---

    /**
     * Замер отклика до коннекта, с которым стоим подключены.
     *
     * Отдельный цикл, а не внутри опроса скорости: у них разная
     * частота (секунда против пяти) и разная цена -- счётчики
     * читаются из `/proc`, пинг уходит в сеть и не должен дублировать
     * работу, пока не понадобился. Проба берёт ту же адресацию, что и
     * список ([probeTarget]), поэтому плашка меряет ровно тот узел,
     * что показан активным.
     *
     * Ошибки пробы не валит цикл: пинга нет -- и ладно, плашка просто
     * не рисует это поле.
     */
    private fun startPing() {
        if (pingJob?.isActive == true) return
        AppLog.log("spd", "ping start")
        pingJob = scope.launch {
            var ticks = 0
            while (isActive) {
                val ms = currentPing()
                PingBus.publish(ms)
                // Первые тики -- чтобы было видно, что замер идёт, а
                // дальше не заливать журнал письмами каждые пять секунд.
                if (ticks < 3) AppLog.log("spd", "ping=$ms")
                ticks++
                delay(PING_TICK_MS)
            }
        }
    }

    /** Замер снят: пинг неизвестен, строка укорачивается. */
    private fun stopPing() {
        if (pingJob != null) AppLog.log("spd", "ping stop")
        pingJob?.cancel()
        pingJob = null
        PingBus.publish(null)
    }

    /**
     * Сторож «Подключено, но туннель мёртв».
     *
     * «Подключено» -- это ещё не работа: пока туннель был чёрной дырой,
     * плашка продолжала красоваться. Пинг тут не помощник -- он меряет
     * путь **в обход** туннеля (см. PingBus) и о туннеле ничего не знает.
     *
     * Проверка **позитивная**. Первый вариант ждал, пока через узел пойдут
     * байты, и врал на простаивающем телефоне: health-check'ы urltest сами
     * по себе ничего не прощупывают (у группы `auto` история остаётся
     * пустой), и при живом туннеле можно просидеть час без единого байта.
     * Теперь box сам гоняет запрос через узел и отвечает задержкой --
     * молчание значит, что туннель не пропускает.
     *
     * Способность api отвечать входит в проверку: без неё мёртвый туннель
     * не отличить от выключенного api, поэтому пробник не стартует, пока
     * clash_api выключен в настройках.
     */
    private fun startTunnelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        if (!config.vpn.clashApiEnabled) return
        val port = config.vpn.clashApiPort
        val secret = config.vpn.clashApiSecret
        watchdogJob = scope.launch {
            delay(TUNNEL_WATCHDOG_MS)
            // Повтор обязателен: в первые секунды группа узлов могла не
            // успеть выбрать рабочий узел, и единственная проба читала бы
            // «туннель мёртв» на сети, которая через пять секунд ожила бы.
            var result: ClashProbe? = null
            var attempt = 0
            while (result == null && attempt < PROBE_ATTEMPTS) {
                if (tunnel.state.value != TunnelState.CONNECTED) return@launch
                attempt++
                result = runCatching {
                    fetchClashProbe(port, secret, WATCHDOG_PROBE_URL, TUNNEL_PROBE_TIMEOUT_MS)
                }.getOrNull()
                if (result == null && attempt < PROBE_ATTEMPTS) {
                    AppLog.log("vpn", "watchdog: probe missed (attempt $attempt), retrying")
                    delay(PROBE_RETRY_MS)
                }
            }
            val probe = result
            if (probe == null) {
                AppLog.log("vpn", "watchdog: the probe through the node did not pass")
                // Стоп раньше причины: `fail` -- это только строка и
                // состояние, бокс он не гасит, и без остановки следующий
                // старт падал бы на занятом порту clash_api.
                runCatching { tunnel.disconnect() }
                tunnel.fail("the tunnel is up, but the probe through the node did not pass")
            } else {
                AppLog.log("spd", "watchdog probe ok: ${probe.delayMs}ms")
                // Первая честная запись здоровья: замер шёл через этот узел,
                // а не мимо него.
                recordNodeExchange(probe)
            }
        }
    }

    /**
     * Здоровье узла из реального обмена через него.
     *
     * `probeLink` меряет только достижимость адреса: до вчерашнего дня
     * профиль висел `alive:true, latencyMs:55`, пока через узел не шло
     * ничего. Проба сторожа идёт через сам узел (box гоняет запрос по
     * `/proxies/<цель>/delay`), и её результат -- единственный замер,
     * который честно говорит «этот узел сегодня пропустил трафик».
     *
     * Пишем не каждый тик: свежий замер с той же задержкой не стоит
     * перезаписи списка (звук перерисовки и записи конфига), а устаревший
     * обновляем всегда, каким бы он ни был.
     *
     * @param quiet не писать в журнал тег без профиля: пачка росписи
     *   при чужом конфиге дала бы восемь одинаковых строк за тик, итог
     *   там пишет вызывающий.
     * @return true, если тег сопоставился с профилем (обновился он или нет).
     */
    private fun recordNodeExchange(probe: ClashProbe, quiet: Boolean = false): Boolean {
        val id = VpnConnects.profileIdByNodeTag(config.vpn.profiles, probe.target)
        if (id == null) {
            // Тег чужого конфига (sing-box/xray как есть) или служебная
            // запись: сопоставлять не с чем, здоровье не трогаем.
            if (!quiet) AppLog.log("ping", "exchange: no profile for tag '${probe.target}'")
            return false
        }
        val now = System.currentTimeMillis()
        val old = config.vpn.profileById(id)?.health ?: LinkHealth()
        val fresh = VpnConnects.exchangeHealth(
            old = old,
            delayMs = probe.delayMs,
            nowMs = now,
            toleranceMs = HEALTH_TOLERANCE_MS,
            windowMs = HEALTH_REFRESH_MS
        ) ?: return true
        applyHealth(listOf(id to fresh))
        AppLog.log("ping", "exchange: ${probe.target} -> ${probe.delayMs}ms (health updated)")
        return true
    }

    /**
     * Периодический обмен с узлами, пока туннель поднят.
     *
     * Сторож проверяет туннель один раз при старте; здоровья он при этом
     * почти не касался, и список продолжал показывать вчерашние значения.
     *
     * В group-режиме api не называет активный узел (`auto.now` пуст,
     * `chains` = `["auto"]`), поэтому цель -- **ростер группы**: каждый
     * тик читается список её участников и пачка из [EXCHANGE_BATCH] узлов
     * идёт по кругу (`ClashStats.nextBatch`). Через полный проход каждый
     * узел получает замер через самого себя ровно один раз.
     *
     * Если ростера нет (api не ответил, одиночный конфиг без группы) --
     * прежний путь: проба через активный узел. Провал пробы здесь ничего
     * не убивает: единичный промах на мобильной сети -- не смерть туннеля
     * (её судит сторож), а здоровье в этот момент остаётся прежним.
     */
    private fun startNodeExchange() {
        nodeExchangeJob?.cancel()
        nodeExchangeJob = null
        if (!config.vpn.clashApiEnabled) return
        val port = config.vpn.clashApiPort
        val secret = config.vpn.clashApiSecret
        nodeExchangeJob = scope.launch {
            var cursor = 0
            while (isActive) {
                delay(NODE_EXCHANGE_TICK_MS)
                if (tunnel.state.value != TunnelState.CONNECTED) return@launch
                val roster = runCatching {
                    fetchClashRoster(port, secret, ROSTER_TIMEOUT_MS)
                }.getOrNull()
                if (roster.isNullOrEmpty()) {
                    runCatching {
                        fetchClashProbe(port, secret, WATCHDOG_PROBE_URL, TUNNEL_PROBE_TIMEOUT_MS)
                    }.getOrNull()?.let { recordNodeExchange(it) }
                    continue
                }
                val (batch, next) = ClashStats.nextBatch(roster, cursor, EXCHANGE_BATCH)
                cursor = next
                var measured = 0
                var mapped = 0
                for (tag in batch) {
                    if (!isActive || tunnel.state.value != TunnelState.CONNECTED) break
                    val probe = runCatching {
                        fetchClashNodeProbe(port, secret, tag, WATCHDOG_PROBE_URL, TUNNEL_PROBE_TIMEOUT_MS)
                    }.getOrNull() ?: continue
                    measured++
                    if (recordNodeExchange(probe, quiet = true)) mapped++
                }
                // Итог пачки одной строкой: и молчание api, и чужой конфиг
                // (ни один тег не сопоставился) иначе не видно вовсе.
                if (measured == 0) {
                    AppLog.log("ping", "exchange: ${batch.size} nodes gave no measurements")
                } else if (mapped == 0) {
                    AppLog.log("ping", "exchange: ${measured} tags matched no profile")
                }
            }
        }
    }

    private fun stopNodeExchange() {
        nodeExchangeJob?.cancel()
        nodeExchangeJob = null
    }

    private fun stopTunnelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Один замер активного подключения; null -- замерять нечего или
     * проба не дала отклика (UDP-тишина -- это «жив», но время не
     * измерить, а показывать ноль вместо миллисекунд нельзя).
     */
    private suspend fun currentPing(): Int? {
        for (target in pingTargets()) {
            val probe = runCatching {
                probeLink(target.host, target.port, target.kind, PING_TIMEOUT_MS)
            }.getOrNull() ?: continue
            if (probe.alive && probe.latencyMs >= 0) return probe.latencyMs
        }
        return null
    }

    /**
     * Цели замера: в обычном режиме -- единственный профиль, в групповом --
     * несколько ближайших кандидатов.
     *
     * urltest в группе выбирает узел сам, а плашка показывает живой замер:
     * если «лучший по пингу» не отвечает (список свежий, latency ещё -1),
     * один промах раньше гасил индикатор целиком. Пробуем несколько
     * кандидатов подряд -- первый живой и есть ответ.
     */
    private fun pingTargets(): List<LinkTarget> {
        val vpn = config.vpn
        if (!vpn.groupMode) {
            return listOfNotNull(VpnConnects.pingProfile(vpn)?.let { probeTarget(it) })
        }
        // У Tor один мост -- замер моста, а не кандидатов: перебирать
        // мосты параллельно с их автовыбором -- двойная работа.
        val active = vpn.activeProfile()
        if (active != null && active.protocol == VpnProtocol.TOR.code) {
            return listOfNotNull(probeTarget(active))
        }
        val seen = LinkedHashSet<String>()
        return VpnConnects.sort(VpnConnects.groupMembers(vpn.profiles), byPing = true)
            .asSequence()
            .mapNotNull { probeTarget(it) }
            .filter { seen.add("${it.host}:${it.port}") }
            .take(GROUP_PING_CANDIDATES)
            .toList()
    }

    // --- Автоподбор мостов Tor ---

    /**
     * Пересмотр выбранного моста, пока подключены через Tor.
     *
     * Автовыбор при подключении -- не конец истории: мосты меняют
     * скорость и падают, а юзер сидит и не знает. Тумблер
     * [VpnConfig.groupMode] включает и выбор при старте, и этот цикл,
     * поэтому ломать соединение без его согласия нельзя.
     *
     * Для VPN переподключение уже делает sing-box urltest (раз в
     * минуту), поэтому здесь только мосты: у них -- свой список, свой
     * выбор и единственный путь к новому узлу -- перезапуск тора.
     */
    private fun startTorAutoSwitch() {
        if (torSwitchJob?.isActive == true) return
        AppLog.log("vpn", "tor auto-switch start")
        torSwitchJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(TOR_SWITCH_TICK_MS)
                runCatching { torAutoSwitchTick() }
                    .onFailure { AppLog.log("vpn", "auto-switch failed: ${it.causeText()}") }
                ticks++
            }
        }
    }

    private fun stopTorAutoSwitch() {
        if (torSwitchJob != null) AppLog.log("vpn", "tor auto-switch stop")
        torSwitchJob?.cancel()
        torSwitchJob = null
    }

    /**
     * Один пересмотр: свежий замер текущего моста и нескольких
     * ближайших кандидатов, затем переключение, если кто-то заметно
     * быстрее (или текущий мёртв).
     *
     * Порядок условий важен: сначала всё, что может отменить такт
     * (режим, состояние, ручная проверка), -- потому что каждый тик
     * пишет health в конфиг, а гонка двух писателей даёт потерянную
     * запись, а не две.
     */
    private suspend fun torAutoSwitchTick() {
        val vpn = config.vpn
        if (!vpn.groupMode) return
        if (tunnel.state.value != TunnelState.CONNECTED) return
        if (_checkProgress.value.running) return
        val current = VpnConnects.pingProfile(vpn) ?: return
        if (current.protocol != VpnProtocol.TOR.code) return

        // Ближайшие по сохранённому пингу: мерить все сто мостов
        // каждую минуту -- это нагрузка на радио впустую.
        val candidates = VpnConnects.sort(
            VpnConnects.torBridges(vpn.profiles).filter { it.id != current.id && it.health.alive != false },
            byPing = true
        ).take(TOR_SWITCH_PROBES)
        if (candidates.isEmpty()) return

        val probed = mutableListOf<Pair<String, LinkHealth>>()
        for (profile in listOf(current) + candidates) {
            val target = probeTarget(profile) ?: continue
            val probe = runCatching {
                probeLink(target.host, target.port, target.kind, PING_TIMEOUT_MS)
            }.getOrElse { e ->
                LinkProbe(alive = false, error = e::class.simpleName.orEmpty().lowercase())
            }
            probed += profile.id to LinkHealth(
                alive = probe.alive,
                latencyMs = probe.latencyMs,
                checkedAtMs = System.currentTimeMillis(),
                error = probe.error
            )
        }
        if (probed.isEmpty()) return
        applyHealth(probed)

        val freshCurrent = config.vpn.profileById(current.id) ?: return
        val better = VpnConnects.betterBridge(
            freshCurrent,
            candidates.mapNotNull { config.vpn.profileById(it.id) },
            TOR_SWITCH_TOLERANCE_MS
        ) ?: return
        AppLog.log(
            "vpn",
            "auto-switch bridge: ${freshCurrent.name.ifBlank { freshCurrent.id }} " +
                "(${freshCurrent.health.latencyMs} ms) -> " +
                "${better.name.ifBlank { better.id }} (${better.health.latencyMs} ms)"
        )
        connectTorBridge(better)
    }

    // --- Мониторинг трафика ---

    private val _trafficHistory = MutableStateFlow<List<TrafficPoint>>(emptyList())
    val trafficHistory: StateFlow<List<TrafficPoint>> = _trafficHistory.asStateFlow()

    private val _trafficSummary = MutableStateFlow(TrafficSummary())
    val trafficSummaryFlow: StateFlow<TrafficSummary> = _trafficSummary.asStateFlow()

    /** Открыто ли отдельное окно мониторинга. Флаг живёт здесь, а не в UI:
     *  страница настроек открывает окно, а корневой экран -- показывает, и
     *  между ними нет общего composabla. */
    private val _trafficWindowOpen = MutableStateFlow(false)
    val trafficWindowOpen: StateFlow<Boolean> = _trafficWindowOpen.asStateFlow()

    var trafficPoints: List<TrafficPoint> = emptyList()
        private set

    var trafficSummary: TrafficSummary = TrafficSummary()
        private set

    private var trafficSource: TrafficSource? = null

    /** Источник счётчиков; создаётся лениво, потому что на десктопе это файлы. */
    fun trafficSource(): TrafficSource =
        trafficSource ?: createTrafficSource().also { trafficSource = it }

    private var connPollJob: Job? = null
    private var connectionsSource: ConnectionsSource? = null

    /** Последний снимок таблицы соединений; пуст, пока окно трафика не открыли. */
    private val _connections = MutableStateFlow(ConnectionsSample.EMPTY)
    val connections: StateFlow<ConnectionsSample> = _connections.asStateFlow()

    /** Источник таблицы соединений; ленив, как и источник счётчиков. */
    fun connectionsSource(): ConnectionsSource =
        connectionsSource ?: createConnectionsSource().also { connectionsSource = it }

    // --- Загрузка и сохранение ---

    /**
     * Загружает настройки и пресеты.
     *
     * Отсутствие файла -- не ошибка: это первый запуск, и тогда применяются
     * значения по умолчанию. Повторный вызов (пересоздание Activity, тап
     * по плитке шторки) ничего не делает: конфиг уже в памяти, а фоновые
     * циклы уже крутятся.
     */
    fun load() {
        if (loaded) return
        loaded = true
        config = runCatching { store.readConfig() }.getOrNull() ?: AppConfig()
        // Висячий выбор из старой версии конфига: id узла -- хеш текста
        // ссылки, обновление источника его могло убрать. Без починки
        // редактор показывал «Профилей нет» при сотнях профилей.
        config = config.copy(vpn = config.vpn.repairedActive())
        DpiOverlay.updateRelay(config.relay)
        DpiOverlay.updateByeDpi(DpiBackend.of(config.dpiBackend), config.byeDpi)
        AppLog.enabled = config.detailLogEnabled
        AppLog.log("app", "start v${APP_VERSION}")
        presets = runCatching { store.readPresets() }.getOrDefault(emptyList())
        meter = TrafficMeter(config.traffic)
        trafficDays = TrafficHistory.decode(config.trafficHistory)
        _todayTraffic.value = trafficDays.today(System.currentTimeMillis())
        _weekTraffic.value = trafficDays.thisWeek(System.currentTimeMillis())
        gpsEngine.applyConfig(config.gps)
        tunnel.applyConfig(config.vpn)
        tunnel.applyPerApp(config.perApp)

        // Разведка, разрешения и обновления -- всё фоновое и независимое:
        // старт не должен ждать сеть или ответ `su`.
        permissions.refresh()
        scope.launch { _probe.value = runCatching { probeDevice() }.getOrNull() }
        scope.launch { checkForUpdates() }

        // Распаковка бинарей, автостарт и источники -- после подготовки
        // нативной части, но старт UI этим не блокируется.
        scope.launch {
            NativeBinaries.awaitReady()
            startAutoServices()
        }
        scope.launch { sourceRefreshLoop() }
        watchDaemons()
        // UI можно показывать: конфиг в памяти, дальше фон сами доработают.
        booted.value = true
    }

    /**
     * Повторная разведка устройства.
     *
     * Сбрасывает кэш пробы root и переспрашивает систему: политику
     * Magisk выдают вне приложения, и без этого «Root: Нет» висел бы
     * на экране до самого перезапуска, ни словом не объясняя, что делать.
     */
    fun reprobe() {
        forgetRootProbe()
        scope.launch { _probe.value = runCatching { probeDevice() }.getOrNull() }
    }

    /** Заменяет настройки целиком и планирует запись. */
    fun update(next: AppConfig) {
        val prevPerApp = config.perApp
        config = next
        DpiOverlay.updateRelay(config.relay)
        DpiOverlay.updateByeDpi(DpiBackend.of(config.dpiBackend), config.byeDpi)
        AppLog.enabled = config.detailLogEnabled
        gpsEngine.applyConfig(config.gps)
        tunnel.applyConfig(config.vpn)
        tunnel.applyPerApp(config.perApp)
        // Выборы приложений меняют NFQUEUE-правила на лету -- демоны
        // живые, а цепочка перестраивается идемпотентно.
        if (config.perApp != prevPerApp) refreshDpiFirewall()
        scheduleSave()
    }

    /** Точечное изменение: удобно для тумблеров и полей. */
    fun mutate(block: (AppConfig) -> AppConfig) {
        val prevPerApp = config.perApp
        config = withDiscoveredDomains(block(config), prevPerApp)
        DpiOverlay.updateRelay(config.relay)
        DpiOverlay.updateByeDpi(DpiBackend.of(config.dpiBackend), config.byeDpi)
        AppLog.enabled = config.detailLogEnabled
        // Движок читает конфиг каждый такт, поэтому здесь достаточно
        // передать ссылку -- такт сам подхватит новые значения.
        gpsEngine.applyConfig(config.gps)
        tunnel.applyConfig(config.vpn)
        tunnel.applyPerApp(config.perApp)
        // Изменение выбора приложений при живых демонах обхода сразу
        // перестраивает цепочку: ждать переподключения нечего.
        if (config.perApp != prevPerApp) refreshDpiFirewall()
        scheduleSave()
    }

    /**
     * Выбор приложений тянет за собой их домены.
     *
     * Область действия обхода собирается сама: смена пакетов в выборе
     * zapret/goodbyedpi запускает поиск связанных доменов, и найденное
     * дописывается к уже введённому -- ручной список не теряется,
     * повторы отсекаются. Срабатывает в mutate, чтобы путь был один для
     * пикера, импорта конфига и пресета.
     */
    private fun withDiscoveredDomains(next: AppConfig, prev: PerAppConfig): AppConfig {
        var out = next
        if (out.perApp.zapret.packages != prev.zapret.packages) {
            val found = AppDomains.discover(out.perApp.zapret.packages)
            out = out.copy(zapret = out.zapret.copy(domains = AppDomains.mergeInto(out.zapret.domains, found)))
            if (found.isNotEmpty()) AppLog.log("zapret", "scope: +${found.size} domains from apps")
        }
        if (out.perApp.goodbyeDpi.packages != prev.goodbyeDpi.packages) {
            val found = AppDomains.discover(out.perApp.goodbyeDpi.packages)
            out = out.copy(goodbyeDpi = out.goodbyeDpi.copy(domains = AppDomains.mergeInto(out.goodbyeDpi.domains, found)))
            if (found.isNotEmpty()) AppLog.log("goodbyedpi", "scope: +${found.size} domains from apps")
        }
        return out
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                runCatching { store.writeConfig(config) }
            }
        }
    }

    /** Немедленное сохранение: нужно перед выходом из приложения. */
    suspend fun flush() {
        saveJob?.cancel()
        withContext(Dispatchers.IO) {
            runCatching { store.writeConfig(config) }
        }
    }

    // --- Пресеты ---

    /** Сохраняет текущие настройки как новый пресет. */
    fun savePreset(name: String, nowMs: Long): Boolean {
        val preset = Preset.from(config, name, nowMs)
        val problems = preset.validate()
        if (problems.isNotEmpty()) {
            lastMessage = PresetMessage.invalid(problems)
            return false
        }
        val ok = runCatching { store.writePreset(preset) }.isSuccess
        presets = runCatching { store.readPresets() }.getOrDefault(presets)
        lastMessage = if (ok) PresetMessage.saved(name) else PresetMessage.failed(name)
        return ok
    }

    /** Применяет пресет к текущим настройкам. */
    fun applyPreset(preset: Preset): Boolean {
        val problems = preset.validate()
        if (problems.isNotEmpty()) {
            lastMessage = PresetMessage.invalid(problems)
            return false
        }
        mutate { preset.applyTo(it) }
        lastMessage = PresetMessage.applied(preset.name)
        return true
    }

    fun deletePreset(id: String) {
        runCatching { store.deletePreset(id) }
        presets = runCatching { store.readPresets() }.getOrDefault(presets)
    }

    /** Выгружает пресет в файл, выбранный системой. */
    suspend fun exportPreset(preset: Preset): Boolean {
        if (!gateway.available) {
            lastMessage = PresetMessage.unavailable
            return false
        }
        val ok = runCatching {
            gateway.write(PresetFiles.export(preset), PresetFiles.suggestedName(preset))
        }.getOrDefault(false)
        lastMessage = if (ok) PresetMessage.exported(preset.name) else PresetMessage.failed(preset.name)
        return ok
    }

    /**
     * Выгрузка текущих настроек как пресета.
     *
     * Коннекты в файл идут только по прямому запросу: по умолчанию
     * список пуст, и файл весит десятки килобайт вместо полутора мегабайтов
     * с ключами всех узлов. Пресет без коннектов при импорте не трогает
     * список принимающей стороны -- см. `Preset.applyTo`.
     */
    suspend fun exportPreset(includeProfiles: Boolean = false): PresetMessage {
        if (!gateway.available) {
            lastMessage = PresetMessage.unavailable
            return PresetMessage.unavailable
        }
        val preset = Preset.from(config, "zapp", System.currentTimeMillis(), includeProfiles)
        val ok = runCatching {
            gateway.write(PresetFiles.export(preset), PresetFiles.suggestedName(preset))
        }.getOrDefault(false)
        lastMessage = if (ok) PresetMessage.exported(preset.name) else PresetMessage.failed(preset.name)
        return lastMessage!!
    }

    /** Загружает пресет из файла и сразу применяет его. */
    suspend fun importPreset(): PresetMessage {
        if (!gateway.available) {
            lastMessage = PresetMessage.unavailable
            return PresetMessage.unavailable
        }
        val text = runCatching { gateway.read() }.getOrNull()
        if (text.isNullOrBlank()) {
            lastMessage = PresetMessage.emptyFile
            return PresetMessage.emptyFile
        }
        val preset = PresetFiles.import(text)
        if (preset == null) {
            lastMessage = PresetMessage.badFile
            return PresetMessage.badFile
        }
        applyPreset(preset)
        return lastMessage ?: PresetMessage.applied(preset.name)
    }

    // --- Бэкап ---

    /** Разобранный архив, ждущий подтверждения. */
    var pendingBackup: BackupContents? by mutableStateOf(null)
        private set

    val backupPreview: BackupManifest?
        get() = pendingBackup?.manifest

    /** Собирает бэкап и сохраняет в файл. */
    suspend fun exportBackup(): PresetMessage {
        if (!gateway.available) {
            lastMessage = PresetMessage.unavailable
            return PresetMessage.unavailable
        }
        val bytes = BackupService.build(config, presets, appVersion, System.currentTimeMillis())
        val name = "zapp-backup"
        val ok = runCatching { gateway.writeBytes(bytes, "$name.zip") }.getOrDefault(false)
        lastMessage = if (ok) PresetMessage.exported(name) else PresetMessage.failed(name)
        return lastMessage!!
    }

    /** Собирает и сохраняет отчёт о проблеме (zip) одной кнопкой. */
    suspend fun exportDiagnostics(): PresetMessage {
        if (!gateway.available) {
            lastMessage = PresetMessage.unavailable
            return PresetMessage.unavailable
        }
        val bytes = diagnosticsZip(
            logs = AppLog.lines.value,
            errors = ErrorLog.lines.value,
            version = appVersion,
            configJson = ConfigCodec.encodeConfig(config)
        )
        val name = "zapp-diagnostics"
        val ok = runCatching { gateway.writeBytes(bytes, "$name.zip") }.getOrDefault(false)
        lastMessage = if (ok) PresetMessage.exported(name) else PresetMessage.failed(name)
        return lastMessage!!
    }

    /** Читает архив и готовит превью; ничего не применяет. */
    suspend fun importBackupPreview(): PresetMessage {
        if (!gateway.available) {
            lastMessage = PresetMessage.unavailable
            return PresetMessage.unavailable
        }
        val bytes = runCatching { gateway.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            lastMessage = PresetMessage.emptyFile
            return PresetMessage.emptyFile
        }
        val contents = BackupService.parse(bytes)
        if (contents == null) {
            lastMessage = PresetMessage.badFile
            return PresetMessage.badFile
        }
        pendingBackup = contents
        lastMessage = PresetMessage.imported(contents.manifest.profileNames.firstOrNull() ?: "backup")
        return lastMessage!!
    }

    /** Применяет подготовленный бэкап. */
    fun applyBackup(mode: RestoreMode) {
        val contents = pendingBackup ?: return
        val newConfig = BackupService.configOf(contents, mode)
        val newPresets = BackupService.presetsOf(contents, mode)
        if (newConfig != null) mutate { newConfig }
        if (newPresets != null) {
            newPresets.forEach { runCatching { store.writePreset(it) } }
            presets = runCatching { store.readPresets() }.getOrDefault(presets)
        }
        pendingBackup = null
        lastMessage = PresetMessage.applied(contents.manifest.profileNames.firstOrNull() ?: "backup")
    }

    /** Отменяет бэкап, ничего не применяя. */
    fun cancelBackup() {
        pendingBackup = null
    }

    // --- WebDAV ---

    private val secure = platformSecureStorage()
    private val webDav = createWebDavClient()

    /** Адрес и логин сервера; пароль лежит в SecureStorage. */
    var webDavConfig: WebDavConfig by mutableStateOf(loadWebDav())
        private set

    private fun loadWebDav(): WebDavConfig =
        runCatching {
            secure.get(webDavSlot)?.let {
                ConfigCodec.json.decodeFromString(WebDavConfig.serializer(), it)
            }
        }.getOrNull() ?: WebDavConfig()

    /** Сохраняет вход на сервер; пароль уходит в хранилище секретов. */
    fun saveWebDav(url: String, login: String, password: String) {
        val merged = WebDavConfig(url.trim(), login.trim(), password.ifEmpty { webDavConfig.password })
        webDavConfig = merged
        val forDisk = merged.copy(password = "")
        runCatching {
            secure.put(webDavSlot, ConfigCodec.json.encodeToString(WebDavConfig.serializer(), forDisk))
            if (password.isNotEmpty()) secure.put(webDavPassSlot, password)
        }
    }

    /** Кладёт бэкап на сервер. */
    suspend fun webDavUpload(): PresetMessage {
        val bytes = BackupService.build(config, presets, appVersion, System.currentTimeMillis())
        val pass = runCatching { secure.get(webDavPassSlot) }.getOrNull() ?: ""
        val ok = webDav.upload(webDavConfig.copy(password = pass), bytes)
        lastMessage = if (ok) PresetMessage.exported("WebDAV") else PresetMessage.failed("WebDAV")
        return lastMessage!!
    }

    /** Тянет бэкап с сервера и готовит превью. */
    suspend fun webDavDownload(): PresetMessage {
        val pass = runCatching { secure.get(webDavPassSlot) }.getOrNull() ?: ""
        val bytes = webDav.download(webDavConfig.copy(password = pass))
        if (bytes == null || bytes.isEmpty()) {
            lastMessage = PresetMessage.badFile
            return PresetMessage.badFile
        }
        val contents = BackupService.parse(bytes)
        if (contents == null) {
            lastMessage = PresetMessage.badFile
            return PresetMessage.badFile
        }
        pendingBackup = contents
        lastMessage = PresetMessage.imported("WebDAV")
        return lastMessage!!
    }

    private val webDavSlot = "backup:webdav"
    private val webDavPassSlot = "backup:webdav:pass"

    // --- Каталог пресетов ---

    /** Загруженный фид каталога; null -- ещё не тянули или не вышло. */
    var catalogFeed: CatalogFeed? by mutableStateOf(null)
        private set

    /** Идёт ли загрузка фида сейчас. */
    var catalogLoading: Boolean by mutableStateOf(false)
        private set

    /** Скачать фид по ссылке; true -- есть что показывать. */
    suspend fun loadCatalog(url: String): Boolean {
        catalogLoading = true
        val feed = runCatching { CatalogService.fetchFeed(url) }.getOrNull()
        catalogLoading = false
        catalogFeed = feed
        return feed != null
    }

    /** Скачивает пресет записи каталога и применяет его. */
    suspend fun installCatalogEntry(entry: CatalogEntry): PresetMessage {
        val preset = runCatching {
            CatalogService.fetchPreset(entry.presetUrl, entry.sha256)
        }.getOrNull()
        if (preset == null) {
            // Отклонён по контрольной сумме или битый: в журнале причина
            // видна, иначе «не установился» выглядело бы как сбой сети.
            if (entry.sha256.isNotBlank()) {
                AppLog.log("catalog", "preset rejected: sha256 or parse failed")
            }
            lastMessage = PresetMessage.badFile
            return PresetMessage.badFile
        }
        applyPreset(preset)
        return lastMessage ?: PresetMessage.applied(preset.name)
    }

    // --- Мониторинг трафика ---

    /**
     * Запускает опрос счётчиков.
     *
     * Опрос идёт в своём coroutine и перезапускается при смене интервала:
     * менять период на лету удобнее, чем пересоздавать цикл вручную.
     */
    fun startTrafficMonitor() {
        pollJob?.cancel()
        if (!config.traffic.enabled) {
            stopTrafficMonitor()
            return
        }
        pollJob = scope.launch {
            val bridge = ClashBridge()
            while (isActive) {
                val period = config.traffic.intervalMs.coerceAtLeast(POLL_FLOOR_MS).toLong()
                val source = trafficSource()
                val mark = source.nowMs()
                var sample = runCatching { source.read(mark) }.getOrNull()
                // Оценочные фолбэки мертвы при живом трафике (netd не видит
                // туннель): метер кормим мостом счётчиков box'а, если
                // clash_api отвечает. Живые интерфейсы точнее и не требуют
                // лишних запросов.
                if (preferClash(sample) && config.vpn.clashApiEnabled) {
                    val clash = runCatching {
                        fetchClashTraffic(config.vpn.clashApiPort, config.vpn.clashApiSecret, 3_000)
                    }.getOrNull()
                    if (clash != null) sample = bridge.sample(clash, mark)
                }
                if (sample != null) {
                    meter.update(sample)
                    trafficPoints = meter.history()
                    _trafficHistory.value = trafficPoints
                    trafficSummary = meter.summary()
                    _trafficSummary.value = trafficSummary
                }
                delay(period)
            }
        }
    }

    /**
     * Гарантирует, что поллинг трафика запущен.
     *
     * Не имеет смысла при выключенном мониторинге: тогда опрос останавливается,
     * даже если что-то его просит запустить. Состояние «запущен ли» проверяется
     * напрямую, а не по факту создания job -- отменённый job остаётся объектом.
     */
    fun ensureTrafficPolling() {
        val running = pollJob?.isActive == true
        when {
            config.traffic.enabled && !running -> startTrafficMonitor()
            !config.traffic.enabled && running -> stopTrafficMonitor()
        }
    }

    /**
     * Живая таблица соединений.
     *
     * Свой цикл, а не общий с монитором: опрос -- блокирующее чтение
     * файлов и команд, поэтому он идёт в IO и с периодом в секунды.
     * Отказ источника -- не исключение для цикла: UI покажет пометку
     * «нет доступа», а не пустую таблицу.
     */
    fun startConnectionsPolling() {
        connPollJob?.cancel()
        connPollJob = scope.launch {
            while (isActive) {
                val sample = withContext(Dispatchers.IO) {
                    runCatching { connectionsSource().read() }.getOrNull()
                }
                if (sample != null) _connections.value = sample
                delay(CONNECTIONS_POLL_MS)
            }
        }
    }

    /** Останавливает опрос таблицы соединений (окно трафика закрыто). */
    fun stopConnectionsPolling() {
        connPollJob?.cancel()
        connPollJob = null
    }

    /** Открывает или закрывает окно мониторинга. */
    fun setTrafficWindowOpen(open: Boolean) {
        _trafficWindowOpen.value = open
        if (open) ensureTrafficPolling() else stopConnectionsPolling()
    }

    suspend fun resetToDefaults() {
        update(AppConfig())
    }

    fun stopTrafficMonitor() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Сбрасывает счётчики и график, не трогая настройки. */
    fun resetTraffic() {
        meter.reset()
        trafficPoints = emptyList()
        _trafficHistory.value = emptyList()
        trafficSummary = TrafficSummary()
        _trafficSummary.value = trafficSummary
    }

    /** Пересоздаёт счётчик при смене настроек отображения. */
    fun rebuildMeter() {
        meter = TrafficMeter(config.traffic)
        resetTraffic()
    }

    // --- Подмена координат ---

    /**
     * Запускает подмену после получения разрешений.
     *
     * Цепочка последовательная: точное местоположение диалогом, потом
     * имитация -- через настройки разработчика, потому что этот AppOps
     * нельзя спросить диалогом. Даже отказ по одному разрешению не
     * обрывает цепочку: движок сам скажет в журнале, чего ему не хватило.
     *
     * [askPermissions] выключается в автостарте: приложение не должно
     * дёргать пользователя системными диалогами при простом открытии.
     * Без разрешений старт тихо откладывается до ручного нажатия.
     */
    fun startGps(askPermissions: Boolean = true, onResult: (Boolean) -> Unit = {}) {
        AppLog.log("gps", "start requested")
        if (!gpsEngine.supported) {
            AppLog.log("gps", "unsupported on this platform")
            onResult(false)
            return
        }

        // Всё строго по одному: два системных диалога подряд система
        // показать не может, и параллельные запросы перепутывают ответы.
        // Порядок: гео, имитация (настройки), уведомления -- и только
        // потом старт движка, который сам проверит, чего ему не хватило.
        val queue = mutableListOf<PermissionId>()
        if (askPermissions) {
            listOf(PermissionId.LOCATION_FINE, PermissionId.LOCATION_MOCK).forEach { id ->
                val state = permissions.statuses.value.firstOrNull { it.id == id }?.state
                if (state == PermissionState.DENIED || state == PermissionState.SETTINGS) {
                    queue += id
                }
            }
            val notifications = permissions.statuses.value
                .firstOrNull { it.id == PermissionId.NOTIFICATIONS }
            if (notifications?.state == PermissionState.DENIED) {
                queue += PermissionId.NOTIFICATIONS
            }
        }

        fun step() {
            if (queue.isEmpty()) {
                // Намерение «включить подмену» уходит в конфиг до старта
                // движка: именно оно переживает рестарт (автостарт читает
                // gps.enabled), а не состояние движка, которое живёт в
                // памяти процесса.
                mutate { it.copy(gps = it.gps.copy(enabled = true)) }
                // Синхронная часть старта (разбор конфига, доступ к сервису)
                // здесь же: исключение иначе пропало бы между диалогом
                // разрешения и состоянием, а статус обязан иметь причину.
                runCatching { gpsEngine.start(config.gps, vpnAddress()) }
                    .onFailure { e ->
                        gpsEngine.fail(GpsEvent(Str.GPS_LOG_START_FAILED, e.causeText()))
                    }
                onResult(true)
                return
            }
            val id = queue.removeAt(0)
            permissions.request(id) { step() }
        }
        step()
    }

    /**
     * Останавливает подмену.
     *
     * Сбрасывает и [GpsConfig.enabled]: «выключено» -- тоже решение
     * пользователя, и после рестарта поднимать подмену заново нельзя.
     */
    fun stopGps() {
        AppLog.log("gps", "stop")
        mutate { it.copy(gps = it.gps.copy(enabled = false)) }
        gpsEngine.stop()
    }

    /** Адрес шлюза активного профиля -- нужен режиму «координаты VPN». */
    fun vpnAddress(): String =
        config.vpn.activeProfile()?.gateway?.trim().orEmpty()

    // --- Избранное и история подмены ---

    /**
     * Кладёт точку в избранное.
     *
     * Дубли по координатам не заводятся: сохранённое место -- это точка,
     * а не запись, и три одинаковых «Кремля» в списке не помогают выбору.
     * Список ограничен: он лежит в настройках, а не в базе.
     */
    fun addGpsFavorite(point: SavedPoint) {
        mutate { cfg ->
            val rest = cfg.gps.favorites.filter {
                !(abs(it.latitude - point.latitude) < COORD_EPS &&
                    abs(it.longitude - point.longitude) < COORD_EPS)
            }
            cfg.copy(gps = cfg.gps.copy(favorites = (listOf(point) + rest).take(LIST_LIMIT)))
        }
    }

    fun removeGpsFavorite(point: SavedPoint) {
        mutate { cfg ->
            cfg.copy(gps = cfg.gps.copy(favorites = cfg.gps.favorites - point))
        }
    }

    fun removeGpsHistory(point: SavedPoint) {
        mutate { cfg ->
            cfg.copy(gps = cfg.gps.copy(history = cfg.gps.history - point))
        }
    }

    /**
     * Запись точки в историю при появлении фикса.
     *
     * Подписка на fix, а не на статус: подмена публикует первую точку
     * через секунду после старта, и именно она есть что записать. Запись
     * одна на эпизод (null -> точка), иначе секундный тик превратил бы
     * историю в копию маршрута.
     */
    private fun recordGpsHistory() {
        scope.launch {
            var previous: GpsFix? = null
            gpsEngine.fix.collect { fix ->
                val was = previous
                previous = fix
                if (fix == null || was != null) return@collect
                if (gpsEngine.state.value != GpsState.ACTIVE) return@collect
                val point = SavedPoint(fix.place, fix.latitude, fix.longitude)
                mutate { cfg ->
                    val rest = cfg.gps.history.filter {
                        !(abs(it.latitude - point.latitude) < COORD_EPS &&
                            abs(it.longitude - point.longitude) < COORD_EPS)
                    }
                    cfg.copy(gps = cfg.gps.copy(history = (listOf(point) + rest).take(LIST_LIMIT)))
                }
                // Подпись места: своя точка приходит без имени, а список
                // из координат читается плохо. Сбой сети -- не беда:
                // остаются координаты.
                if (point.name.isEmpty()) {
                    val named = runCatching { photonReverse(point.latitude, point.longitude) }
                        .getOrNull()?.label
                    if (!named.isNullOrBlank()) {
                        mutate { cfg ->
                            cfg.copy(
                                gps = cfg.gps.copy(
                                    history = cfg.gps.history.map {
                                        if (abs(it.latitude - point.latitude) < COORD_EPS &&
                                            abs(it.longitude - point.longitude) < COORD_EPS
                                        ) it.copy(name = named) else it
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /** Одинаковые координаты в двух записях истории -- одна запись. */
    private val COORD_EPS = 0.00005

    /** Сколько точек живёт в избранном и истории: списки в настройках. */
    private val LIST_LIMIT = 50

    // --- Демоны обхода ---

    /**
     * Запускает zapret.
     *
     * Кнопка «Старт» и переключатель «включено» -- одно и то же действие:
     * состояние в конфиге отражает намерение, а состояние движка -- факт.
     * Перезапуск при живом процессе -- норма: движок сам снимает старый,
     * иначе новые правила не применялись бы.
     */
    fun startZapret() {
        if (!DpiScope.zapret(config.perApp, config.zapret)) {
            AppLog.log("zapret", "start blocked: no apps or domains")
            zapretDaemon.note(DaemonEvent(text = Str.DAEMON_NEED_SCOPE))
            return
        }
        AppLog.log("zapret", "start: ${config.zapret.toArgs().joinToString(" ")}")
        if (!config.zapret.enabled) {
            mutate { it.copy(zapret = it.zapret.copy(enabled = true)) }
        }
        val c = config.zapret
        runCatching {
            zapretDaemon.start(DaemonSpec(binary = c.binaryPath, args = c.toArgs()))
        }.onFailure { e ->
            failDaemon(zapretDaemon, "zapret", e)
        }
    }

    fun stopZapret() {
        AppLog.log("zapret", "stop")
        zapretDaemon.stop()
        if (config.zapret.enabled) {
            mutate { it.copy(zapret = it.zapret.copy(enabled = false)) }
        }
    }

    /**
     * Запускает goodbyedpi.
     *
     * Выключенный режим -- не ошибка, а «пользователь хочет автоматику»:
     * старт переводит его в AUTO. Режим Fake SNI без домена не стартует:
     * `--fake-with-sni` обязателен для аргумента, и пустой роняет демон --
     * честнее сказать об этом до запуска.
     */
    fun startDpi() {
        if (!DpiScope.gdpi(config.perApp, config.goodbyeDpi)) {
            AppLog.log("goodbyedpi", "start blocked: no apps or domains")
            dpiDaemon.note(DaemonEvent(text = Str.DAEMON_NEED_SCOPE))
            return
        }
        AppLog.log("goodbyedpi", "start: ${config.goodbyeDpi.toArgs().joinToString(" ")}")
        if (GoodbyeDpiMode.of(config.goodbyeDpi.mode) == GoodbyeDpiMode.DISABLED) {
            mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(mode = GoodbyeDpiMode.AUTO.code)) }
        }
        val c = config.goodbyeDpi
        if (GoodbyeDpiMode.of(c.mode) == GoodbyeDpiMode.FAKE_SNI && c.fakeSni.isBlank()) {
            dpiDaemon.note(DaemonEvent(text = Str.DAEMON_NO_SNI))
            return
        }
        val hosts = c.domainList()
        val files = if (hosts.isNotEmpty()) mapOf("hosts" to hosts.joinToString("\n")) else emptyMap()
        runCatching {
            dpiDaemon.start(
                DaemonSpec(
                    binary = c.daemonPath,
                    args = c.toArgs(blacklistRef = if (hosts.isNotEmpty()) "{{hosts}}" else null),
                    files = files
                )
            )
        }.onFailure { e ->
            failDaemon(dpiDaemon, "goodbyedpi", e)
        }
    }

    /**
     * Причина исключения для журнала: цепочка до четырёх звеньев.
     *
     * Никогда пусто: строка причины обязана быть чем-то заполнена, иначе
     * под статусом появится пустая строка вместо объяснения. Цепочка
     * (а не только корень) нужна, чтобы было видно, что именно обернуло
     * ошибку: «refused» ничего не говорит без «box -> urltest -> refused».
     */
    private fun Throwable.causeText(): String =
        causeChain().ifEmpty { this::class.simpleName ?: "error" }

    /**
     * Пойманное при запуске демона исключение -- в его журнал и в zapp.log.
     *
     * Без этого состояние оставалось бы «Ошибка» без причины (или, хуже,
     * «Остановлен» с молчаливым исключением в никуда): одна строка тут --
     * и карточка демона, и общий журнал показывают, что именно сломалось.
     */
    private fun failDaemon(engine: xyz.azraellab.zapp.core.daemon.DaemonEngine, tag: String, e: Throwable) {
        AppLog.log(tag, "start failed: ${e.causeText()}")
        engine.fail("start failed: ${e.causeText()}")
    }

    fun stopDpi() {
        AppLog.log("goodbyedpi", "stop")
        dpiDaemon.stop()
    }

    /**
     * Пересаживает механизм обхода без root: релей <-> byedpi.
     *
     * Порт у обоих механизмов один ([DpiOverlay.RELAY_PORT]), поэтому это не
     * переключение тумблера, а пересадка: живые карточки сначала
     * останавливаются -- старый слушатель обязан освободить сокет, -- и только
     * потом поднимаются заново, иначе новый процесс спавнится на занятом
     * порту и умирает сразу. Остановки идут через движки напрямую, а не через
     * stopZapret/stopDpi: те гасят тумблеры «включено», а смена механизма не
     * должна отменять намерение пользователя держать демоны запущенными.
     *
     * Вызывается из UI-потока, поэтому ожидание освобождения порта уходит в
     * фон: главный поток свободен с первой миллисекунды.
     */
    fun setDpiBackend(code: String) {
        val backend = DpiBackend.of(code)
        if (config.dpiBackend == backend.code) return
        AppLog.log("dpi", "backend: ${config.dpiBackend} -> ${backend.code}")
        mutate { it.copy(dpiBackend = backend.code) }
        val up = setOf(DaemonState.STARTING, DaemonState.RUNNING)
        val zapretUp = zapretDaemon.state.value in up
        val dpiUp = dpiDaemon.state.value in up
        if (!zapretUp && !dpiUp) return
        if (zapretUp) zapretDaemon.stop()
        if (dpiUp) dpiDaemon.stop()
        scope.launch {
            delay(BACKEND_SWAP_SETTLE_MS)
            if (zapretUp) startZapret()
            if (dpiUp) startDpi()
        }
    }

    // --- Туннель ---

    /**
     * Поднимает туннель.
     *
     * Системное согласие VpnService здесь не запрашивается намеренно:
     * бэкенд работает через внешний инструмент от root, а не через tun-
     * интерфейс приложения, и диалог «разрешить VPN» ничего не решил бы.
     */
    fun connectVpn() {
        if (!tunnel.supported) return
        // Сессия достаётся вкладке VPN, а релей обхода отдаёт её: её
        // поднимала автоматика, а гасить выбор пользователя нельзя.
        adoptVpnProfile()
        sessionOwner = SessionKind.VPN
        AppLog.log("vpn", "connect requested (group=${config.vpn.groupMode})")
        // Синхронная часть подключения: исключение здесь иначе ушло бы
        // мимо UI, а карточка осталась бы в DISCONNECTED без объяснений.
        runCatching { tunnel.connect() }
            .onFailure { tunnel.fail("connect failed: ${it.causeText()}") }
    }

    /**
     * Приводит активный коннект к виду, пригодному для вкладки VPN.
     *
     * Выбор профиля один на обе вкладки, и на вкладке Tor активным
     * становится мост -- которого список VPN не показывает вовсе, а
     * движок идёт по [VpnConfig.activeProfileId]. Без этой проверки
     * СТАРТ на вкладке VPN поднимал бы tor, что и наблюдалось: пользователь
     * нажал VPN, а получил чужую сессию. Мост заменяется на самый быстрый
     * живой коннект; коннектов нет -- выбор сбрасывается, и движок дал бы
     * внятную «no active profile» вместо молчаливого tor (или соберёт
     * группу, если включён автоподбор).
     */
    private fun adoptVpnProfile() {
        val active = config.vpn.activeProfile()
        if (active == null || active.protocol != VpnProtocol.TOR.code) return
        val best = VpnConnects.sort(config.vpn.visibleProfiles(), byPing = true).firstOrNull()
        val label = best?.let { it.name.ifBlank { it.id } } ?: "no profile"
        AppLog.log("vpn", "bridge was active -- $label")
        mutate { it.copy(vpn = it.vpn.copy(activeProfileId = best?.id ?: "")) }
    }

    fun disconnectVpn() {
        AppLog.log("vpn", "disconnect requested")
        runCatching { tunnel.disconnect() }
            .onFailure { tunnel.fail("disconnect failed: ${it.causeText()}") }
    }

    /**
     * Тумблер системного прокси.
     *
     * Настройка вступает в силу сразу: локальные входы есть в каждом
     * десктопном конфиге ещё до подключения, поэтому применение при
     * живом туннеле указывает на уже слушающий порт. Выключение
     * возвращает системное состояние немедленно.
     */
    fun setSystemProxy(on: Boolean) {
        mutate { it.copy(systemProxy = on) }
        AppLog.log("app", "system proxy -> $on")
        scope.launch {
            runCatching {
                when {
                    !on -> SystemProxyCtl.restore()
                    tunnel.state.value == TunnelState.CONNECTED -> SystemProxyCtl.apply()
                }
            }.onFailure {
                AppLog.log("app", "system proxy toggle failed: ${it.causeText()}")
            }
        }
    }

    /**
     * Тумблер автозапуска на десктопе.
     *
     * Запись в систему -- это и есть применение настройки; после неё
     * статус читается обратно, чтобы тумблер не врал, когда записать
     * не удалось. Состояние не сохраняется в конфиге: оно и так лежит
     * в реестре/файле/Keychain и переживает переустановку.
     */
    fun setAutostart(on: Boolean) {
        AppLog.log("app", "autostart -> $on")
        scope.launch {
            runCatching {
                if (on) AutostartCtl.enable() else AutostartCtl.disable()
            }.onFailure {
                AppLog.log("app", "autostart apply failed: ${it.causeText()}")
            }
            _autostartOn.value = runCatching { AutostartCtl.status() }.getOrDefault(false)
        }
    }

    /**
     * Подключение через Tor: выбирает активный мост и поднимает туннель.
     *
     * Выбор пользователя -- не декорация: если на вкладке Tor выбран
     * источник, СТАРТ идёт через него, а не через первый попавшийся
     * живой. Запасной порядок: выбранный живой -> самый быстрый живой
     * (минимальный пинг -- приоритет автовыбора) -> выбранный мёртвый
     * (tor попробует сам, список в конфиге его).
     *
     * Если туннель уже поднят другим протоколом -- сначала снимается он,
     * иначе движок продолжил бы старое подключение и мост ни на что не
     * повлиял бы. Смена активного профиля и переподключение идут в одной
     * корутине с ожиданием DISCONNECTED: гонка «выбрал, но не успел
     * остановиться» здесь исключена.
     */
    fun connectTor() {
        val bridge = selectTorBridge()
        if (bridge == null) {
            vpnNotice = VpnNotice(Str.TOR_NO_BRIDGES)
            AppLog.log("vpn", "tor connect refused: no bridges")
            return
        }
        connectTorBridge(bridge)
    }

    /**
     * Мост для подключения.
     *
     * В автовыборе ([VpnConfig.groupMode]) ручной выбор отбрасывается:
     * тумблер обещает «самый быстрый», поэтому выбранный когда-то мост
     * не должен его обходить. Если живых не осталось -- откат к
     * выбранному, пусть мёртвому: tor попробует сам, список мостов в
     * конфиге у него есть.
     *
     * Ручной источник -- [VpnConfig.torBridgeSourceId], а не
     * `activeProfileId`: тот общий с VPN и сбрасывается при обновлении
     * источника.
     */
    private fun selectTorBridge(): VpnProfile? {
        val bridges = VpnConnects.torBridges(config.vpn.profiles)
        val preferred = if (config.vpn.groupMode) "" else config.vpn.torBridgeSourceId
        return VpnConnects.torBridgeFor(bridges, preferred)
            ?: VpnConnects.torBridgeFor(bridges, config.vpn.activeProfileId)
    }

    /** Поднимает туннель ровно через [bridge]; см. [connectTor]. */
    private fun connectTorBridge(bridge: VpnProfile) {
        if (!tunnel.supported) return
        sessionOwner = SessionKind.TOR
        selectProfile(bridge.id)
        AppLog.log("vpn", "tor connect requested (bridge=${bridge.name.ifBlank { bridge.id }})")
        if (tunnel.state.value == TunnelState.DISCONNECTED) {
            runCatching { tunnel.connect() }
                .onFailure { tunnel.fail("connect failed: ${it.causeText()}") }
            return
        }
        scope.launch {
            disconnectVpn()
            var waits = 0
            while (tunnel.state.value != TunnelState.DISCONNECTED && waits < 50) {
                delay(100); waits++
            }
            runCatching {
                tunnel.applyConfig(config.vpn)
                tunnel.applyPerApp(config.perApp)
                tunnel.connect()
            }.onFailure { tunnel.fail("connect failed: ${it.causeText()}") }
        }
    }

    // --- Обновления ---

    /**
     * Проверяет последний релиз на GitHub.
     *
     * Повторный запуск во время полёта игнорируется: две параллельные
     * проверки дали бы гонку состояний без единого выигрыша.
     */
    fun checkForUpdates() {
        // Чужая подпись -- наши фиды и бинарники в руках фарм-сборки не
        // должны оказаться: проверка и загрузка молча выключены.
        if (!isOfficialBuild()) {
            AppLog.log("update", "check skipped: unofficial build signature")
            return
        }
        if (_updateState.value == UpdateState.CHECKING) return
        _updateState.value = UpdateState.CHECKING
        scope.launch {
            val result = runCatching { fetchLatestRelease() }
            val found = result.getOrNull()
            if (found == null) {
                // Состояние ERROR всегда с причиной: сеть, таймаут, статус
                // GitHub -- видно, что именно вышло, а не «Не удалось
                // проверить» в пустоту. Строка идёт и в журнал ошибок:
                // без неё багрепорт об обновлениях был бы голым.
                val cause = result.exceptionOrNull()?.causeText() ?: "unknown"
                updateError = cause
                AppLog.log("update", "check failed: $cause")
                _updateState.value = UpdateState.ERROR
                return@launch
            }
            updateError = null
            _release.value = found
            // Объявление -- блок в теле того же релиза: отдельного фида нет,
            // и проверка обновлений уже ходит в GitHub.
            _devNotice.value = parseNotice(found.changelog)
            val newer = updateAvailable(found.version, appVersion, config.updateCompareMode)
            _updateState.value = if (newer) UpdateState.AVAILABLE else UpdateState.CURRENT
            // «Не напоминать сегодня» глушит только уведомление: состояние
            // в настройках и журнал обновляются всегда, иначе отложенное
            // напоминание выглядело бы как поломка проверки.
            val snoozed = System.currentTimeMillis() < config.updateSnoozeUntilMs
            AppLog.log("update", "latest=${found.version} current=$appVersion newer=$newer snoozed=$snoozed")
            if (newer && !snoozed) notify(Str.NOTIFY_UPDATE, found.version, TAG_UPDATE)
            // Обновление нашлось и под эту сборку есть APK -- качаем сами,
            // пользователю останется только нажать «Установить».
            if (newer && found.apkUrl.isNotBlank()) downloadUpdate()
        }
    }

    /**
     * Кладёт APK последнего релиза в кеш.
     *
     * Повторный тап во время загрузки игнорируется, а после успеха
     * файл остаётся на диске: перезапуск приложения не начинает качать
     * его заново.
     */
    fun downloadUpdate() {
        val found = _release.value ?: return
        if (found.apkUrl.isBlank()) return
        if (_updateDownload.value == UpdateDownload.RUNNING) return
        _updateDownload.value = UpdateDownload.RUNNING
        updateDownloadError = null
        updateDownloadPercent = null
        scope.launch {
            val ok = runCatching {
                downloadUpdateApk(found.version, found.apkUrl) { done, total ->
                    updateDownloadPercent =
                        if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else null
                }
            }
                .onFailure { updateDownloadError = it.causeText() }
                .getOrDefault(false)
            _updateDownload.value = if (ok) UpdateDownload.NONE else UpdateDownload.ERROR
            updateDownloadPercent = null
            AppLog.log(
                "update",
                if (ok) "apk cached ${found.version}" else "apk download failed: ${updateDownloadError ?: "?"}"
            )
            // APK лежит в кеше -- предлагаем установить прямо сейчас.
            if (ok) notify(Str.NOTIFY_UPDATE_READY, found.version, TAG_UPDATE)
        }
    }

    /** Открывает закешированный APK установщиком; файла может не быть. */
    fun installUpdate() {
        val found = _release.value ?: return
        val path = cachedUpdateApk(found.version) ?: return
        val ok = installUpdateApk(path)
        AppLog.log("update", if (ok) "installer opened: $path" else "installer refused: $path")
    }

    /**
     * Откладывает напоминание об обновлении до конца суток.
     *
     * Сами проверки продолжают идти и состояние в настройках остаётся
     * честным -- отложен только тост.
     */
    fun snoozeUpdates() {
        mutate { it.copy(updateSnoozeUntilMs = localDayEndMs(System.currentTimeMillis())) }
        AppLog.log("update", "notifications snoozed until tomorrow")
    }

    /** Открывает страницу последнего релиза в браузере. */
    fun openReleasePage(): Boolean {
        val url = _release.value?.url ?: return false
        return xyz.azraellab.zapp.core.net.openUrl(url)
    }

    /** Ссылка из объявления разработчика в браузере. */
    fun openNoticeUrl(): Boolean {
        val url = _devNotice.value?.url ?: return false
        return xyz.azraellab.zapp.core.net.openUrl(url)
    }

    /**
     * Гасит текущее объявление: его ID запоминается в настройках, и
     * баннер не всплывает заново для того же текста.
     */
    fun dismissNotice() {
        val current = _devNotice.value ?: return
        mutate { it.copy(noticeDismissedId = current.id) }
        AppLog.log("update", "notice dismissed: ${current.title}")
    }

    // --- Обновление ядра sing-box ---

    /** Обновление ядра с официального фида; null -- нет или не проверено. */
    private val _coreUpdate = MutableStateFlow<CoreUpdateInfo?>(null)
    val coreUpdate: StateFlow<CoreUpdateInfo?> = _coreUpdate.asStateFlow()

    /** Ядро качается прямо сейчас. */
    var coreUpdateBusy by mutableStateOf(false)
        private set

    /** Ядро обновлено в этой сессии; применяется при следующем старте туннеля. */
    var coreUpdateDone by mutableStateOf(false)
        private set

    /** Причина неудачи обновления ядра; null -- всё хорошо или ещё не пробовали. */
    var coreUpdateError by mutableStateOf<String?>(null)
        private set

    /**
     * Проверяет официальный фид sing-box на свежее ядро.
     *
     * Предлагаются только patch-релизы своей minor-ветки: конфиги
     * проверены на версии, с которой собран дистрибутив (см.
     * [coreGateOpen]). Android -- no-op: бинарь в jniLibs заменить нельзя.
     */
    fun checkCoreUpdate() {
        if (!caps.coreUpdateSupported || _coreUpdate.value != null || coreUpdateBusy) return
        scope.launch {
            val current = runCatching { probeComponentVersion(ZappComponent.BOX) }.getOrNull()
            if (current == null) {
                AppLog.log("core", "check: cannot read box version")
                return@launch
            }
            val body = httpGetText(CORE_RELEASES_URL)
            if (body == null) {
                AppLog.log("core", "check: no response from GitHub")
                return@launch
            }
            val osArch = corePlatform()
            if (osArch == null) {
                AppLog.log("core", "check: unsupported platform")
                return@launch
            }
            val found = parseCoreRelease(body, current, osArch.first, osArch.second)
            AppLog.log("core", "check: current=$current available=${found?.version ?: "none in line"}")
            _coreUpdate.value = found
        }
    }

    /**
     * Качает архив ядра, распаковывает бинарь и кладёт его поверх PATH.
     *
     * Запущенный туннель не трогается: свежий файл применяется при
     * следующем старте -- перезапуск ради обновления поднял бы трафик
     * и оборвал бы сессию.
     */
    fun downloadCoreUpdate() {
        val info = _coreUpdate.value ?: return
        if (coreUpdateBusy) return
        coreUpdateBusy = true
        coreUpdateDone = false
        coreUpdateError = null
        scope.launch {
            val result = runCatching {
                val os = corePlatform()?.first ?: error("unsupported platform")
                val bytes = httpGetBytes(info.url) ?: error("no response from GitHub")
                val entries = if (info.zip) zipEntries(bytes) else tarGzEntries(bytes)
                val binary = findCoreBinary(entries, os) ?: error("no $os binary in archive")
                if (!writeUpdatedCore(binary, info.version)) error("platform cannot replace core")
            }
            coreUpdateBusy = false
            result.onSuccess {
                _coreUpdate.value = null
                coreUpdateDone = true
                AppLog.log("core", "updated to ${info.version}; applies on next tunnel start")
            }.onFailure { e ->
                val cause = e.causeText()
                coreUpdateError = cause
                AppLog.log("core", "update failed: $cause")
            }
        }
    }

    /** Целевая пара платформы; null -- ОС или архитектура не поддержаны. */
    private fun corePlatform(): Pair<String, String>? = coreOsArch(
        System.getProperty("os.name") ?: "",
        System.getProperty("os.arch") ?: ""
    )

    // --- Уведомления ---

    /**
     * Показывает системное уведомление с переводом.
     *
     * Язык читается здесь, а не передаётся из UI: события возникают в
     * корутинах, где Composable'ов нет, а настройка языка -- та же самая,
     * что и в интерфейсе.
     *
     * [action] -- необязательное действие прямо из шторки (например,
     * «открыть настройки»); [actionLabel] -- уже переведённая подпись.
     * Действие есть -- уведомление показывается как heads-up: отказ
     * требует внимания здесь и сейчас, а не при следующем взгляде.
     */
    private fun notify(
        str: Str,
        arg: String = "",
        tag: String,
        action: NotifierAction? = null,
        actionLabel: Str? = null
    ) {
        if (!notifier.supported) return
        val lang = resolveLang(AppLang.of(AppLangStore.read()), AppLangStore.systemCode())
        notifier.show(
            title = str.of(lang),
            body = arg,
            tag = tag,
            action = action,
            actionLabel = actionLabel?.of(lang) ?: ""
        )
    }

    /**
     * Причина сбоя подмены координат для тела уведомления.
     *
     * «Подмена GPS не запустилась» без причины -- ровно та же голая
     * ошибка, что и «Ошибка» без строки под статусом. Причина берётся из
     * журнала движка и рендерится на том же языке, что и заголовок.
     */
    private fun gpsErrorCause(): String {
        val event = gpsEngine.log.value.errorEvent() ?: return ""
        val lang = resolveLang(AppLang.of(AppLangStore.read()), AppLangStore.systemCode())
        return event.text.of(lang) + if (event.arg.isEmpty()) "" else " ${event.arg}"
    }

    // --- VPN: источники, проверка живости, импорт ---

    /** Состояние загрузки одного источника коннектов. */
    enum class SourceState { IDLE, LOADING, OK, ERROR }

    /** Итог загрузки источника: сколько коннектов нашлось или почему не вышло. */
    data class SourceStatus(
        val state: SourceState = SourceState.IDLE,
        val links: Int = 0,
        val error: String = ""
    )

    /** Прогресс проверки: сколько коннектов уже замерено и сколько всего. */
    data class CheckProgress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0
    )

    /** Сообщение VPN-раздела: ключ строки плюс число для подстановки. */
    data class VpnNotice(val key: Str, val arg: String = "")

    private val _sourceStatus = MutableStateFlow<Map<String, SourceStatus>>(emptyMap())
    val sourceStatus: StateFlow<Map<String, SourceStatus>> = _sourceStatus.asStateFlow()

    private val _checkProgress = MutableStateFlow(CheckProgress())
    val checkProgress: StateFlow<CheckProgress> = _checkProgress.asStateFlow()

    /** Последнее событие раздела для баннера; null -- баннер не нужен. */
    var vpnNotice: VpnNotice? by mutableStateOf(null)
        private set

    fun clearVpnNotice() {
        vpnNotice = null
    }

    /** Приветствие показано: фиксируем и больше не показываем. */
    fun finishWelcome() {
        mutate { it.copy(welcomeDone = true) }
        AppLog.log("app", "welcome finished")
    }

    /** Коннекты, показываемые сейчас: живые, не проверенные и не из архива. */
    fun visibleVpnProfiles(): List<VpnProfile> = config.vpn.visibleProfiles()

    fun selectProfile(id: String) {
        if (config.vpn.profileById(id) == null) return
        mutate { it.copy(vpn = it.vpn.copy(activeProfileId = id)) }
    }

    /** Выполняет действие deep-link: импорт конфига или выбор профиля. */
    fun handleDeepLink(link: DeepLink) {
        when (link) {
            is DeepLink.Import -> scope.launch { importVpnText(link.url) }
            is DeepLink.Connect -> {
                val profile = config.vpn.profiles.firstOrNull {
                    it.id == link.profile || it.name == link.profile
                } ?: return
                selectProfile(profile.id)
            }
        }
    }

    /** Удаляет коннекты: архив чистится кнопкой, ручные -- из списка. */
    fun deleteProfiles(ids: Collection<String>) {
        if (ids.isEmpty()) return
        mutate { current ->
            val remaining = current.vpn.profiles.filterNot { it.id in ids }
            current.copy(
                vpn = current.vpn.copy(
                    profiles = remaining,
                    activeProfileId = if (current.vpn.activeProfileId in ids) "" else current.vpn.activeProfileId
                )
            )
        }
    }

    // --- Источники ---

    /**
     * Добавляет свой источник по URL подписки; false -- адрес не похож на URL.
     *
     * [kind] определяет, в каком разделе источник появится: "proxy" --
     * список VPN, "tor" -- вкладка Tor с мостами.
     */
    fun addSource(url: String, name: String, kind: String = "proxy"): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            vpnNotice = VpnNotice(Str.VPN_SOURCE_FAILED)
            return false
        }
        val existing = config.vpn.sources.firstOrNull { it.url == clean }
        if (existing != null) {
            refreshSource(existing.id)
            return true
        }
        val id = "custom:" + clean.hashCode().toUInt().toString(16)
        val host = clean.substringAfter("://").substringBefore("/").substringBefore('?')
        mutate { current ->
            current.copy(
                vpn = current.vpn.copy(
                    sources = current.vpn.sources + VpnSource(
                        id = id,
                        name = name.ifBlank { host },
                        url = clean,
                        kind = kind
                    )
                )
            )
        }
        vpnNotice = VpnNotice(Str.VPN_SOURCE_ADDED)
        refreshSource(id)
        return true
    }

    fun removeSource(id: String) {
        mutate { current ->
            val sources = current.vpn.sources.filterNot { it.id == id }
            val profiles = current.vpn.profiles.filterNot { it.sourceId == id }
            current.copy(
                vpn = current.vpn.copy(
                    sources = sources,
                    profiles = profiles,
                    activeProfileId = profiles.firstOrNull { it.id == current.vpn.activeProfileId }?.id ?: ""
                )
            )
        }
        _sourceStatus.value = _sourceStatus.value - id
        vpnNotice = VpnNotice(Str.VPN_SOURCE_REMOVED)
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        mutate { current ->
            current.copy(
                vpn = current.vpn.copy(
                    sources = current.vpn.sources.map { if (it.id == id) it.copy(enabled = enabled) else it }
                )
            )
        }
        if (enabled) refreshSource(id)
    }

    fun refreshSource(id: String) {
        refreshSources(listOf(id))
    }

    fun refreshSources(ids: Collection<String> = config.vpn.sources.filter { it.enabled }.map { it.id }) {
        val wanted = ids.filter { id -> config.vpn.sources.any { it.id == id } }
        if (wanted.isEmpty()) return
        scope.launch { refreshSourcesImpl(wanted) }
    }

    /**
     * Загружает источники по очереди.
     *
     * Зеркала пробуются по порядку -- это не «запасной план на всякий случай»,
     * а основной способ жить при блокировках: GitHub RAW у нас может не
     * отвечать, а зеркало с тем же файлом -- отвечать.
     */
    private suspend fun refreshSourcesImpl(ids: Collection<String>) {
        val sources = config.vpn.sources.filter { it.id in ids && it.enabled }
        for (source in sources) {
            setStatus(source.id, SourceState.LOADING)

            var text: String? = null
            var error = "fetch"
            for (url in source.urls()) {
                val fetched = runCatching { httpGetText(url) }.getOrNull()
                if (!fetched.isNullOrBlank()) {
                    text = fetched
                    break
                }
                error = "fetch:$url"
            }

            if (text == null) {
                AppLog.log("source", "${source.name}: fetch failed ($error)")
                setStatus(source.id, SourceState.ERROR, 0, error)
                continue
            }

            val links = when (source.kind) {
                "tor" -> replaceTorSource(source, text)
                else -> replaceProxySource(source, text)
            }
            markSourceRefreshed(source.id)
            setStatus(source.id, SourceState.OK, links)
            AppLog.log("source", "${source.name}: updated, links=$links")
        }
    }

    private fun setStatus(id: String, state: SourceState, links: Int = 0, error: String = "") {
        _sourceStatus.value = _sourceStatus.value + (id to SourceStatus(state, links, error))
    }

    private fun markSourceRefreshed(id: String) {
        mutate { current ->
            current.copy(
                vpn = current.vpn.copy(
                    sources = current.vpn.sources.map {
                        if (it.id == id) it.copy(lastRefreshAtMs = System.currentTimeMillis()) else it
                    }
                )
            )
        }
    }

    /**
     * Обновляет коннекты обычного источника.
     *
     * Сверка по тексту ссылки: уже известные коннекты сохраняют свой id и
     * результат последней проверки (иначе каждое обновление сбрасывало бы
     * пинги сотен узлов), пропавшие из файла уходят, новые приходят с
     * непроверенным статусом.
     */
    private fun replaceProxySource(source: VpnSource, text: String): Int {
        val configs = xyz.azraellab.zapp.core.net.ProxyUri.parseAll(text)
        mutate { current ->
            val freshRaw = configs.map { it.raw }.toSet()
            val mine = current.vpn.profiles.filter { it.sourceId == source.id }
            val keep = mine.filter { it.rawConfig in freshRaw }
            val keptRaw = keep.map { it.rawConfig }.toSet()
            val added = configs
                .filter { it.raw !in keptRaw }
                .map { cfg ->
                    ConfigImport.fromProxy(cfg)
                        .copy(id = freshProfileId(current.vpn.profiles, source.id, cfg.raw), sourceId = source.id)
                }
            val profiles = current.vpn.profiles.filter { it.sourceId != source.id } + keep + added
            // Выбор держим, пока он жив: висячий id чинится в repairedActive.
            current.copy(
                vpn = current.vpn.copy(
                    profiles = profiles,
                    activeProfileId = profiles.firstOrNull { it.id == current.vpn.activeProfileId }?.id
                        ?: current.vpn.activeProfileId
                ).repairedActive()
            )
        }
        return configs.size
    }

    /**
     * Tor-источник: один профиль со списком мостов, а не профиль на мост.
     *
     * Мосты -- транспорт одного подключения: при смене файла меняется состав
     * списка, а не число коннектов, и хранить их сотнями отдельных пунктов
     * значило бы показывать пользователю мусор вместо одного понятного «Tor».
     */
    private fun replaceTorSource(source: VpnSource, text: String): Int {
        val trimmed = text.trim()
        val profileId = "tor:" + source.id
        mutate { current ->
            val existing = current.vpn.profiles.firstOrNull { it.id == profileId }
            val profile = VpnProfile(
                id = profileId,
                name = source.name,
                protocol = VpnProtocol.TOR.code,
                rawConfig = trimmed,
                sourceId = source.id,
                // Текст не изменился -- проверка ещё актуальна.
                health = if (existing?.rawConfig == trimmed) existing.health else LinkHealth()
            )
            val profiles = current.vpn.profiles.filterNot { it.sourceId == source.id } + profile
            current.copy(
                vpn = current.vpn.copy(
                    profiles = profiles,
                    activeProfileId = profiles.firstOrNull { it.id == current.vpn.activeProfileId }?.id
                        ?: current.vpn.activeProfileId
                ).repairedActive()
            )
        }
        return 1
    }

    /** Свободный id для нового коннекта: источник + хеш текста ссылки. */
    private fun freshProfileId(profiles: List<VpnProfile>, sourceId: String, raw: String): String {
        val base = "$sourceId:" + raw.hashCode().toUInt().toString(16)
        if (profiles.none { it.id == base }) return base
        var copy = 2
        while (profiles.any { it.id == "$base-$copy" }) copy++
        return "$base-$copy"
    }

    /** Фоновый цикл: обновляем источники по их периоду, раз в минуту проверяем. */
    private suspend fun sourceRefreshLoop() {
        while (coroutineContext.isActive) {
            delay(SOURCE_REFRESH_TICK_MS)
            val now = System.currentTimeMillis()
            val due = config.vpn.sources
                .filter { it.enabled && it.refreshSeconds > 0 }
                .filter { it.lastRefreshAtMs == 0L || now - it.lastRefreshAtMs >= it.refreshSeconds * 1000L }
                .filter { _sourceStatus.value[it.id]?.state != SourceState.LOADING }
            if (due.isNotEmpty()) refreshSourcesImpl(due.map { it.id })
        }
    }

    // --- Проверка живости ---

    fun checkAllVisible() {
        checkLinks(visibleVpnProfiles().map { it.id })
    }

    fun recheckArchive() {
        checkLinks(config.vpn.archivedProfiles().map { it.id })
    }

    /**
     * Проверяет коннекты: жив или мёртв, с замером задержки.
     *
     * Прогресс идёт потоком состояния, а результаты пишутся в список
     * пачками: сотни отдельных перерисовок на каждый пинг -- это дёрганье
     * списка, а не прогресс. Мёртвые не исчезают из списка мгновенно --
     * их перемещает в архив лишь перерисовка страницы по health.alive.
     */
    fun checkLinks(ids: Collection<String>) {
        if (_checkProgress.value.running) return
        val wanted = ids.distinct()
        if (wanted.isEmpty()) return

        // Прогресс включается до сбора целей: на тысячах коннектов сбор
        // probeTarget'ов занимает секунды, и без этого флага приветствие
        // (и кнопка «Проверить все») видели «не запущено» и решали, что
        // проверка не состоялась, -- шаг автонастройки помечался ✗ при
        // полном списке живых коннектов.
        _checkProgress.value = CheckProgress(running = true)

        scope.launch {
            val targets = wanted.mapNotNull { id ->
                val profile = config.vpn.profileById(id) ?: return@mapNotNull null
                probeTarget(profile)?.let { profile.id to it }
            }
            if (targets.isEmpty()) {
                _checkProgress.value = CheckProgress()
                return@launch
            }
            _checkProgress.value = CheckProgress(running = true, done = 0, total = targets.size)

            val semaphore = Semaphore(CHECK_PARALLEL)
            val mutex = Mutex()
            val pending = mutableListOf<Pair<String, LinkHealth>>()
            var done = 0

            kotlinx.coroutines.coroutineScope {
                targets.forEach { (id, target) ->
                    launch {
                        semaphore.acquire()
                        val health = try {
                            val probe: LinkProbe = runCatching {
                                probeLink(target.host, target.port, target.kind)
                            }.getOrElse { e ->
                                LinkProbe(alive = false, error = e::class.simpleName.orEmpty().lowercase())
                            }
                            LinkHealth(
                                alive = probe.alive,
                                latencyMs = probe.latencyMs,
                                checkedAtMs = System.currentTimeMillis(),
                                error = probe.error
                            )
                        } catch (e: Exception) {
                            LinkHealth(alive = false, error = e::class.simpleName.orEmpty().lowercase())
                        } finally {
                            semaphore.release()
                        }

                        mutex.withLock {
                            pending += id to health
                            done++
                            val finished = done == targets.size
                            if (pending.size >= CHECK_FLUSH_BATCH || finished) {
                                applyHealth(pending.toList())
                                pending.clear()
                            }
                            _checkProgress.value = CheckProgress(
                                running = !finished,
                                done = done,
                                total = targets.size
                            )
                        }
                    }
                }
            }

            // Итог одним числом: сколько живо из проверенных.
            val alive = targets.count { config.vpn.profileById(it.first)?.health?.alive == true }
            AppLog.log("ping", "checked=${targets.size} alive=$alive")
        }
    }

    private fun applyHealth(batch: List<Pair<String, LinkHealth>>) {
        if (batch.isEmpty()) return
        val byId = batch.toMap()
        mutate { current ->
            current.copy(
                vpn = current.vpn.copy(
                    profiles = current.vpn.profiles.map { profile ->
                        byId[profile.id]?.let { profile.copy(health = it) } ?: profile
                    }
                )
            )
        }
    }

    // --- Импорт коннектов из буфера ---

    /**
     * Импортирует текст любого формата: ссылка, список, wg-ini, ovpn,
     * JSON, clash YAML, мосты Tor или адрес подписки. Подписка превращается в источник --
     * так её обновление ходит по общим правилам, а не живёт отдельной
     * логикой в профиле.
     */
    suspend fun importVpnText(text: String) {
        val result = runCatching { ConfigImport.import(text) }.getOrNull() ?: ConfigImport.Result.none
        when (result.kind) {
            ConfigImport.Kind.NONE -> vpnNotice = VpnNotice(Str.VPN_IMPORT_NONE)

            ConfigImport.Kind.SUBSCRIPTION -> {
                val profile = result.profiles.firstOrNull()
                val url = profile?.subscriptionUrl.orEmpty()
                if (url.isBlank() || !addSource(url, profile?.name.orEmpty())) {
                    vpnNotice = VpnNotice(Str.VPN_IMPORT_NONE)
                }
            }

            else -> {
                val added = addImportedProfiles(result.profiles)
                vpnNotice = VpnNotice(Str.VPN_IMPORTED, added.toString())
            }
        }
    }

    /** Добавляет импортированные профили со свежими id и осмысленным именем. */
    private fun addImportedProfiles(incoming: List<VpnProfile>): Int {
        if (incoming.isEmpty()) return 0
        mutate { current ->
            val used = current.vpn.profiles.map { it.id }.toSet()
            val added = incoming.map { profile ->
                val code = profile.protocol.ifBlank { "link" }
                val name = profile.name.ifBlank { code }
                var id = freshProfileId(current.vpn.profiles, "import", profile.rawConfig.ifBlank { code + name })
                if (id in used) id = "$id-2"
                profile.copy(id = id, name = name, sourceId = "")
            }
            current.copy(
                vpn = current.vpn.copy(
                    profiles = current.vpn.profiles + added,
                    activeProfileId = current.vpn.activeProfileId.ifBlank { added.first().id }
                )
            )
        }
        return incoming.size
    }

    // --- Cloudflare WARP ---

    /** Регистрирует аккаунт WARP и добавляет его как wireguard-профиль. */
    suspend fun registerWarp(): PresetMessage {
        val keys = generateWireGuardKeyPair()
        if (keys == null) {
            lastMessage = PresetMessage.unavailable
            return PresetMessage.unavailable
        }
        val (privateKey, publicKey) = keys
        val serial = runCatching {
            platformSecureStorage().get(warpSerialSlot)
                ?: java.util.UUID.randomUUID().toString().also {
                    platformSecureStorage().put(warpSerialSlot, it)
                }
        }.getOrDefault(java.util.UUID.randomUUID().toString())

        val body = buildRegisterRequest(publicKey, serial)
        val response = runCatching {
            httpPostJson(
                warpRegisterUrl,
                body,
                mapOf("User-Agent" to "okhttp/3.12.1", "Accept" to "application/json")
            )
        }.getOrNull()
        if (response == null) {
            lastMessage = PresetMessage.badFile
            return PresetMessage.badFile
        }

        val account = parseRegisterResponse(response, privateKey)
        if (account == null) {
            lastMessage = PresetMessage.badFile
            return PresetMessage.badFile
        }
        val profile = account.toProfile("WARP")
        addImportedProfiles(listOf(profile))
        lastMessage = PresetMessage.applied("WARP")
        return lastMessage!!
    }

    private val warpSerialSlot = "warp:serial"

    // --- Автостарт и автоперезапуск ---

    /**
     * Старт после распаковки бинарей: демоны с включённым тумблером,
     * туннель по флагу «старт при запуске», источники -- которых ещё
     * ни разу не грузили. Всё это фон: приложение уже показало экран.
     */
    private suspend fun startAutoServices() {
        // Уборка сирот движка должна кончиться до первого демона:
        // иначе свежий zapret конкурирует за NFQUEUE с сиротой и
        // падает с «Operation not permitted» до того, как фоновый pkill
        // доедет.
        runCatching { tunnel.awaitStartup() }
        if (config.zapret.enabled) startZapret()
        if (config.goodbyeDpi.autoStart &&
            GoodbyeDpiMode.of(config.goodbyeDpi.mode) != GoodbyeDpiMode.DISABLED
        ) {
            startDpi()
        }
        // Подмена геолокации переживает рестарт через gps.enabled:
        // тумблер на странице GPS -- это намерение, а не состояние
        // процесса. Разрешения не спрашиваем (диалоги при открытии --
        // не то, за чем пришли), движок сам откажется и запишет причину.
        if (config.gps.autostart) {
            startGps(askPermissions = false)
        }
        if (config.vpn.autoStart && (config.vpn.groupMode || config.vpn.activeProfile() != null)) {
            connectVpn()
        }
        val neverLoaded = config.vpn.sources
            .filter { it.enabled && it.lastRefreshAtMs == 0L }
            .map { it.id }
        if (neverLoaded.isNotEmpty()) refreshSourcesImpl(neverLoaded)
    }

    /**
     * Автоперезапуск упавших демонов.
     *
     * Тумблер «включено» -- намерение пользователя, а падение процесса --
     * не его решение: пока тумблер включён, демон поднимается снова через
     * паузу. Выключенный тумблер останавливает и цикл -- иначе после
     * ручной остановки процесс снова стартовал бы сам.
     */
    private fun watchDaemons() {
        scope.launch {
            zapretDaemon.state.collect { state ->
                refreshDpiFirewall()
                if (state == DaemonState.ERROR && config.zapret.enabled && config.zapret.autoRestart) {
                    delay(DAEMON_RESTART_DELAY_MS)
                    if (config.zapret.enabled && zapretDaemon.state.value == DaemonState.ERROR) {
                        startZapret()
                    }
                }
            }
        }
        scope.launch {
            dpiDaemon.state.collect { state ->
                refreshDpiFirewall()
                val enabled = config.goodbyeDpi.autoRestart &&
                    GoodbyeDpiMode.of(config.goodbyeDpi.mode) != GoodbyeDpiMode.DISABLED
                if (state == DaemonState.ERROR && enabled) {
                    delay(DAEMON_RESTART_DELAY_MS)
                    if (enabled && dpiDaemon.state.value == DaemonState.ERROR) {
                        startDpi()
                    }
                }
            }
        }
        // Noroot-обход: правила живут в конфиге туннеля, поэтому смена
        // его состояния тоже пересчит -- остановили VPN при живых демонах
        // обхода (и наоборот), и DPI-сессия должна встать или упасть.
        scope.launch {
            tunnel.state.collect {
                if (!BuildMode.rootAllowed) refreshDpiOverlay(dpiPortSets())
            }
        }
    }

    /**
     * Чья сессия сейчас поднимается или работает в туннеле.
     *
     * Ставится в момент запроса, а не выводится из активного профиля:
     * ошибка должна остаться «своей» для вкладки, которая её вызвала, а
     * чужая вкладка не должна показывать сбой незнакомого подключения.
     * Профиль при этом к этому моменту уже приведён в порядок (см.
     * [adoptVpnProfile], [connectTor]), так что владелец всегда
     * соответствует тому, что движок реально поднимает.
     *
     * Для noroot-обхода это же прежний признак владельца сессии:
     * [SessionKind.DPI] значит «подняла автоматика» -- такую сессию
     * можно снять вместе с демонами, а пользовательскую нельзя.
     */
    var sessionOwner: SessionKind by mutableStateOf(SessionKind.NONE)
        private set

    /**
     * Свежие NFQUEUE-правила под текущее состояние демонов.
     *
     * Живы оба -- цепочка обслуживает оба набора портов; жив ни один --
     * цепочка снимается, чтобы трафик не ходил в пустую очередь.
     * Правила читаются из состояний (а не из тумблеров): во время
     * автоперезапуска процесс мёртв, и слушателя для очереди нет.
     */
    private fun refreshDpiFirewall() {
        val sets = dpiPortSets()
        val tcp = sets.first
        val udp = sets.second
        val filter = dpiFilter()
        scope.launch(Dispatchers.IO) {
            if (tcp.isEmpty() && udp.isEmpty()) {
                DpiFirewall.disable()
            } else {
                DpiFirewall.enable(tcp.toList(), udp.toList(), filter.owners, filter.excludes)
            }
        }
        // Noroot: ядерных правил нет, обход переезжает в конфиг туннеля.
        if (!BuildMode.rootAllowed) refreshDpiOverlay(sets)
    }

    /**
     * Разделение трафика обхода по выборам живых демонов.
     *
     * Выборы живых демонов объединяются: NFQUEUE-правило одно на оба
     * процесса, и режим фильтра обязан быть общим (см. dpiAppFilter).
     * Выборы без enabled здесь не встречаются: enabled -- это состояние
     * пикера, а правила читаются из packages.
     */
    private fun dpiFilter(): DpiAppFilter {
        val up = setOf(DaemonState.STARTING, DaemonState.RUNNING)
        val selections = buildList {
            if (zapretDaemon.state.value in up) add(config.perApp.zapret)
            if (dpiDaemon.state.value in up) add(config.perApp.goodbyeDpi)
        }
        if (selections.isEmpty()) return DpiAppFilter()
        val catalog = runCatching { installedApps() }.getOrDefault(emptyList())
        return dpiAppFilter(selections, catalog)
    }

    /**
     * Домены, сузившие область обхода (для журнала и правил релея).
     *
     * Берутся только у живых демонов: выключенный zapret со списком
     * доменов не сузжает ничью область. Объединение обеих областей --
     * релей один на двоих, и подпись обязана показывать обе.
     */
    private fun dpiDomains(): List<String> {
        val up = setOf(DaemonState.STARTING, DaemonState.RUNNING)
        val hosts = linkedSetOf<String>()
        if (zapretDaemon.state.value in up) hosts += config.zapret.domainList()
        if (dpiDaemon.state.value in up) hosts += config.goodbyeDpi.domainList()
        return hosts.toList()
    }

    /** Порты живых демонов обхода: (tcp, udp), свежие из конфига и состояний. */
    private fun dpiPortSets(): Pair<Set<String>, Set<String>> {
        val up = setOf(DaemonState.STARTING, DaemonState.RUNNING)
        val tcp = linkedSetOf<String>()
        val udp = linkedSetOf<String>()
        if (zapretDaemon.state.value in up) {
            config.zapret.filterTcp.split(',').map { it.trim() }
                .filterTo(tcp) { it.isNotEmpty() }
            config.zapret.filterUdp.split(',').map { it.trim() }
                .filterTo(udp) { it.isNotEmpty() }
        }
        if (dpiDaemon.state.value in up) {
            // goodbyedpi без портовых фильтров: ему нужны только сами
            // рукопожатия HTTP/HTTPS.
            tcp += "80"
            tcp += "443"
        }
        return tcp to udp
    }

    /**
     * Noroot-обход: состояние релея и сессии под свежими портами.
     *
     * Демоны поднимают релей через UserlandDpiEngine, но трафик идёт по
     * правилам уже поднятого туннеля, поэтому здесь решается три вещи:
     * поднять пассивную сессию, если туннель стоит; переподнять её, если
     * правила изменились; снять её, если обход выключили, а сессию подняли
     * именно мы (см. [SessionKind.DPI]).
     */
    private fun refreshDpiOverlay(sets: Pair<Set<String>, Set<String>>) {
        val (tcp, udp) = sets
        val changed = DpiOverlay.update(tcp, udp.isNotEmpty(), dpiDomains())
        if (DpiOverlay.active) {
            when (tunnel.state.value) {
                TunnelState.DISCONNECTED -> {
                    AppLog.log("dpi", "userland relay: starting passthrough session")
                    sessionOwner = SessionKind.DPI
                    tunnel.connectDpi()
                }
                TunnelState.CONNECTED -> if (changed) tunnel.refreshDpi()
                else -> Unit
            }
        } else if (sessionOwner == SessionKind.DPI) {
            // Рвать можно из любого живого состояния: демон нередко
            // умирает раньше туннеля (провал пробы -- а connectDpi уже
            // в полёте), и прежняя проверка только на CONNECTED молча
            // съедала владение -- осиротевшая сессия держала релейные
            // маршруты без релея.
            val ownedLive = tunnel.state.value.isLive
            sessionOwner = SessionKind.NONE
            if (ownedLive) {
                AppLog.log("dpi", "userland relay: no daemons -- session down")
                tunnel.disconnect()
            }
        }
    }

    // --- Автоподбор стратегии обхода ---

    /**
     * Итог автоподбора: кандидаты прогнаны, победитель применён.
     *
     * Один на обе страницы: zapret и goodbyedpi проверяют одну и ту же
     * сеть, и прогонять перебор дважды ради разных кнопок -- бессмысленно.
     * Страница показывает свой target, прогресс виден у всех.
     */
    data class StrategyOutcome(
        val target: String,
        val probe: StrategyProbeOutcome
    )

    private val _strategyRunning = MutableStateFlow(false)
    val strategyRunning: StateFlow<Boolean> = _strategyRunning.asStateFlow()

    private val _strategyOutcome = MutableStateFlow<StrategyOutcome?>(null)
    val strategyOutcome: StateFlow<StrategyOutcome?> = _strategyOutcome.asStateFlow()

    /** Работающие состояния демонов: и для хуков пробы, и для рестарта. */
    private val daemonUp = setOf(DaemonState.STARTING, DaemonState.RUNNING)

    /** Был ли основной демон обхода жив до пробы (его останавливали). */
    private var probeDpiWasRunning = false

    /**
     * Перебор кандидатов с применением победителя.
     *
     * Проба -- не гипотезная проверка сети, а реальный перебор изолированными
     * инстансами (см. [StrategyProbe]): каждый кандидат обрабатывает трафик
     * приложения, побеждает лучший скоринг по продуктовым хостам. Победитель
     * пишется в конфиг, работающие демоны перезапускаются -- движки читают
     * конфиг только при старте, и без рестарта новый режим существовал бы
     * только на бумаге. Сеть без данных -- не итог: применять нечего, и
     * карточка честно показывает отказ.
     */
    fun autoPickStrategy(target: String) {
        if (_strategyRunning.value) return
        // Проба без области действия -- перебор стратегий вхолостую:
        // применять результат не к чему. То же правило, что и у запуска.
        val scoped = if (target == "zapret") {
            DpiScope.zapret(config.perApp, config.zapret)
        } else {
            DpiScope.gdpi(config.perApp, config.goodbyeDpi)
        }
        if (!scoped) {
            val engine = if (target == "zapret") zapretDaemon else dpiDaemon
            engine.note(DaemonEvent(text = Str.DAEMON_NEED_SCOPE))
            return
        }
        _strategyRunning.value = true
        scope.launch {
            val hooks = object : ProbeHooks {
                override fun dpiRunning(): Boolean = dpiDaemon.state.value in daemonUp
                override suspend fun stopDpi() {
                    probeDpiWasRunning = dpiRunning()
                    if (probeDpiWasRunning) dpiDaemon.stop()
                }

                override suspend fun startDpi() {
                    if (probeDpiWasRunning) startDpi()
                }
            }
            val outcome = runCatching {
                StrategyProbe.run(target, config.zapret, config.goodbyeDpi, config.relay, hooks)
            }.getOrNull()

            if (outcome != null) {
                if (!outcome.noData) applyProbeOutcome(outcome)
                _strategyOutcome.value = StrategyOutcome(target, outcome)
            }
            _strategyRunning.value = false
        }
    }

    /**
     * Пишет победителя в конфиг и поднимает демоны под новым режимом.
     *
     * Три пути ровно как в пробе: стратегия zapret (root), режим goodbyedpi
     * (root, демон останавливался хуками и возвращается уже с новым
     * конфигом) и тюнинг релея (noroot -- конфиг читают лениво, перезапуск
     * не нужен, хватает обновления DpiOverlay внутри mutate).
     */
    private fun applyProbeOutcome(outcome: StrategyProbeOutcome) {
        val code = outcome.strategyCode
        when {
            outcome.target == "zapret" && code != null -> {
                mutate { current ->
                    current.copy(
                        zapret = AutoStrategy.applyZapret(
                            current.zapret,
                            AutoStrategy.Decision(code, "probe")
                        )
                    )
                }
                if (zapretDaemon.state.value in daemonUp) {
                    zapretDaemon.stop()
                    startZapret()
                }
            }

            code != null -> {
                mutate { current ->
                    current.copy(goodbyeDpi = current.goodbyeDpi.copy(mode = code))
                }
                if (probeDpiWasRunning) {
                    dpiDaemon.stop()
                    startDpi()
                }
            }

            outcome.relay != null -> {
                mutate { current -> current.copy(relay = outcome.relay!!) }
            }
        }
        AppLog.log(
            "strategy",
            "probe target=${outcome.target} winner=${outcome.candidate.id} " +
                "score=${outcome.score?.summary() ?: "-"}"
        )
    }

    private fun targetIsZapret(target: String) = target == "zapret"

    fun clearStrategyOutcome() {
        _strategyOutcome.value = null
    }

    // --- Дуэль узлов (A-vs-B) ---

    /**
     * Состояние дуэли двух узлов: выбор и итог в одном поле.
     *
     * pick -- режим выбора (0 выключен, 1 выбран A, 2 ждёт B): пока он
     * включён, тап по строке списка выбирает узел для дуэли, а не
     * подключается. error -- узел в принципе не замеряем (сырой конфиг
     * sing-box/xray: тегов в боксе нет).
     */
    data class NodeDuelState(
        val pick: Int = 0,
        val aId: String = "",
        val bId: String = "",
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val result: DuelResult? = null,
        val error: Str? = null
    )

    private val _nodeDuel = MutableStateFlow(NodeDuelState())
    val nodeDuel: StateFlow<NodeDuelState> = _nodeDuel.asStateFlow()
    private var duelJob: Job? = null

    /** Вход в режим выбора; повторное нажатие -- выход и сброс. */
    fun duelToggle() {
        duelJob?.cancel()
        _nodeDuel.value = if (_nodeDuel.value.pick > 0) NodeDuelState()
        else NodeDuelState(pick = 1)
    }

    /** Закрыть итог или сбросить выбор: дуэль отменяется целиком. */
    fun duelClose() {
        duelJob?.cancel()
        _nodeDuel.value = NodeDuelState()
    }

    /**
     * Тап по строке в режиме дуэли: первый узел -- A, второй -- B и старт.
     *
     * Свой конфиг (sing-box/xray) тегов в боксе не имеет -- замерить его
     * нечем, и это честная ошибка, а не молчаливый отказ тапа.
     */
    fun duelTap(id: String) {
        val duel = _nodeDuel.value
        if (duel.running) return
        when (duel.pick) {
            1 -> _nodeDuel.update { it.copy(aId = id, pick = 2) }
            2 -> {
                if (id == duel.aId) return
                _nodeDuel.update { it.copy(bId = id) }
                startNodeDuel()
            }
        }
    }

    /**
     * Замер: пробы двух сторон вперемешку через clash_api, пока туннель
     * поднят ([NodeDuelRunner] -- чередование и агрегаты). Требует,
     * чтобы оба узла были в росписи работающего бокса.
     */
    private fun startNodeDuel() {
        val duel = _nodeDuel.value
        val a = config.vpn.profileById(duel.aId)
        val b = config.vpn.profileById(duel.bId)
        if (a == null || b == null) return
        val aTag = VpnConnects.nodeTag(a)
        val bTag = VpnConnects.nodeTag(b)
        if (aTag == null || bTag == null) {
            _nodeDuel.update { it.copy(pick = 0, error = Str.NODE_DUEL_UNSUPPORTED) }
            return
        }
        duelJob?.cancel()
        _nodeDuel.update {
            it.copy(running = true, done = 0, total = DUEL_PROBES_PER_SIDE * 2, result = null, error = null)
        }
        duelJob = scope.launch {
            val url = config.vpn.urlTest.testingUrl.ifBlank { WATCHDOG_PROBE_URL }
            val runner = NodeDuelRunner { tag ->
                fetchClashNodeProbe(
                    config.vpn.clashApiPort, config.vpn.clashApiSecret,
                    tag, url, TUNNEL_PROBE_TIMEOUT_MS
                )?.delayMs
            }
            val result = runCatching {
                runner.run(aTag, bTag) { done, total ->
                    _nodeDuel.update { it.copy(done = done, total = total) }
                }
            }.getOrNull()
            // null -- отмена: состояние уже сброшено вызвавшим, трогать
            // его заново не нужно.
            if (result != null) {
                _nodeDuel.update { it.copy(running = false, result = result, pick = 0) }
            }
        }
    }

    fun dispose() {
        stopTrafficMonitor()
        gpsEngine.stop()
        zapretDaemon.stop()
        dpiDaemon.stop()
        tunnel.disconnect()
        saveJob?.cancel()
        // Правила снимаются синхронно: после dispose коллекторов уже нет,
        // и оставить очередь без слушателя на чужом трафике нельзя.
        scope.launch(Dispatchers.IO) { DpiFirewall.disable() }
    }

}

/** Результат операции с пресетом: ключ для текста и признак успеха. */
data class PresetMessage(
    val kind: Kind,
    val argument: String = "",
    val problems: List<String> = emptyList(),
    val ok: Boolean = true
) {
    enum class Kind {
        SAVED,
        APPLIED,
        EXPORTED,
        IMPORTED,
        DELETED,
        FAILED,
        INVALID,
        UNAVAILABLE,
        BAD_FILE,
        EMPTY_FILE
    }

    companion object {
        fun saved(name: String) = PresetMessage(Kind.SAVED, name)
        fun applied(name: String) = PresetMessage(Kind.APPLIED, name)
        fun exported(name: String) = PresetMessage(Kind.EXPORTED, name)
        fun imported(name: String) = PresetMessage(Kind.IMPORTED, name)
        fun failed(name: String) = PresetMessage(Kind.FAILED, name, ok = false)
        fun invalid(problems: List<String>) = PresetMessage(Kind.INVALID, "", problems, ok = false)
        val unavailable = PresetMessage(Kind.UNAVAILABLE, ok = false)
        val badFile = PresetMessage(Kind.BAD_FILE, ok = false)
        val emptyFile = PresetMessage(Kind.EMPTY_FILE, ok = false)
    }

    /**
     * Строковый ключ для сообщения.
     *
     * Отдельная функция, а не текст здесь же: текст знает только UI и язык,
     * а объект сообщения живёт в core и перевода не видит.
     */
    fun key(): Str = when (kind) {
        Kind.SAVED -> Str.ERR_PRESET_SAVED
        Kind.APPLIED -> Str.ERR_PRESET_APPLIED
        Kind.EXPORTED -> Str.ERR_PRESET_EXPORTED
        Kind.IMPORTED -> Str.ERR_PRESET_IMPORTED
        Kind.DELETED -> Str.ERR_PRESET_DELETED
        Kind.FAILED -> Str.ERR_PRESET_FAILED
        Kind.INVALID -> Str.ERR_PRESET_INVALID
        Kind.UNAVAILABLE -> Str.ERR_PRESET_UNAVAILABLE
        Kind.BAD_FILE -> Str.ERR_PRESET_BAD_FILE
        Kind.EMPTY_FILE -> Str.ERR_PRESET_EMPTY
    }
}
