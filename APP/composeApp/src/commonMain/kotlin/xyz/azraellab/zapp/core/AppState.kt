package xyz.azraellab.zapp.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    }

    /** Текущие настройки. Меняются целиком -- так проще рассуждать и не забыть поле. */
    var config: AppConfig = AppConfig()
        private set

    var presets: List<Preset> = emptyList()
        private set

    /** Возможности платформы; считаются один раз, они не меняются за сессию. */
    val caps: PlatformCaps = platformCaps()

    /** Итог последней операции с пресетом: сообщение и признак успеха. */
    var lastMessage: PresetMessage? = null
        private set

    private var saveJob: Job? = null
    private var meter: TrafficMeter = TrafficMeter()
    private var pollJob: Job? = null

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
        presets = runCatching { store.readPresets() }.getOrDefault(emptyList())
        meter = TrafficMeter(config.traffic)
    }

    /** Заменяет настройки целиком и планирует запись. */
    fun update(next: AppConfig) {
        config = next
        scheduleSave()
    }

    /** Точечное изменение: удобно для тумблеров и полей. */
    fun mutate(block: (AppConfig) -> AppConfig) {
        config = block(config)
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

    fun dispose() {
        stopTrafficMonitor()
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
