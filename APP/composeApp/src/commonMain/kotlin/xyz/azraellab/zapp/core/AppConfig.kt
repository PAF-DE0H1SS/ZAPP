package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Все настройки приложения в одном объекте.
 *
 * Хранится одним куском и сохраняется целиком: так настройки разных разделов
 * не могут разъехаться между собой, а импорт пресета -- одна запись, а не
 * слияние полей из разных мест.
 */
@Serializable
data class AppConfig(
    val zapret: ZapretConfig = ZapretConfig(),
    val goodbyeDpi: GoodbyeDpiConfig = GoodbyeDpiConfig(),
    val vpn: VpnConfig = VpnConfig(),
    val gps: GpsConfig = GpsConfig(),
    val traffic: TrafficConfig = TrafficConfig(),

    /** Показывать ли раздел мониторинга трафика. */
    val trafficMonitorEnabled: Boolean = true,

    /** Детальный журнал: действия, состояния, ошибки (файл zapp.log). */
    val detailLogEnabled: Boolean = true,

    /** Приветствие уже показано. false -- показываем при первом запуске. */
    val welcomeDone: Boolean = false,

    /**
     * Производительность: фон, размер страницы списка.
     *
     * Отдельный блок, потому что это свойства железа, а не поведения
     * обхода: пресеты его не переносят, а мини-опросник в приветствии
     * заполняет по классу устройства.
     */
    val perf: PerfConfig = PerfConfig(),

    /**
     * Ответы мини-опросника: как пользуются приложением.
     *
     * Пустой профиль -- опросник не заполнялся; `applied` показывает,
     * были ли ответы уже применены к настройкам.
     */
    val usage: UsageProfile = UsageProfile(),

    /** Схема версии: нужна для миграций при чтении старых файлов. */
    val version: Int = CURRENT_VERSION
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * Настройки производительности.
 *
 * Значения по умолчанию совпадают с прежним поведением приложения, чтобы
 * старый файл настроек после обновления выглядел и работал один в один.
 */
@Serializable
data class PerfConfig(
    /** Анимированное звёздное небо на фоне. false -- статичная отрисовка. */
    val animatedBackground: Boolean = true,

    /** Частота кадров фона. Ограничена 15..60: больше бессмысленно, меньше -- каша. */
    val backgroundFps: Int = 30,

    /** Сколько коннектов рисовать в списке за раз. */
    val listPageSize: Int = 30
) {
    fun sanitized(): PerfConfig = copy(
        backgroundFps = backgroundFps.coerceIn(15, 60),
        listPageSize = listPageSize.coerceIn(10, 120)
    )
}

/**
 * Ответы мини-опросника о характере использования.
 *
 * Коды -- стабильные строки, они попадают в файл настроек и сравниваются
 * в тестах; человеческие подписи живут в [Str]. Неизвестный код
 * интерпретируется как «не выбрано» и ничего не меняет.
 */
@Serializable
data class UsageProfile(
    /** Чего хотят от приложения: video | games | chat | privacy. */
    val purpose: String = "",

    /** Класс устройства: low | mid | high. */
    val device: String = "",

    /** Главный приоритет: speed | stability | battery. */
    val priority: String = "",

    /** Опросник заполнен и применён. */
    val applied: Boolean = false
) {
    companion object {
        const val PURPOSE_VIDEO = "video"
        const val PURPOSE_GAMES = "games"
        const val PURPOSE_CHAT = "chat"
        const val PURPOSE_PRIVACY = "privacy"

        const val DEVICE_LOW = "low"
        const val DEVICE_MID = "mid"
        const val DEVICE_HIGH = "high"

        const val PRIORITY_SPEED = "speed"
        const val PRIORITY_STABILITY = "stability"
        const val PRIORITY_BATTERY = "battery"
    }
}

/**
 * Применение ответов опросника к настройкам.
 *
 * Чистая функция без состояния: её же вызывает экран приветствия, и её же
 * покрывают тесты -- любое изменение маппинга видно сразу. Маппинг
 * консервативный: ответ меняет только то, что однозначно следует из него,
 * и никогда не трогает настройки обхода (стратегии, правила, домены).
 */
object UsageTuner {
    fun apply(config: AppConfig, usage: UsageProfile): AppConfig {
        if (!usage.applied) return config

        val perf = config.perf
            .copy(listPageSize = when (usage.device) {
                UsageProfile.DEVICE_LOW -> 15
                UsageProfile.DEVICE_MID -> 30
                UsageProfile.DEVICE_HIGH -> 60
                else -> config.perf.listPageSize
            })
            .copy(animatedBackground = when (usage.priority) {
                UsageProfile.PRIORITY_BATTERY -> false
                UsageProfile.PRIORITY_SPEED, UsageProfile.PRIORITY_STABILITY ->
                    config.perf.animatedBackground
                else -> config.perf.animatedBackground
            })
            .copy(backgroundFps = when {
                usage.priority == UsageProfile.PRIORITY_BATTERY -> 15
                usage.device == UsageProfile.DEVICE_LOW -> 20
                else -> config.perf.backgroundFps
            })

        val vpn = config.vpn.copy(
            groupMode = when (usage.purpose) {
                UsageProfile.PURPOSE_CHAT, UsageProfile.PURPOSE_VIDEO -> true
                else -> config.vpn.groupMode
            },
            killSwitch = when (usage.purpose) {
                UsageProfile.PURPOSE_PRIVACY -> true
                else -> config.vpn.killSwitch
            }
        )

        return config.copy(perf = perf.sanitized(), vpn = vpn)
    }
}

/**
 * Именованный пресет настроек.
 *
 * Пресет хранит только то, что имеет смысл переносить между устройствами:
 * правила обхода и профили VPN. Мониторинг трафика и язык сюда не входят,
 * потому что это свойства устройства, а не конфигурация обхода.
 */
@Serializable
data class Preset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val createdAtMs: Long = 0L,
    val zapret: ZapretConfig = ZapretConfig(),
    val goodbyeDpi: GoodbyeDpiConfig = GoodbyeDpiConfig(),
    val vpn: VpnConfig = VpnConfig(),
    val gps: GpsConfig = GpsConfig()
) {
    /** Применяет пресет к настройкам, не трогая устройственные поля. */
    fun applyTo(config: AppConfig): AppConfig = config.copy(
        zapret = zapret,
        goodbyeDpi = goodbyeDpi,
        vpn = vpn,
        gps = gps
    )

    fun validate(): List<String> {
        val problems = mutableListOf<String>()

        val profile = vpn.activeProfile() ?: vpn.profiles.firstOrNull()
        if (profile != null) {
            if (profile.gatewayPort !in 1..65535) problems += "gatewayPort"
            if (profile.mtu !in 576..9000) problems += "mtu"
            if (profile.keepaliveSeconds < 0) problems += "keepaliveSeconds"
        }

        zapret.toArgs().firstOrNull { it.startsWith("--") && it.endsWith("=-") }?.let {
            problems += "zapret"
        }

        if (gps.latitude !in -90.0..90.0) problems += "latitude"
        if (gps.longitude !in -180.0..180.0) problems += "longitude"

        return problems
    }

    companion object {
        /**
         * Собирает пресет из текущих настроек.
         *
         * Время передаётся снаружи, а не берётся из `Clock.System.now()`:
         * это лишняя зависимость ради одного числа, которое всё равно знает
         * вызывающий.
         */
        fun from(config: AppConfig, name: String, nowMs: Long): Preset = Preset(
            name = name,
            createdAtMs = nowMs,
            zapret = config.zapret,
            goodbyeDpi = config.goodbyeDpi,
            vpn = config.vpn,
            gps = config.gps
        )
    }
}

/**
 * Кодирование и разбор настроек.
 *
 * Отдельный объект, а не вызовы Json прямо в интерфейсе, потому что формат
 * настроек переживает приложение: файл пресета должен открываться не только
 * текущей версией. Отсюда два решения -- `ignoreUnknownKeys` (старый файл не
 * должен падать на новом поле) и `encodeDefaults` (в файле видно все ручки, а
 * не только изменённые).
 */
object ConfigCodec {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    private val compact: Json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Читает настройки. Любое повреждение файла даёт значения по умолчанию. */
    fun decodeConfig(text: String): AppConfig =
        runCatching { json.decodeFromString<AppConfig>(text) }.getOrDefault(AppConfig())

    fun decodeConfigOrNull(text: String): AppConfig? =
        runCatching { json.decodeFromString<AppConfig>(text) }.getOrNull()

    fun encodeConfig(config: AppConfig): String = json.encodeToString(AppConfig.serializer(), config)

    fun decodePreset(text: String): Preset =
        runCatching { json.decodeFromString<Preset>(text) }.getOrDefault(Preset())

    fun decodePresetOrNull(text: String): Preset? =
        runCatching { json.decodeFromString<Preset>(text) }.getOrNull()

    fun encodePreset(preset: Preset): String = json.encodeToString(Preset.serializer(), preset)

    /** Однострочная форма -- для буфера обмена и компактного экспорта. */
    fun encodePresetCompact(preset: Preset): String =
        compact.encodeToString(Preset.serializer(), preset)
}
