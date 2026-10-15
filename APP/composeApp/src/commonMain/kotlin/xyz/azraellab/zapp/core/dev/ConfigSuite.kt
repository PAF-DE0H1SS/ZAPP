package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.ConfigCodec
import xyz.azraellab.zapp.core.GoodbyeDpiConfig
import xyz.azraellab.zapp.core.GoodbyeDpiMode
import xyz.azraellab.zapp.core.PerfConfig
import xyz.azraellab.zapp.core.Preset
import xyz.azraellab.zapp.core.TrafficConfig
import xyz.azraellab.zapp.core.UsageProfile
import xyz.azraellab.zapp.core.UsageTuner
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.ZapretConfig
import xyz.azraellab.zapp.core.GpsConfig

/**
 * Настройки: кодек, пресеты, дефолты, санитайзеры.
 *
 * Главное свойство файла настроек -- обратимость и терпимость к мусору:
 * то, что записали, читается один в один, а битый или старый файл не
 * роняет приложение, а откатывается на дефолты.
 */
internal fun configTests(out: MutableList<DevTest>) {

    // --- Кодек: обратимость ---

    out += DevTest("config/roundtrip-defaults", DevCategory.CONFIG, "Настройки по умолчанию переживают запись/чтение") {
        val decoded = ConfigCodec.decodeConfig(ConfigCodec.encodeConfig(AppConfig()))
        checkEq(AppConfig(), decoded, "дефолтный конфиг")
    }

    listOf(
        "zapret" to AppConfig(zapret = ZapretConfig(enabled = true, allTraffic = true, strategy = "fake")),
        "goodbyeDpi" to AppConfig(goodbyeDpi = GoodbyeDpiConfig(mode = GoodbyeDpiMode.SPLIT2.code)),
        "vpn" to AppConfig(vpn = VpnConfig(enabled = true, groupMode = true, torSocksPort = 9150)),
        "gps" to AppConfig(gps = GpsConfig(enabled = true, latitude = 55.75, longitude = 37.61)),
        "traffic" to AppConfig(traffic = TrafficConfig(enabled = false, unitSystem = "decimal")),
        "trafficMonitorEnabled" to AppConfig(trafficMonitorEnabled = false),
        "detailLogEnabled" to AppConfig(detailLogEnabled = false),
        "welcomeDone" to AppConfig(welcomeDone = true),
        "perf" to AppConfig(perf = PerfConfig(animatedBackground = false, backgroundFps = 15, listPageSize = 60)),
        "usage" to AppConfig(
            usage = UsageProfile(
                purpose = UsageProfile.PURPOSE_PRIVACY,
                device = UsageProfile.DEVICE_LOW,
                priority = UsageProfile.PRIORITY_BATTERY,
                applied = true
            )
        ),
        "version" to AppConfig(version = 1),
        "profiles" to AppConfig(
            vpn = VpnConfig(
                activeProfileId = "x",
                profiles = listOf(
                    xyz.azraellab.zapp.core.VpnProfile(
                        id = "x",
                        name = "Node",
                        protocol = VpnProtocol.VLESS.code,
                        gateway = "1.2.3.4"
                    )
                )
            )
        )
    ).forEach { (field, config) ->
        out += DevTest(
            "config/roundtrip-$field",
            DevCategory.CONFIG,
            "Поле $field переживает roundtrip"
        ) {
            val decoded = ConfigCodec.decodeConfig(ConfigCodec.encodeConfig(config))
            checkEq(config, decoded, "roundtrip $field")
        }
    }

    // --- Кодек: мусор и совместимость ---

    listOf(
        "not a json at all" to "текст",
        "{\"zapret\": " to "обрыв JSON",
        "" to "пустая строка",
        "[1, 2, 3]" to "массив вместо объекта",
        "null" to "null"
    ).forEach { (input, what) ->
        out += DevTest(
            "config/corrupt-${what.hashCode().toUInt().toString(16).take(8)}",
            DevCategory.CONFIG,
            "Битый конфиг ($what) откатывается на дефолт"
        ) {
            checkEq(AppConfig(), ConfigCodec.decodeConfig(input), "fallback для $what")
        }
    }

    out += DevTest("config/corrupt-returns-null", DevCategory.CONFIG, "decodeConfigOrNull не бросает, а отдаёт null") {
        checkEq(null, ConfigCodec.decodeConfigOrNull("junk"), "мусор")
        checkEq(null, ConfigCodec.decodeConfigOrNull("{"), "обрыв")
        checkTrue(ConfigCodec.decodeConfigOrNull("{}") != null, "пустой объект валиден")
    }

    out += DevTest("config/old-file-gets-defaults", DevCategory.CONFIG, "Старый файл без новых блоков читается") {
        val old = """
            {
                "zapret": {"allTraffic": true},
                "trafficMonitorEnabled": false,
                "welcomeDone": true,
                "version": 1
            }
        """.trimIndent()
        val decoded = ConfigCodec.decodeConfig(old)
        checkEq(PerfConfig(), decoded.perf, "perf из дефолта")
        checkEq(UsageProfile(), decoded.usage, "usage из дефолта")
        checkTrue(decoded.zapret.allTraffic, "zapret не потерян")
        checkEq(false, decoded.trafficMonitorEnabled, "trafficMonitorEnabled")
        checkTrue(decoded.welcomeDone, "welcomeDone")
    }

    out += DevTest("config/unknown-keys-ignored", DevCategory.CONFIG, "Чужие ключи будущих версий игнорируются") {
        val withJunk = """
            {"futureField": {"nested": 1}, "perf": {"backgroundFps": 24}, "other": 5}
        """.trimIndent()
        val decoded = ConfigCodec.decodeConfig(withJunk)
        checkEq(24, decoded.perf.backgroundFps, "известное поле прочитано")
        checkEq(30, decoded.perf.listPageSize, "отсутствующее поле -- дефолт")
    }

    listOf(
        "\"animatedBackground\"" to "perf",
        "\"backgroundFps\"" to "perf fps",
        "\"listPageSize\"" to "perf listPageSize",
        "\"usage\"" to "usage",
        "\"applied\"" to "usage.applied",
        "\"welcomeDone\"" to "welcomeDone",
        "\"detailLogEnabled\"" to "detailLogEnabled",
        "\"groupMode\"" to "vpn.groupMode",
        "\"monthlyLimitBytes\"" to "traffic limit"
    ).forEach { (needle, what) ->
        out += DevTest(
            "config/encoded-contains-${what.lowercase().filter { it.isLetterOrDigit() }}",
            DevCategory.CONFIG,
            "encodeDefaults пишет в файл $what"
        ) {
            checkContains(ConfigCodec.encodeConfig(AppConfig()), needle, "в файле нет $needle")
        }
    }

    // --- Пресеты ---

    out += DevTest("config/preset-roundtrip", DevCategory.CONFIG, "Пресет переживает запись/чтение") {
        val preset = Preset(
            id = "p1",
            name = "Мой",
            description = "описание",
            createdAtMs = 123L,
            zapret = ZapretConfig(enabled = true),
            gps = GpsConfig(mode = "route", route = "0,0;1,1")
        )
        val decoded = ConfigCodec.decodePreset(ConfigCodec.encodePreset(preset))
        checkEq(preset, decoded, "пресет")
    }

    out += DevTest("config/preset-compact-roundtrip", DevCategory.CONFIG, "Компактный пресет читается обратно") {
        val preset = Preset(id = "p2", name = "short", zapret = ZapretConfig(allTraffic = true))
        val decoded = ConfigCodec.decodePreset(ConfigCodec.encodePresetCompact(preset))
        checkEq(preset, decoded, "компактный пресет")
        checkNotContains(ConfigCodec.encodePresetCompact(preset), "\n", "компактная форма -- одна строка")
    }

    out += DevTest("config/preset-corrupt-default", DevCategory.CONFIG, "Битый файл пресета -- дефолтный пресет") {
        checkEq(Preset(), ConfigCodec.decodePreset("junk"), "fallback")
        checkEq(null, ConfigCodec.decodePresetOrNull("{"), "null вместо исключения")
    }

    out += DevTest("config/preset-apply-touches-only-bypass", DevCategory.CONFIG, "Пресет меняет только обход и VPN") {
        val base = AppConfig(
            trafficMonitorEnabled = false,
            welcomeDone = true,
            perf = PerfConfig(listPageSize = 42)
        )
        val applied = Preset(
            zapret = ZapretConfig(enabled = true),
            vpn = VpnConfig(enabled = true),
            gps = GpsConfig(enabled = true)
        ).applyTo(base)
        checkTrue(applied.zapret.enabled, "zapret применён")
        checkTrue(applied.vpn.enabled, "vpn применён")
        checkTrue(applied.gps.enabled, "gps применён")
        checkEq(false, applied.trafficMonitorEnabled, "устройственное поле не тронуто")
        checkTrue(applied.welcomeDone, "welcomeDone не тронут")
        checkEq(42, applied.perf.listPageSize, "perf не тронут")
    }

    out += DevTest("config/preset-from-copies-bypass", DevCategory.CONFIG, "Preset.from снимает только четыре секции") {
        val config = AppConfig(
            zapret = ZapretConfig(enabled = true),
            goodbyeDpi = GoodbyeDpiConfig(mode = "split2"),
            trafficMonitorEnabled = false
        )
        val preset = Preset.from(config, name = "имя", nowMs = 777L)
        checkEq("имя", preset.name, "имя")
        checkEq(777L, preset.createdAtMs, "время")
        checkEq(config.zapret, preset.zapret, "zapret")
        checkEq(config.goodbyeDpi, preset.goodbyeDpi, "goodbyeDpi")
    }

    // --- B5: коннекты в пресете ---

    out += DevTest("config/preset-from-strips-connections", DevCategory.CONFIG, "Preset.from по умолчанию не тащит коннекты") {
        val config = AppConfig(
            vpn = VpnConfig(
                activeProfileId = "a",
                profiles = listOf(VpnProfile(id = "a", gateway = "1.2.3.4")),
                killSwitch = true
            )
        )
        val plain = Preset.from(config, name = "zapp", nowMs = 1L)
        checkTrue(plain.vpn.profiles.isEmpty(), "без галочки список пуст")
        checkEq("", plain.vpn.activeProfileId, "и выбор сброшен")
        checkTrue(plain.vpn.killSwitch, "настройки туннеля при этом сохранены")

        val full = Preset.from(config, name = "zapp", nowMs = 1L, includeProfiles = true)
        checkEq(1, full.vpn.profiles.size, "с галочкой список внутри")
        checkEq("a", full.vpn.activeProfileId, "выбор сохранён")
    }

    out += DevTest("config/preset-from-fills-identity", DevCategory.CONFIG, "Профиль без id и имени получает оба") {
        val config = AppConfig(
            vpn = VpnConfig(profiles = listOf(VpnProfile(gateway = "5.6.7.8")))
        )
        val profile = Preset.from(config, name = "zapp", nowMs = 1L, includeProfiles = true)
            .vpn.profiles.single()
        checkTrue(profile.id.isNotBlank(), "id пустой")
        checkTrue(profile.name.isNotBlank(), "имя пустое")
        checkContains(profile.name, "5.6.7.8", "в имени остаётся адрес шлюза")
    }

    out += DevTest("config/preset-apply-keeps-connections", DevCategory.CONFIG, "Пресет без коннектов не стирает список") {
        val base = AppConfig(
            vpn = VpnConfig(
                activeProfileId = "keep",
                profiles = listOf(VpnProfile(id = "keep", gateway = "9.9.9.9"))
            )
        )
        val applied = Preset(vpn = VpnConfig(enabled = true)).applyTo(base)
        checkEq(1, applied.vpn.profiles.size, "список цел")
        checkEq("keep", applied.vpn.activeProfileId, "выбор цел")
        checkTrue(applied.vpn.enabled, "настройки пресета применены")
    }

    out += DevTest("config/preset-apply-repairs-active-id", DevCategory.CONFIG, "Выбор, оставшийся за пределами списка, чинится") {
        val base = AppConfig(
            vpn = VpnConfig(
                activeProfileId = "gone",
                profiles = listOf(VpnProfile(id = "gone"), VpnProfile(id = "survivor"))
            )
        )
        val applied = Preset(
            vpn = VpnConfig(profiles = listOf(VpnProfile(id = "survivor", gateway = "1.1.1.1")))
        ).applyTo(base)
        checkEq("survivor", applied.vpn.activeProfileId, "выбор переехал на существующий")
    }

    // --- Валидация пресета ---

    listOf(
        "ok" to (Preset() to emptyList<String>()),
        "bad-port" to (Preset(vpn = VpnConfig(profiles = listOf(xyz.azraellab.zapp.core.VpnProfile(gatewayPort = 70000)))) to listOf("gatewayPort")),
        "bad-mtu" to (Preset(vpn = VpnConfig(profiles = listOf(xyz.azraellab.zapp.core.VpnProfile(mtu = 100)))) to listOf("mtu")),
        "bad-keepalive" to (Preset(vpn = VpnConfig(profiles = listOf(xyz.azraellab.zapp.core.VpnProfile(keepaliveSeconds = -1)))) to listOf("keepaliveSeconds")),
        "bad-latitude" to (Preset(gps = GpsConfig(latitude = 120.0)) to listOf("latitude")),
        "bad-longitude" to (Preset(gps = GpsConfig(longitude = -999.0)) to listOf("longitude"))
    ).forEach { (id, case) ->
        val (preset, expected) = case
        out += DevTest(
            "config/preset-validate-$id",
            DevCategory.CONFIG,
            "validate($id) == $expected"
        ) {
            checkEq(expected, preset.validate(), "проблемы пресета")
        }
    }

    // --- Санитайзеры и опросник ---

    out += DevTest("config/perf-sanitize-high", DevCategory.CONFIG, "Перебор значений жмётся к верхней границе") {
        val perf = PerfConfig(backgroundFps = 9999, listPageSize = 1).sanitized()
        checkEq(60, perf.backgroundFps, "fps сверху")
        checkEq(10, perf.listPageSize, "listPageSize снизу")
    }

    out += DevTest("config/perf-sanitize-low", DevCategory.CONFIG, "Слишком низкие значения жмётся к нижней границе") {
        val perf = PerfConfig(backgroundFps = 1, listPageSize = 100000).sanitized()
        checkEq(15, perf.backgroundFps, "fps снизу")
        checkEq(120, perf.listPageSize, "listPageSize сверху")
    }

    out += DevTest("config/perf-sanitize-keeps-normal", DevCategory.CONFIG, "Нормальные значения не меняются") {
        val perf = PerfConfig(animatedBackground = false, backgroundFps = 30, listPageSize = 45).sanitized()
        checkEq(30, perf.backgroundFps, "fps")
        checkEq(45, perf.listPageSize, "listPageSize")
        checkFalse(perf.animatedBackground, "анимация не включается сама")
    }

    // --- Трафик: геттеры конфига ---

    out += DevTest("config/traffic-interval-clamped", DevCategory.CONFIG, "Интервал опроса зажат 0.2..60 с") {
        checkEq(0.2, TrafficConfig(intervalMs = 100).intervalSeconds, "снизу")
        checkEq(60.0, TrafficConfig(intervalMs = 9999999).intervalSeconds, "сверху")
        checkEq(1.0, TrafficConfig(intervalMs = 1000).intervalSeconds, "обычный")
    }

    out += DevTest("config/traffic-points-clamped", DevCategory.CONFIG, "Точек истории 10..2000") {
        checkEq(10, TrafficConfig(historyPoints = 1).points, "снизу")
        checkEq(2000, TrafficConfig(historyPoints = 99999).points, "сверху")
        checkEq(120, TrafficConfig().points, "дефолт")
    }

    out += DevTest("config/traffic-limit", DevCategory.CONFIG, "Лимит включён только при положительном числе") {
        checkFalse(TrafficConfig(monthlyLimitBytes = 0).limitEnabled(), "0 -- без лимита")
        checkTrue(TrafficConfig(monthlyLimitBytes = 1).limitEnabled(), "1 -- лимит есть")
    }

    // --- Опросник ---

    fun usage(
        purpose: String = "",
        device: String = "",
        priority: String = "",
        applied: Boolean = true
    ) = UsageProfile(purpose = purpose, device = device, priority = priority, applied = applied)

    val defaults = AppConfig()

    out += DevTest("config/usage-not-applied-untouched", DevCategory.CONFIG, "Не применённый опросник не меняет конфиг") {
        checkEq(defaults, UsageTuner.apply(defaults, usage(applied = false)), "конфиг")
    }

    out += DevTest("config/usage-empty-answers", DevCategory.CONFIG, "Пустые ответы ничего не меняют") {
        val cfg = UsageTuner.apply(defaults, usage())
        checkEq(defaults.perf, cfg.perf, "perf")
        checkEq(defaults.vpn.groupMode, cfg.vpn.groupMode, "groupMode")
        checkEq(defaults.vpn.killSwitch, cfg.vpn.killSwitch, "killSwitch")
    }

    out += DevTest("config/usage-unknown-codes", DevCategory.CONFIG, "Неизвестные коды ответов игнорируются") {
        val cfg = UsageTuner.apply(defaults, usage(purpose = "mining", device = "toaster", priority = "quantum"))
        checkEq(defaults.perf, cfg.perf, "perf")
        checkEq(defaults.vpn, cfg.vpn, "vpn")
    }

    mapOf(
        UsageProfile.DEVICE_LOW to 15,
        UsageProfile.DEVICE_MID to 30,
        UsageProfile.DEVICE_HIGH to 60
    ).forEach { (device, pageSize) ->
        out += DevTest(
            "config/usage-device-$device",
            DevCategory.CONFIG,
            "Устройство $device -> listPageSize=$pageSize"
        ) {
            checkEq(pageSize, UsageTuner.apply(defaults, usage(device = device)).perf.listPageSize)
        }
    }

    out += DevTest("config/usage-low-device-fps", DevCategory.CONFIG, "Слабое устройство -- медленнее фон (20 fps)") {
        val perf = UsageTuner.apply(defaults, usage(device = UsageProfile.DEVICE_LOW)).perf
        checkEq(20, perf.backgroundFps, "fps")
        checkTrue(perf.animatedBackground, "фон замедляется, а не выключается")
    }

    out += DevTest("config/usage-battery-priority", DevCategory.CONFIG, "Приоритет «батарея» гасит анимацию") {
        val perf = UsageTuner.apply(defaults, usage(priority = UsageProfile.PRIORITY_BATTERY)).perf
        checkFalse(perf.animatedBackground, "анимация")
        checkEq(15, perf.backgroundFps, "fps")
    }

    out += DevTest("config/usage-speed-stability-keep-background", DevCategory.CONFIG, "Скорость/стабильность не гасят фон") {
        for (priority in listOf(UsageProfile.PRIORITY_SPEED, UsageProfile.PRIORITY_STABILITY)) {
            val perf = UsageTuner.apply(defaults, usage(priority = priority)).perf
            checkTrue(perf.animatedBackground, priority)
            checkEq(30, perf.backgroundFps, priority)
        }
    }

    out += DevTest("config/usage-privacy-killswitch", DevCategory.CONFIG, "Цель «приватность» включает kill switch") {
        val vpn = UsageTuner.apply(defaults, usage(purpose = UsageProfile.PURPOSE_PRIVACY)).vpn
        checkTrue(vpn.killSwitch, "killSwitch")
    }

    out += DevTest("config/usage-chat-video-groupmode", DevCategory.CONFIG, "Чат/видео включают режим группы") {
        for (purpose in listOf(UsageProfile.PURPOSE_CHAT, UsageProfile.PURPOSE_VIDEO)) {
            val vpn = UsageTuner.apply(defaults, usage(purpose = purpose)).vpn
            checkTrue(vpn.groupMode, purpose)
        }
    }

    out += DevTest("config/usage-games-untouched", DevCategory.CONFIG, "Игры и пустая цель не трогают VPN") {
        for (purpose in listOf(UsageProfile.PURPOSE_GAMES, "")) {
            val cfg = UsageTuner.apply(defaults, usage(purpose = purpose))
            checkEq(defaults.vpn.groupMode, cfg.vpn.groupMode, purpose)
            checkEq(defaults.vpn.killSwitch, cfg.vpn.killSwitch, purpose)
        }
    }

    out += DevTest("config/usage-never-touches-bypass", DevCategory.CONFIG, "Опросник не меняет стратегии обхода") {
        val cfg = UsageTuner.apply(defaults, usage(purpose = UsageProfile.PURPOSE_GAMES))
        checkEq(defaults.zapret, cfg.zapret, "zapret")
        checkEq(defaults.goodbyeDpi, cfg.goodbyeDpi, "goodbyeDpi")
    }

    out += DevTest("config/usage-combined", DevCategory.CONFIG, "Ответы складываются вместе") {
        val cfg = UsageTuner.apply(
            defaults,
            usage(
                purpose = UsageProfile.PURPOSE_PRIVACY,
                device = UsageProfile.DEVICE_LOW,
                priority = UsageProfile.PRIORITY_BATTERY
            )
        )
        checkEq(15, cfg.perf.listPageSize, "listPageSize")
        checkFalse(cfg.perf.animatedBackground, "анимация")
        checkEq(15, cfg.perf.backgroundFps, "fps")
        checkTrue(cfg.vpn.killSwitch, "killSwitch")
    }

    // --- Старт из перезагрузки (§137): мастер-тумблер + реальный флаг ---

    out += DevTest("config/boot-autostart-gate", DevCategory.CONFIG, "Старт из загрузки требует и тумблер, и флаг") {
        checkFalse(AppConfig().autostartOnBoot(), "дефолт: ничего не поднимается")
        checkFalse(
            AppConfig(bootAutostart = true).autostartOnBoot(),
            "тумблер без флагов -- будить нечего"
        )
        checkFalse(
            AppConfig(zapret = ZapretConfig(enabled = true)).autostartOnBoot(),
            "флаги без тумблера -- поведение прежнее"
        )
        val gated = listOf(
            AppConfig(bootAutostart = true, vpn = VpnConfig(autoStart = true)),
            AppConfig(bootAutostart = true, zapret = ZapretConfig(enabled = true)),
            AppConfig(bootAutostart = true, goodbyeDpi = GoodbyeDpiConfig(autoStart = true)),
            AppConfig(bootAutostart = true, gps = GpsConfig(enabled = true))
        )
        gated.forEach { checkTrue(it.autostartOnBoot(), "тумблер + флаг") }
    }
}
