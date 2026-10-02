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

    /** Схема версии: нужна для миграций при чтении старых файлов. */
    val version: Int = CURRENT_VERSION
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
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
