package xyz.azraellab.zapp.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import xyz.azraellab.zapp.core.daemon.DaemonEvent
import xyz.azraellab.zapp.core.daemon.DaemonId
import xyz.azraellab.zapp.core.daemon.DaemonSpec
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.daemon.createDaemonEngine
import xyz.azraellab.zapp.core.engine.TunnelEngine
import xyz.azraellab.zapp.core.engine.createTunnelEngine
import xyz.azraellab.zapp.core.gps.GpsEngine
import xyz.azraellab.zapp.core.gps.GpsState
import xyz.azraellab.zapp.core.gps.createGpsEngine
import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.native.NativeBinaries
import xyz.azraellab.zapp.core.net.ConfigImport
import xyz.azraellab.zapp.core.net.LinkProbe
import xyz.azraellab.zapp.core.net.NetworkProbe
import xyz.azraellab.zapp.core.net.StrategyReport
import xyz.azraellab.zapp.core.net.httpGetText
import xyz.azraellab.zapp.core.net.probeLink
import xyz.azraellab.zapp.core.net.probeTarget
import xyz.azraellab.zapp.core.notify.createNotifier
import xyz.azraellab.zapp.core.permissions.PermissionGateway
import xyz.azraellab.zapp.core.permissions.PermissionId
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.core.permissions.createPermissionGateway
import xyz.azraellab.zapp.core.probe.DeviceProbe
import xyz.azraellab.zapp.core.probe.probeDevice
import xyz.azraellab.zapp.core.update.APP_VERSION
import xyz.azraellab.zapp.core.update.ReleaseInfo
import xyz.azraellab.zapp.core.update.UpdateState
import xyz.azraellab.zapp.core.update.fetchLatestRelease
import xyz.azraellab.zapp.core.update.isNewerVersion

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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    companion object {
        /** Отложенная запись: столько ждём после последнего изменения. */
        const val SAVE_DEBOUNCE_MS = 400L

        /** Как часто опрашивать счётчики трафика при включённом мониторинге */
        const val POLL_FLOOR_MS: Int = 200

        /** Метки уведомлений: одна метка = одна строка в шторке. */
        const val TAG_GPS: String = "gps"
        const val TAG_UPDATE: String = "update"

        /** Как часто смотрим, не пора ли обновить источники коннектов. */
        const val SOURCE_REFRESH_TICK_MS: Long = 60_000L

        /** Сколько коннектов проверяем параллельно: больше -- давим сеть. */
        const val CHECK_PARALLEL: Int = 16

        /** Через сколько результатов писать их в список: плавность против шума. */
        const val CHECK_FLUSH_BATCH: Int = 8

        /** Пауза перед автоперезапуском упавшего демона. */
        const val DAEMON_RESTART_DELAY_MS: Long = 3_000L
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

    /** Результат разведки устройства; null, пока не проверили. */
    private val _probe = MutableStateFlow<DeviceProbe?>(null)
    val probe: StateFlow<DeviceProbe?> = _probe.asStateFlow()

    /** Итог проверки обновлений и последний найденный релиз. */
    private val _updateState = MutableStateFlow(UpdateState.UNKNOWN)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _release = MutableStateFlow<ReleaseInfo?>(null)
    val release: StateFlow<ReleaseInfo?> = _release.asStateFlow()

    /** Текущая версия -- та же константа, что участвует в сравнении. */
    val appVersion: String = APP_VERSION

    /** Итог последней операции с пресетом: сообщение и признак успеха. */
    var lastMessage: PresetMessage? = null
        private set

    private var saveJob: Job? = null
    private var meter: TrafficMeter = TrafficMeter()
    private var pollJob: Job? = null

    init {
        // Уведомления о подмене: движок сообщает только состояния, а что
        // именно сказать пользователю -- решает здесь общая подписка,
        // потому что события возникают из кода, где нет доступа к UI.
        scope.launch {
            var previous = gpsEngine.state.value
            gpsEngine.state.collect { current ->
                if (current != previous) {
                    when (current) {
                        GpsState.ACTIVE -> notify(Str.NOTIFY_GPS_ON, tag = TAG_GPS)
                        GpsState.ERROR -> notify(Str.NOTIFY_GPS_ERROR, tag = TAG_GPS)
                        GpsState.OFF -> if (previous == GpsState.ACTIVE || previous == GpsState.ERROR) {
                            notify(Str.NOTIFY_GPS_OFF, tag = TAG_GPS)
                        }
                        GpsState.STARTING -> Unit
                    }
                    previous = current
                }
            }
        }
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

    // --- Загрузка и сохранение ---

    /**
     * Загружает настройки и пресеты.
     *
     * Отсутствие файла -- не ошибка: это первый запуск, и тогда применяются
     * значения по умолчанию.
     */
    fun load() {
        config = runCatching { store.readConfig() }.getOrNull() ?: AppConfig()
        AppLog.enabled = config.detailLogEnabled
        AppLog.log("app", "start v${APP_VERSION}")
        presets = runCatching { store.readPresets() }.getOrDefault(emptyList())
        meter = TrafficMeter(config.traffic)
        gpsEngine.applyConfig(config.gps)
        tunnel.applyConfig(config.vpn)

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
    }

    /** Заменяет настройки целиком и планирует запись. */
    fun update(next: AppConfig) {
        config = next
        AppLog.enabled = config.detailLogEnabled
        gpsEngine.applyConfig(config.gps)
        tunnel.applyConfig(config.vpn)
        scheduleSave()
    }

    /** Точечное изменение: удобно для тумблеров и полей. */
    fun mutate(block: (AppConfig) -> AppConfig) {
        config = block(config)
        AppLog.enabled = config.detailLogEnabled
        // Движок читает конфиг каждый такт, поэтому здесь достаточно
        // передать ссылку -- такт сам подхватит новые значения.
        gpsEngine.applyConfig(config.gps)
        tunnel.applyConfig(config.vpn)
        scheduleSave()
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

    /** Выгрузка текущих настроек как пресета. */
    suspend fun exportPreset(): PresetMessage {
        if (!gateway.available) {
            lastMessage = PresetMessage.unavailable
            return PresetMessage.unavailable
        }
        val preset = Preset.from(config, "zapp", System.currentTimeMillis())
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
            while (isActive) {
                val period = config.traffic.intervalMs.coerceAtLeast(POLL_FLOOR_MS).toLong()
                val source = trafficSource()
                val sample = runCatching { source.read(source.nowMs()) }.getOrNull()
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

    /** Открывает или закрывает окно мониторинга. */
    fun setTrafficWindowOpen(open: Boolean) {
        _trafficWindowOpen.value = open
        if (open) ensureTrafficPolling()
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
     */
    fun startGps(onResult: (Boolean) -> Unit = {}) {
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

        fun step() {
            if (queue.isEmpty()) {
                gpsEngine.start(config.gps, vpnAddress())
                onResult(true)
                return
            }
            val id = queue.removeAt(0)
            permissions.request(id) { step() }
        }
        step()
    }

    /** Останавливает подмену. */
    fun stopGps() {
        AppLog.log("gps", "stop")
        gpsEngine.stop()
    }

    /** Адрес шлюза активного профиля -- нужен режиму «координаты VPN». */
    fun vpnAddress(): String =
        config.vpn.activeProfile()?.gateway?.trim().orEmpty()

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
        AppLog.log("zapret", "start: ${config.zapret.toArgs().joinToString(" ")}")
        if (!config.zapret.enabled) {
            mutate { it.copy(zapret = it.zapret.copy(enabled = true)) }
        }
        val c = config.zapret
        zapretDaemon.start(DaemonSpec(binary = c.binaryPath, args = c.toArgs()))
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
        dpiDaemon.start(
            DaemonSpec(
                binary = c.daemonPath,
                args = c.toArgs(blacklistRef = if (hosts.isNotEmpty()) "{{hosts}}" else null),
                files = files
            )
        )
    }

    fun stopDpi() {
        AppLog.log("goodbyedpi", "stop")
        dpiDaemon.stop()
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
        AppLog.log("vpn", "connect requested (group=${config.vpn.groupMode})")
        tunnel.connect()
    }

    fun disconnectVpn() {
        AppLog.log("vpn", "disconnect requested")
        tunnel.disconnect()
    }

    // --- Обновления ---

    /**
     * Проверяет последний релиз на GitHub.
     *
     * Повторный запуск во время полёта игнорируется: две параллельные
     * проверки дали бы гонку состояний без единого выигрыша.
     */
    fun checkForUpdates() {
        if (_updateState.value == UpdateState.CHECKING) return
        _updateState.value = UpdateState.CHECKING
        scope.launch {
            val found = runCatching { fetchLatestRelease() }.getOrNull()
            if (found == null) {
                _updateState.value = UpdateState.ERROR
                return@launch
            }
            _release.value = found
            val newer = isNewerVersion(found.version, appVersion)
            _updateState.value = if (newer) UpdateState.AVAILABLE else UpdateState.CURRENT
            if (newer) notify(Str.NOTIFY_UPDATE, found.version, TAG_UPDATE)
        }
    }

    /** Открывает страницу последнего релиза в браузере. */
    fun openReleasePage(): Boolean {
        val url = _release.value?.url ?: return false
        return xyz.azraellab.zapp.core.net.openUrl(url)
    }

    // --- Уведомления ---

    /**
     * Показывает системное уведомление с переводом.
     *
     * Язык читается здесь, а не передаётся из UI: события возникают в
     * корутинах, где Composable'ов нет, а настройка языка -- та же самая,
     * что и в интерфейсе.
     */
    private fun notify(str: Str, arg: String = "", tag: String) {
        if (!notifier.supported) return
        val chosen = AppLang.of(AppLangStore.read())
        val lang = resolveLang(chosen, AppLangStore.systemCode())
        notifier.show(title = str.of(lang), body = arg, tag = tag)
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

    /** Добавляет свой источник по URL подписки; false -- адрес не похож на URL. */
    fun addSource(url: String, name: String): Boolean {
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
                        url = clean
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
                setStatus(source.id, SourceState.ERROR, 0, error)
                continue
            }

            val links = when (source.kind) {
                "tor" -> replaceTorSource(source, text)
                else -> replaceProxySource(source, text)
            }
            markSourceRefreshed(source.id)
            setStatus(source.id, SourceState.OK, links)
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
            current.copy(
                vpn = current.vpn.copy(
                    profiles = profiles,
                    activeProfileId = profiles.firstOrNull { it.id == current.vpn.activeProfileId }?.id
                        ?: current.vpn.activeProfileId
                )
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
                )
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
     * JSON, мосты Tor или адрес подписки. Подписка превращается в источник --
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

    // --- Автостарт и автоперезапуск ---

    /**
     * Старт после распаковки бинарей: демоны с включённым тумблером,
     * туннель по флагу «старт при запуске», источники -- которых ещё
     * ни разу не грузили. Всё это фон: приложение уже показало экран.
     */
    private suspend fun startAutoServices() {
        if (config.zapret.enabled) startZapret()
        if (config.goodbyeDpi.autoStart &&
            GoodbyeDpiMode.of(config.goodbyeDpi.mode) != GoodbyeDpiMode.DISABLED
        ) {
            startDpi()
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
    }

    // --- Автоподбор стратегии обхода ---

    /**
     * Итог автоподбора: живой отчёт проверки плюс решение.
     *
     * Один на обе страницы: zapret и goodbyedpi проверяют одну и ту же
     * сеть, и прогонять замеры дважды ради разных кнопок -- бессмысленно.
     * Страница показывает только своё решение (по [StrategyOutcome.target]).
     */
    data class StrategyOutcome(
        val target: String,
        val report: StrategyReport,
        val decision: AutoStrategy.Decision
    )

    private val _strategyRunning = MutableStateFlow(false)
    val strategyRunning: StateFlow<Boolean> = _strategyRunning.asStateFlow()

    private val _strategyOutcome = MutableStateFlow<StrategyOutcome?>(null)
    val strategyOutcome: StateFlow<StrategyOutcome?> = _strategyOutcome.asStateFlow()

    /** "zapret" или "gdp". */
    fun autoPickStrategy(target: String) {
        if (_strategyRunning.value) return
        _strategyRunning.value = true
        scope.launch {
            val report = runCatching { NetworkProbe.run() }.getOrNull()
            if (report == null) {
                _strategyRunning.value = false
                return@launch
            }
            val decision = if (target == "zapret") {
                AutoStrategy.chooseZapretStrategy(report.probe, config.zapret)
            } else {
                AutoStrategy.chooseGoodbyeDpiMode(report.probe)
            }

            // Сеть недоступна целиком: применять «стратегию» нечего --
            // это не выбор между обходами, а отсутствие данных.
            val noData = report.steps.all { it.result == AutoStrategy.ProbeResult.Unknown }
            if (!noData) {
                mutate { current ->
                    if (target == "zapret") {
                        current.copy(zapret = AutoStrategy.applyZapret(current.zapret, decision))
                    } else {
                        current.copy(goodbyeDpi = current.goodbyeDpi.copy(mode = decision.strategy))
                    }
                }
            }
            _strategyOutcome.value = StrategyOutcome(target, report, decision)
            _strategyRunning.value = false
        }
    }

    fun clearStrategyOutcome() {
        _strategyOutcome.value = null
    }

    fun dispose() {
        stopTrafficMonitor()
        gpsEngine.stop()
        zapretDaemon.stop()
        dpiDaemon.stop()
        tunnel.disconnect()
        saveJob?.cancel()
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
