package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.azraellab.zapp.core.perApp.PerAppConfig
import xyz.azraellab.zapp.core.probe.RelayTuning

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
     * Простой режим: только базовый сценарий -- вкладки VPN и настройки,
     * без продвинутых разделов страниц.
     *
     * Прячет, но не выключает: туннель, коннекты и все прочие режимы
     * остаются рабочими, меняется только видимость. Дефолт false:
     * существующие пользователи не должны проснуться в урезанном UI.
     */
    val noviceMode: Boolean = false,

    /**
     * Поднимать ли автосервисы при загрузке устройства.
     *
     * Дефолт false: раньше приложение никогда не стартовало само после
     * перезагрузки, и тумблер не должен молча это менять. Что именно
     * поднимется -- решают прежние флаги (автостарт туннеля, включённый
     * обход, подмена геолокации); без них старт из загрузчика -- пустая
     * трата процесса, и [autostartOnBoot] это учитывает.
     */
    val bootAutostart: Boolean = false,

    /**
     * Системный прокси десктопа: пока туннель поднят, браузеры
     * и другие программы, следующие прокси машины, уходят через локальные
     * входы sing-box.
     *
     * Дефолт false: приложение никогда не трогало системные настройки
     * прокси, и тумблер не должен молча это менять. Поле устройственное --
     * пресеты его не переносят, а на платформах без поддержки
     * (см. `platformCaps().systemProxySupported`) не читается вовсе.
     */
    val systemProxy: Boolean = false,

    /**
     * Тема оформления: система / тёмная / светлая.
     *
     * Дефолт -- тёмная: это исходный вид приложения, переключатель его
     * не меняет, а лишь открывает светлую схему тем, кому она нужна.
     */
    val themeMode: ThemeMode = ThemeMode.DARK,

    /**
     * Динамические цвета Material You -- палитра из обоев устройства.
     *
     * Android 12+; на старых версиях и на десктопе мост отдаёт null и
     * остаётся выбранная схема. Гаснет автоматически, если платформа
     * динамических цветов не даёт.
     */
    val dynamicColors: Boolean = true,

    /**
     * Как сравнивать версии при проверке обновлений: `semver` -- по
     * числам, `tag` -- по любому отличию тега (см. updateAvailable).
     */
    val updateCompareMode: String = "semver",

    /**
     * Напоминания об обновлениях отложены до этого момента (эпоха, мс).
     *
     * 0 -- не отложено. После [localDayEndMs] тапа «не напоминать
     * сегодня» уведомление молчит до конца суток, сама проверка при этом
     * продолжает работать и состояние в настройках остаётся честным.
     */
    val updateSnoozeUntilMs: Long = 0L,

    /**
     * ID показанного объявления разработчика; пусто -- ничего не скрыто.
     *
     * Объявление приходит в теле релиза (см. parseNotice) и показывается
     * баннером, пока его не закрыли и не истёк срок: закрытое гаснет
     * навсегда для своего текста, новое содержимое -- новый ID и снова
     * на экране.
     */
    val noticeDismissedId: String = "",

    /**
     * История трафика по дням (компактный текст).
     *
     * Отдельно от счётчиков сессии: переживает перезапуск и отвечает
     * на «за сегодня / за неделю». Формат -- строки «день,вход,исход»
     * (см. TrafficHistory.encode).
     */
    val trafficHistory: String = "",

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

    /**
     * Регион/страна пользователя (ISO 3166-1 alpha-2, например "RU").
     *
     * Один из факторов автонастройки: для части регионов есть политика
     * (скрытые протоколы, принудительные механизмы). Пусто -- страна не
     * выбрана, ведёт себя как нейтральный регион.
     */
    val region: String = "",

    /**
     * Выбор приложений для функций обхода (per-app).
     *
     * Хранится в основном конфиге, а не внутри VpnConfig: выбор общий для
     * VPN, Tor, Zapret и GoodbyeDPI, а профили VPN -- лишь часть конфига.
     * Пресеты переносят выбор: куда уезжает профиль, туда уезжает и набор
     * приложений, которые через него ходят.
     */
    val perApp: PerAppConfig = PerAppConfig(),

    /**
     * Тюнинг локального релея обхода (noroot-сборки).
     *
     * Общий для zapret и goodbyedpi: релей у них один. Заполняется
     * автоподбором стратегии -- в сборке без root «стратегия» и есть
     * параметры этого разрыва.
     */
    val relay: RelayTuning = RelayTuning(),

    /**
     * Механизм обхода без root: встроенный релей или процесс byedpi.
     *
     * Общий для zapret и goodbyedpi -- порт у них один. В root-сборке не
     * читается: там обход идёт через ядро, и поле лежит без дела.
     */
    val dpiBackend: String = DpiBackend.RELAY.code,

    /** Настройки byedpi: стратегия и хвост своих аргументов. */
    val byeDpi: ByeDpiConfig = ByeDpiConfig(),

    /** Схема версии: нужна для миграций при чтении старых файлов. */
    val version: Int = CURRENT_VERSION
) {
    /**
     * Стоит ли поднимать приложение при загрузке устройства.
     *
     * Мастер-тумблер плюс хотя бы один реальный флаг автостарта. Условия
     * здесь намеренно грубые (без проверки профиля и режимов): это только
     * решение «будить ли процесс», а полную проверку условий выполняет
     * сам startAutoServices при старте -- перебудка без дела дешевле, чем
     * молча пропущенный старт.
     */
    fun autostartOnBoot(): Boolean = bootAutostart &&
        (vpn.autoStart || zapret.enabled || goodbyeDpi.autoStart || gps.enabled)

    companion object {
        const val CURRENT_VERSION: Int = 3
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
 * правила обхода, профили VPN и регион (от региона зависит, какие протоколы
 * показывать). Мониторинг трафика и язык сюда не входят, потому что это
 * свойства устройства, а не конфигурация обхода.
 */
@Serializable
data class Preset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val createdAtMs: Long = 0L,
    val region: String = "",
    val zapret: ZapretConfig = ZapretConfig(),
    val goodbyeDpi: GoodbyeDpiConfig = GoodbyeDpiConfig(),
    val vpn: VpnConfig = VpnConfig(),
    val gps: GpsConfig = GpsConfig(),
    val perApp: PerAppConfig = PerAppConfig()
) {
    /** Применяет пресет к настройкам, не трогая устройственные поля. */
    fun applyTo(config: AppConfig): AppConfig =
        RegionTuner.apply(config.copy(
            zapret = zapret,
            goodbyeDpi = goodbyeDpi,
            vpn = mergedVpn(config.vpn),
            gps = gps,
            region = region,
            perApp = perApp
        ), region)

    /**
     * VPN-часть пресета, склеенная со списком коннектов принимающей стороны.
     *
     * Пустой список -- это пресет настроек, а не «удали всё»: иначе
     * загрузка готовой настройки стирала бы все коннекты, и СТАРТ отвечал
     * бы `no active profile`. Список в пресете есть -- берём его, но выбор
     * обязан попадать внутрь: подмена 868 профилей на 789 оставляла старый
     * `activeProfileId` в воздухе, и та же ошибка появлялась уже здесь.
     */
    private fun mergedVpn(current: VpnConfig): VpnConfig {
        if (vpn.profiles.isEmpty()) {
            return vpn.copy(
                profiles = current.profiles,
                activeProfileId = current.activeProfileId
            )
        }
        val active = vpn.activeProfileId
            .takeIf { id -> vpn.profiles.any { it.id == id } }
            ?: current.activeProfileId.takeIf { id -> vpn.profiles.any { it.id == id } }
            ?: vpn.profiles.first().id
        return vpn.copy(activeProfileId = active)
    }

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
         *
         * Коннекты по умолчанию не включаются: тысяча профилей тянет
         * за собой учётные данные всех узлов и раздувает файл до мегабайтов,
         * а для передачи настроек -- региона, обхода, правил по приложениям
         * -- список не нужен. При [includeProfiles] каждый профиль получает
         * id и имя, если они пусты: иначе в файле остаются строки без
         * названия и с одинаковым пустым id, по которым импорт не отличает
         * один коннект от другого.
         */
        fun from(
            config: AppConfig,
            name: String,
            nowMs: Long,
            includeProfiles: Boolean = false
        ): Preset = Preset(
            name = name,
            createdAtMs = nowMs,
            region = config.region,
            zapret = config.zapret,
            goodbyeDpi = config.goodbyeDpi,
            vpn = if (includeProfiles) {
                config.vpn.copy(
                    profiles = config.vpn.profiles.mapIndexed { i, p -> p.exported(i) }
                )
            } else {
                config.vpn.copy(profiles = emptyList(), activeProfileId = "")
            },
            gps = config.gps,
            perApp = config.perApp
        )
    }
}

/**
 * Профиль, пригодный для записи в файл пресета.
 *
 * Пустой id склеивает при импорте разные коннекты в один, пустое имя даёт
 * в списке строку без названия. Обе величины берутся из того, что у профиля
 * уже есть: протокол и адрес шлюза -- это и есть то, по чему коннект
 * узнают на глаз. Индекс входит в id, чтобы совпадения не было и у
 * одинаково настроенных профилей.
 */
private fun VpnProfile.exported(index: Int): VpnProfile = copy(
    id = id.ifBlank { "p$index-${gateway.hashCode().toString(36)}" },
    name = name.ifBlank {
        listOf(protocol, gateway)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "profile $index" }
    }
)

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
