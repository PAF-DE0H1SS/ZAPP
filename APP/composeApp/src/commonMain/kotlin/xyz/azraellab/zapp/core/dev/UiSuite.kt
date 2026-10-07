package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.permissions.PermissionId
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.core.permissions.PermissionStatus
import xyz.azraellab.zapp.core.probe.Probe
import xyz.azraellab.zapp.core.resolveLang
import xyz.azraellab.zapp.ui.nav.AppTab
import xyz.azraellab.zapp.ui.nav.tabSlots

/**
 * Интерфейс: вкладки, язык, разрешения, метки.
 *
 * Всё, что решает, что пользователь видит на экране и в каком порядке,
 * без самого Compose: структура нижней панели, правила выбора языка,
 * полнота списка разрешений.
 */
internal fun uiTests(out: MutableList<DevTest>) {

    // --- Вкладки ---

    out += DevTest("ui/tab-tor-first", DevCategory.UI, "Tor -- первая вкладка") {
        checkEq(AppTab.TOR, AppTab.entries.first(), "первая вкладка")
        checkEq(AppTab.TOR, AppTab.first, "AppTab.first")
    }

    out += DevTest("ui/tab-core-present", DevCategory.UI, "Без GPS все базовые вкладки на месте") {
        val names = AppTab.available(gpsSupported = false).map { it.name }
        checkEq(listOf("TOR", "VPN", "ZAPRET", "GOODBYE_DPI", "SETTINGS"), names)
    }

    out += DevTest("ui/tab-gps-present", DevCategory.UI, "С GPS вкладка встаёт перед «Настройками»") {
        val names = AppTab.available(gpsSupported = true).map { it.name }
        checkEq(listOf("TOR", "VPN", "ZAPRET", "GOODBYE_DPI", "GPS", "SETTINGS"), names)
    }

    out += DevTest("ui/tab-gps-omitted-when-unsupported", DevCategory.UI, "Без GPS раздел отсутствует вовсе") {
        checkFalse(
            AppTab.available(gpsSupported = false).contains(AppTab.GPS),
            "GPS попала в список без поддержки"
        )
        checkTrue(AppTab.available(gpsSupported = true).contains(AppTab.GPS), "с поддержкой GPS должна быть")
    }

    out += DevTest("ui/tab-titles-unique", DevCategory.UI, "У вкладок разные ключи заголовков") {
        checkEq(
            AppTab.entries.size,
            AppTab.entries.map { it.title }.toSet().size,
            "дубликаты title"
        )
    }

    out += DevTest("ui/tab-icons-unique", DevCategory.UI, "У вкладок разные иконки") {
        checkEq(
            AppTab.entries.size,
            AppTab.entries.map { it.icon }.toSet().size,
            "дубликаты icon"
        )
    }

    out += DevTest("ui/tab-slots-pairs", DevCategory.UI, "Парные слоты нижней панели") {
        val slots = tabSlots(AppTab.available(gpsSupported = true)).map { it.map(AppTab::name) }
        checkEq(
            listOf(
                listOf("TOR", "VPN"),
                listOf("ZAPRET", "GOODBYE_DPI"),
                listOf("GPS"),
                listOf("SETTINGS")
            ),
            slots,
            "слоты с GPS"
        )
        val noGps = tabSlots(AppTab.available(gpsSupported = false)).map { it.map(AppTab::name) }
        checkEq(
            listOf(
                listOf("TOR", "VPN"),
                listOf("ZAPRET", "GOODBYE_DPI"),
                listOf("SETTINGS")
            ),
            noGps,
            "слоты без GPS"
        )
    }

    out += DevTest("ui/tab-slots-require-pair", DevCategory.UI, "Неполная пара не собирается") {
        val slots = tabSlots(listOf(AppTab.VPN, AppTab.ZAPRET, AppTab.SETTINGS))
            .map { it.map(AppTab::name) }
        checkEq(listOf(listOf("VPN"), listOf("ZAPRET"), listOf("SETTINGS")), slots)
    }

    out += DevTest("ui/tab-short-labels", DevCategory.UI, "Короткие метки вкладок не пустые") {
        AppTab.entries.forEach { tab ->
            val short = tab.short.of(AppLang.EN)
            checkTrue(short.isNotBlank(), "${tab.name}: пустая короткая метка")
        }
    }

    // --- Язык ---

    val ofCases = listOf<Pair<String, AppLang?>>(
        "ru" to AppLang.RU,
        "RU" to AppLang.RU,
        "ru-RU" to AppLang.RU,
        "ru_RU" to AppLang.RU,
        "  RU-rU " to AppLang.RU,
        "zh-Hans-CN" to AppLang.ZH,
        "en-GB" to AppLang.EN,
        // Языки СНГ в приложении не выбраны: AppLang знает только три языка,
        // а «СНГ -> русский» решает resolveLang, а не разбор кода.
        "be" to null,
        "kk-KZ" to null
    )
    ofCases.forEachIndexed { index, (code, expected) ->
        out += DevTest(
            // Индекс в id: "ru", "RU" и "ru_RU" дают одинаковую санитизацию.
            "ui/lang-of-$index-${code.lowercase().filter { it.isLetterOrDigit() }}",
            DevCategory.UI,
            "AppLang.of(\"$code\") == $expected"
        ) {
            checkEq(expected, AppLang.of(code), "разбор кода")
        }
    }
    listOf(null, "", "de", "solarized", "123").forEachIndexed { i, code ->
        out += DevTest("ui/lang-of-invalid-$i", DevCategory.UI, "AppLang.of($code) == null") {
            checkEq(null, AppLang.of(code), "неизвестный код")
        }
    }

    out += DevTest("ui/lang-cis-default-russian", DevCategory.UI, "Язык СНГ по умолчанию -- русский") {
        for (code in listOf(
            "ru", "be-BY", "uk-UA", "kk-KZ", "ky-KG", "tg", "tk-TM",
            "uz-UZ", "az-AZ", "hy-AM", "ka-GE", "ru-RU"
        )) {
            checkEq(AppLang.RU, resolveLang(null, code), "системный язык $code")
        }
    }

    out += DevTest("ui/lang-region-rules", DevCategory.UI, "Регион СНГ даёт русский даже с иностранным языком") {
        for (code in listOf("ro-MD", "de-KZ", "en-UA", "fr-BY", "tr-GE")) {
            checkEq(AppLang.RU, resolveLang(null, code), "системный язык $code")
        }
        // Румыния без региона -- не СНГ.
        checkEq(AppLang.EN, resolveLang(null, "ro"), "ro без региона")
        checkEq(AppLang.EN, resolveLang(null, "ro-RO"), "ro-RO")
    }

    out += DevTest("ui/lang-chinese-not-overridden-by-region", DevCategory.UI, "Китайский не перехватывается регионным правилом") {
        for (code in listOf("zh", "zh-CN", "zh-Hans-CN", "zh_TW")) {
            checkEq(AppLang.ZH, resolveLang(null, code), "системный язык $code")
        }
    }

    out += DevTest("ui/lang-explicit-choice-wins", DevCategory.UI, "Явный выбор пользователя главнее системы") {
        checkEq(AppLang.EN, resolveLang(AppLang.EN, "ru-RU"))
        checkEq(AppLang.ZH, resolveLang(AppLang.ZH, "en-US"))
        checkEq(AppLang.RU, resolveLang(AppLang.RU, "en-GB"))
    }

    out += DevTest("ui/lang-unknown-system-is-english", DevCategory.UI, "Неизвестный язык системы -- английский") {
        checkEq(AppLang.EN, resolveLang(null, null))
        checkEq(AppLang.EN, resolveLang(null, ""))
        checkEq(AppLang.EN, resolveLang(null, "tr-TR"))
    }

    // --- Разрешения ---

    out += DevTest("ui/permission-titles-present", DevCategory.UI, "У каждого разрешения есть ключ строки") {
        PermissionId.entries.forEach { id ->
            val title = id.title
            checkTrue(title.name.isNotBlank(), "$id без ключа")
            for (lang in AppLang.entries) {
                checkTrue(title.of(lang).isNotBlank(), "$id: пустой перевод на $lang")
            }
        }
    }

    out += DevTest("ui/permission-ids-unique", DevCategory.UI, "Идентификаторы разрешений уникальны") {
        checkEq(PermissionId.entries.size, PermissionId.entries.map { it.title }.toSet().size)
        checkEq(PermissionId.entries.size, PermissionId.entries.map { it.name }.toSet().size)
    }

    out += DevTest("ui/permission-granted-only-when-granted", DevCategory.UI, "granted -- только у выданного разрешения") {
        PermissionState.entries.forEach { state ->
            val granted = PermissionStatus(PermissionId.VPN, state).granted
            checkEq(state == PermissionState.GRANTED, granted, "state=$state")
        }
    }

    out += DevTest("ui/permission-states-cover-platforms", DevCategory.UI, "Состояния разрешений полны") {
        checkEq(
            listOf("GRANTED", "DENIED", "SETTINGS", "UNSUPPORTED"),
            PermissionState.entries.map { it.name },
            "список состояний"
        )
    }

    // --- Метки разведки ---

    out += DevTest("ui/probe-labels-translated", DevCategory.UI, "Метки разведки переведены") {
        Probe.entries.forEach { probe ->
            for (lang in AppLang.entries) {
                checkTrue(probe.label.of(lang).isNotBlank(), "$probe: пустая метка на $lang")
            }
        }
        checkEq(
            Probe.entries.size,
            Probe.entries.map { it.label }.toSet().size,
            "у состояний разные метки"
        )
    }
}
