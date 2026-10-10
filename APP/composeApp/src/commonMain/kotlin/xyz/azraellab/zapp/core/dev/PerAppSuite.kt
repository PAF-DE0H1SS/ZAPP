package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.ConfigCodec
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnRouting
import xyz.azraellab.zapp.core.daemon.dpiAppFilter
import xyz.azraellab.zapp.core.daemon.dpiFirewallScript
import xyz.azraellab.zapp.core.engine.vpnTunnelParams
import xyz.azraellab.zapp.core.perApp.AppFamily
import xyz.azraellab.zapp.core.perApp.AppSelection
import xyz.azraellab.zapp.core.perApp.InstalledApp
import xyz.azraellab.zapp.core.perApp.PerAppConfig
import xyz.azraellab.zapp.core.perApp.RouteMode
import xyz.azraellab.zapp.ui.pages.PerAppScope

/**
 * Per-app выбор: модель, семейства и проброс в параметры туннеля.
 *
 * Главные свойства: выбор по функции независим (VPN != Tor), семья
 * тянет родственников (uid-соседи на Android, ветка процессов на ПК),
 * а пустой выбор включённого режима не означает «ничего не выбрано» --
 * это отдельное состояние, которое пикер показывает честно.
 */
internal fun perAppTests(out: MutableList<DevTest>) {

    // --- Дефолт и санитайзер ---

    out += DevTest("perapp/clean-default", DevCategory.VPN, "Чистый конфиг: выбор выключен") {
        val cfg = AppConfig()
        checkFalse(cfg.perApp.vpn.enabled, "vpn выключен по умолчанию")
        checkFalse(cfg.perApp.tor.enabled, "tor выключен по умолчанию")
        checkFalse(cfg.perApp.zapret.enabled, "zapret выключен по умолчанию")
        checkFalse(cfg.perApp.goodbyeDpi.enabled, "goodbyeDpi выключен по умолчанию")
        checkFalse(cfg.perApp.showSystemApps, "системные приложения скрыты по умолчанию")
    }

    out += DevTest("perapp/sanitize-drops-blanks", DevCategory.VPN, "Санитайзер чистит пустые и дубли") {
        val dirty = AppSelection(enabled = true, packages = listOf("a", "", "  ", "b", "a"))
        checkEq(listOf("a", "b"), dirty.sanitized().packages, "пустое и дубль уходят")
    }

    out += DevTest("perapp/sanitize-keeps-order", DevCategory.VPN, "Санитайзер сохраняет порядок выбора") {
        val sel = AppSelection(packages = listOf("z", "a", "m"))
        checkEq(listOf("z", "a", "m"), sel.sanitized().packages, "порядок как в выборе")
    }

    out += DevTest("perapp/sanitize-all-four", DevCategory.VPN, "Санитайзер PerAppConfig чистит все четыре функции") {
        val cfg = PerAppConfig(
            vpn = AppSelection(packages = listOf("a", "")),
            tor = AppSelection(packages = listOf("", "b")),
            zapret = AppSelection(packages = listOf("c", " ")),
            goodbyeDpi = AppSelection(packages = listOf("", "d"))
        ).sanitized()
        checkEq(listOf("a"), cfg.vpn.packages, "vpn")
        checkEq(listOf("b"), cfg.tor.packages, "tor")
        checkEq(listOf("c"), cfg.zapret.packages, "zapret")
        checkEq(listOf("d"), cfg.goodbyeDpi.packages, "goodbyeDpi")
    }

    // --- Семейства: uid-соседи и ветки процессов ---

    out += DevTest("perapp/expand-family", DevCategory.VPN, "Выбор тянет семью по familyId") {
        val catalog = listOf(
            InstalledApp("com.a", "A", uid = 1001, isSystem = false, familyId = 7),
            InstalledApp("com.a.helper", "A helper", uid = 1002, isSystem = false, familyId = 7),
            InstalledApp("com.b", "B", uid = 1003, isSystem = false, familyId = 9)
        )
        val expanded = AppFamily.expand(listOf("com.a"), catalog)
        checkIn("com.a.helper", expanded, "родственник входит в выбор")
        checkFalse("com.b" in expanded, "чужая семья не тянется")
    }

    out += DevTest("perapp/expand-uid-fallback", DevCategory.VPN, "Без familyId семья берётся по uid") {
        val catalog = listOf(
            InstalledApp("com.a", "A", uid = 1001, isSystem = false),
            InstalledApp("com.a.share", "A share", uid = 1001, isSystem = false),
            InstalledApp("com.b", "B", uid = 1002, isSystem = false)
        )
        val expanded = AppFamily.expand(listOf("com.a"), catalog)
        checkIn("com.a.share", expanded, "uid-сосед входит")
        checkFalse("com.b" in expanded, "другой uid не входит")
    }

    out += DevTest("perapp/expand-unknown-stays", DevCategory.VPN, "Пакет вне каталога остаётся в выборе") {
        val expanded = AppFamily.expand(listOf("com.gone"), listOf())
        checkEq(listOf("com.gone"), expanded, "выбор пользователя не теряется")
    }

    out += DevTest("perapp/expand-idempotent", DevCategory.VPN, "Расширение идемпотентно") {
        val catalog = listOf(
            InstalledApp("com.a", "A", uid = 1, isSystem = false, familyId = 5),
            InstalledApp("com.a.b", "B", uid = 2, isSystem = false, familyId = 5)
        )
        val once = AppFamily.expand(listOf("com.a"), catalog)
        val twice = AppFamily.expand(once, catalog)
        checkEq(once, twice, "повторный проход ничего не добавляет")
    }

    out += DevTest("perapp/expand-tree", DevCategory.VPN, "Ветка дерева процессов выбирается целиком") {
        // Дерево на десктопе: корень vlc, дети -- его воркеры одной ветки.
        val catalog = listOf(
            InstalledApp("pid:10", "vlc", uid = 1000, isSystem = false, depth = 0, familyId = 10),
            InstalledApp("pid:11", "vlc", uid = 1000, isSystem = false, depth = 1, familyId = 10),
            InstalledApp("pid:12", "vlc-cached", uid = 1000, isSystem = false, depth = 2, familyId = 10),
            InstalledApp("pid:99", "ssh", uid = 1000, isSystem = false, depth = 0, familyId = 99)
        )
        val expanded = AppFamily.expand(listOf("pid:11"), catalog)
        checkEq(listOf("pid:10", "pid:11", "pid:12"), expanded.sorted(), "вся ветка vlc")
        checkFalse("pid:99" in expanded, "чужой корень не тянется")
    }

    out += DevTest("perapp/uids-are-real-owners", DevCategory.VPN, "uids() возвращает uid владельца, не семью") {
        val catalog = listOf(
            InstalledApp("com.a", "A", uid = 1001, isSystem = false, familyId = 7),
            InstalledApp("com.a.h", "H", uid = 1002, isSystem = false, familyId = 7)
        )
        checkEq(listOf(1001), AppFamily.uids(listOf("com.a"), catalog), "uid конкретного пакета")
        checkEq(listOf(1001, 1002), AppFamily.uids(listOf("com.a", "com.a.h"), catalog), "оба владельца")
    }

    out += DevTest("perapp/uids-skip-unknown", DevCategory.VPN, "Пакет вне каталога не даёт uid") {
        checkEq(listOf<Int>(), AppFamily.uids(listOf("com.gone"), listOf()), "пусто, а не мусор")
    }

    // --- Независимость выборов по функции ---

    out += DevTest("perapp/functions-independent", DevCategory.VPN, "Выбор VPN не трогает остальные функции") {
        val cfg = AppConfig(
            perApp = PerAppConfig(vpn = AppSelection(enabled = true, packages = listOf("com.x")))
        )
        checkEq(listOf("com.x"), cfg.perApp.vpn.packages, "vpn получил выбор")
        checkFalse(cfg.perApp.tor.enabled, "tor не тронут")
        checkTrue(cfg.perApp.zapret.isEmpty(), "zapret пуст")
        checkTrue(cfg.perApp.goodbyeDpi.isEmpty(), "goodbyeDpi пуст")
    }

    out += DevTest("perapp/empty-while-enabled", DevCategory.VPN, "Включенный режим с пустым списком корректен") {
        val sel = AppSelection(enabled = true, packages = emptyList())
        checkTrue(sel.isEmpty(), "список пуст")
        checkTrue(sel.enabled, "но режим включён")
    }

    // --- Проброс в параметры туннеля ---

    out += DevTest("perapp/tunnel-params-carry-allowed", DevCategory.VPN, "Параметры туннеля несут allow-список") {
        val json = """{"inbounds":[{"type":"tun","address":["172.19.0.1/30"],"mtu":1500}]}"""
        val params = vpnTunnelParams(json, VpnConfig(), "test", listOf("com.a", "com.b"))
        checkTrue(params != null, "tun-инбаунд разобран")
        checkEq(listOf("com.a", "com.b"), params?.allowedApps, "allow-список дошёл до платформы")
    }

    out += DevTest("perapp/tunnel-params-empty-by-default", DevCategory.VPN, "Без выбора allow-список пуст") {
        val json = """{"inbounds":[{"type":"tun","address":["172.19.0.1/30"]}]}"""
        val params = vpnTunnelParams(json, VpnConfig(), "test")
        checkEq(emptyList<String>(), params?.allowedApps, "пусто -- весь трафик через туннель")
    }

    // --- Scope пикера: четыре функции, четыре поля ---

    out += DevTest("perapp/scope-roundtrip", DevCategory.VPN, "Каждый scope пишет своё поле") {
        val cfg = PerAppConfig()
        for (scope in PerAppScope.entries) {
            val sel = AppSelection(enabled = true, packages = listOf("p.${scope.name}"))
            val written = scope.write(cfg, sel)
            checkEq(sel, scope.read(written), "write->read не теряет выбор: ${scope.name}")
        }
    }

    out += DevTest("perapp/scope-write-isolation", DevCategory.VPN, "Запись одного scope не трогает другие") {
        val cfg = PerAppConfig()
        val vpn = PerAppScope.VPN.write(cfg, AppSelection(enabled = true, packages = listOf("v")))
        val tor = PerAppScope.TOR.write(vpn, AppSelection(enabled = true, packages = listOf("t")))
        checkEq(listOf("v"), PerAppScope.VPN.read(tor).packages, "vpn выбор на месте")
        checkEq(listOf("t"), PerAppScope.TOR.read(tor).packages, "tor выбор записан")
        checkTrue(PerAppScope.ZAPRET.read(tor).isEmpty(), "zapret не тронут")
        checkTrue(PerAppScope.GDPI.read(tor).isEmpty(), "goodbyeDpi не тронут")
    }

    out += DevTest("perapp/scope-flags", DevCategory.VPN, "DPI-скоупы требуют root, туннельные -- переподключения") {
        checkFalse(PerAppScope.VPN.requiresRoot(), "vpn без root не требует")
        checkFalse(PerAppScope.TOR.requiresRoot(), "tor без root не требует")
        checkTrue(PerAppScope.ZAPRET.requiresRoot(), "zapret -- root")
        checkTrue(PerAppScope.GDPI.requiresRoot(), "goodbyeDpi -- root")
        checkTrue(PerAppScope.VPN.isTunnel(), "vpn -- туннель")
        checkTrue(PerAppScope.TOR.isTunnel(), "tor -- туннель")
        checkFalse(PerAppScope.ZAPRET.isTunnel(), "zapret -- демон")
        checkFalse(PerAppScope.GDPI.isTunnel(), "goodbyeDpi -- демон")
    }

    // --- Режим: только выбранные или все кроме выбранных ---

    out += DevTest("perapp/mode-default-include", DevCategory.VPN, "Режим по умолчанию -- только выбранные") {
        checkEq(RouteMode.INCLUDE, AppSelection().mode, "дефолт include")
        checkEq(RouteMode.INCLUDE, AppConfig().perApp.vpn.mode, "в конфиге тоже include")
    }

    out += DevTest("perapp/mode-survives-json", DevCategory.VPN, "Режим переживает сериализацию конфига") {
        val sel = AppSelection(enabled = true, mode = RouteMode.EXCLUDE, packages = listOf("com.x"))
        val encoded = ConfigCodec.encodeConfig(AppConfig(perApp = PerAppConfig(vpn = sel)))
        val back = ConfigCodec.decodeConfig(encoded)
        checkEq(RouteMode.EXCLUDE, back.perApp.vpn.mode, "exclude доехал")
        checkEq(listOf("com.x"), back.perApp.vpn.packages, "список доехал")
    }

    out += DevTest("perapp/mode-not-required-for-active", DevCategory.VPN, "active() зависит от enabled и списка, не от режима") {
        checkTrue(AppSelection(enabled = true, mode = RouteMode.EXCLUDE, packages = listOf("a")).active(), "exclude активен")
        checkTrue(AppSelection(enabled = true, mode = RouteMode.INCLUDE, packages = listOf("a")).active(), "include активен")
        checkFalse(AppSelection(enabled = true, mode = RouteMode.EXCLUDE, packages = emptyList()).active(), "пустой список не активен")
        checkFalse(AppSelection(mode = RouteMode.EXCLUDE, packages = listOf("a")).active(), "выключенный не активен")
    }

    out += DevTest("perapp/sanitize-keeps-mode", DevCategory.VPN, "Санитайзер не трогает режим") {
        val sel = AppSelection(enabled = true, mode = RouteMode.EXCLUDE, packages = listOf("a", "a", ""))
        checkEq(RouteMode.EXCLUDE, sel.sanitized().mode, "режим на месте")
        checkEq(listOf("a"), sel.sanitized().packages, "список почищен")
    }

    out += DevTest("perapp/exclude-tunnel-params", DevCategory.VPN, "Exclude-список уходит в bypassApps туннеля") {
        val json = """{"inbounds":[{"type":"tun","address":["172.19.0.1/30"]}]}"""
        val params = vpnTunnelParams(json, VpnConfig(), "test", emptyList(), listOf("com.x"))
        checkEq(emptyList<String>(), params?.allowedApps, "allow-список пуст -- режим disallowed")
        checkIn("com.x", params?.bypassApps ?: emptyList<String>(), "исключённый пакет ушёл мимо туннеля")
    }

    out += DevTest("perapp/exclude-merges-with-routing-bypass", DevCategory.VPN, "Exclude объединяется с bypass из настроек профиля") {
        val json = """{"inbounds":[{"type":"tun","address":["172.19.0.1/30"]}]}"""
        val vpn = VpnConfig(
            routing = VpnRouting(
                allowPerAppBypass = true,
                bypassApps = "com.routing"
            )
        )
        val params = vpnTunnelParams(json, vpn, "test", emptyList(), listOf("com.perapp"))
        val bypass = params?.bypassApps ?: emptyList()
        checkIn("com.routing", bypass, "bypass профиля на месте")
        checkIn("com.perapp", bypass, "exclude выбора на месте")
    }

    // --- Фильтр NFQUEUE: owners vs excludes ---

    out += DevTest("perapp/dpi-filter-include", DevCategory.VPN, "Include-режим даёт owners, excludes пуст") {
        val catalog = listOf(InstalledApp("com.a", "A", uid = 1001, isSystem = false))
        val filter = dpiAppFilter(
            listOf(AppSelection(enabled = true, mode = RouteMode.INCLUDE, packages = listOf("com.a"))),
            catalog
        )
        checkEq(listOf(1001), filter.owners, "владелец в очереди")
        checkEq(emptyList<Int>(), filter.excludes, "исключений нет")
    }

    out += DevTest("perapp/dpi-filter-exclude", DevCategory.VPN, "Exclude-режим даёт excludes, owners пуст") {
        val catalog = listOf(InstalledApp("com.a", "A", uid = 1001, isSystem = false))
        val filter = dpiAppFilter(
            listOf(AppSelection(enabled = true, mode = RouteMode.EXCLUDE, packages = listOf("com.a"))),
            catalog
        )
        checkEq(emptyList<Int>(), filter.owners, "очередь без owner-фильтра")
        checkEq(listOf(1001), filter.excludes, "владелец в исключениях")
    }

    out += DevTest("perapp/dpi-filter-mixed-modes-drop", DevCategory.VPN, "Смешанные режимы двух демонов снимают фильтр") {
        val catalog = listOf(
            InstalledApp("com.a", "A", uid = 1001, isSystem = false),
            InstalledApp("com.b", "B", uid = 1002, isSystem = false)
        )
        val filter = dpiAppFilter(
            listOf(
                AppSelection(enabled = true, mode = RouteMode.INCLUDE, packages = listOf("com.a")),
                AppSelection(enabled = true, mode = RouteMode.EXCLUDE, packages = listOf("com.b"))
            ),
            catalog
        )
        checkTrue(filter.unfiltered, "одной очередью это не выражается")
    }

    out += DevTest("perapp/dpi-filter-inactive-selection", DevCategory.VPN, "Выключенный выбор снимает фильтр") {
        val catalog = listOf(InstalledApp("com.a", "A", uid = 1001, isSystem = false))
        val off = dpiAppFilter(listOf(AppSelection(mode = RouteMode.EXCLUDE, packages = listOf("com.a"))), catalog)
        checkTrue(off.unfiltered, "enabled=false -- без фильтра")
        val empty = dpiAppFilter(
            listOf(AppSelection(enabled = true, mode = RouteMode.EXCLUDE, packages = emptyList())),
            catalog
        )
        checkTrue(empty.unfiltered, "пустой список -- без фильтра")
    }

    out += DevTest("perapp/dpi-filter-empty-daemons", DevCategory.VPN, "Пустой список выборов -- без фильтра") {
        checkTrue(dpiAppFilter(emptyList(), emptyList()).unfiltered, "демонов нет -- фильтра нет")
    }

    // --- Текст iptables-скрипта ---

    out += DevTest("perapp/dpi-script-excludes-before-queue", DevCategory.VPN, "Исключения возвращаются раньше NFQUEUE") {
        val script = dpiFirewallScript(tcpPorts = listOf("443"), udpPorts = listOf("443"), owners = emptyList(), excludes = listOf(1001))
        val ret = script.indexOf("--uid-owner 1001 -j RETURN")
        val queue = script.indexOf("-j NFQUEUE")
        checkTrue(ret >= 0, "RETURN-правило исключения есть")
        checkTrue(queue >= 0, "NFQUEUE есть")
        checkTrue(ret < queue, "исключение строго до очереди")
    }

    out += DevTest("perapp/dpi-script-owner-split", DevCategory.VPN, "Owner-фильтр дублирует портовое правило на каждый uid") {
        val script = dpiFirewallScript(listOf("443"), emptyList(), owners = listOf(1002, 1001), excludes = emptyList())
        val first = script.indexOf("--uid-owner 1001")
        val second = script.indexOf("--uid-owner 1002")
        checkTrue(first >= 0, "uid 1001 в правиле")
        checkTrue(second >= 0, "uid 1002 в правиле")
        checkTrue(first < second, "uid отсортированы для детерминизма")
    }

    out += DevTest("perapp/dpi-script-unfiltered-queue", DevCategory.VPN, "Без owners очередь идёт без owner-match") {
        val script = dpiFirewallScript(emptyList(), listOf("53"), owners = emptyList(), excludes = emptyList())
        checkContains(script, "-p udp --dport 53 -j NFQUEUE", "udp-порт без owner-фильтра")
        checkNotContains(script, "--uid-owner", "правил владельцев нет")
    }

    out += DevTest("perapp/dpi-script-bad-ports-dropped", DevCategory.VPN, "Не-порты молча выпадают из скрипта") {
        val script = dpiFirewallScript(listOf("abc", "0:80", "443"), emptyList(), emptyList(), emptyList())
        checkContains(script, "--dport 443", "валидный порт на месте")
        checkNotContains(script, "--dport abc", "мусор отброшен")
        checkNotContains(script, "--dport 0:80", "нулевой диапазон отброшен")
    }

    out += DevTest("perapp/dpi-script-port-ranges", DevCategory.VPN, "Диапазон: дефис конфига -> двоеточие iptables") {
        // nfqws диапазон пишет через дефис (27015-27020), iptables --
        // через двоеточие. Один токен должен угодить обоим.
        val script = dpiFirewallScript(listOf("27015-27020"), listOf("5223-5230"), emptyList(), emptyList())
        checkContains(script, "-p tcp --dport 27015:27020 -j NFQUEUE", "tcp-диапазон нормализован")
        checkContains(script, "-p udp --dport 5223:5230 -j NFQUEUE", "udp-диапазон нормализован")
        checkNotContains(script, "27015-27020", "дефисная форма в iptables не попала")
    }
}
