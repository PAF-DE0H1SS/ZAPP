package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.ConfigCodec
import xyz.azraellab.zapp.core.update.APP_VERSION
import xyz.azraellab.zapp.core.update.UPDATE_COMPARE_SEMVER
import xyz.azraellab.zapp.core.update.UPDATE_COMPARE_TAG
import xyz.azraellab.zapp.core.update.ZappComponent
import xyz.azraellab.zapp.core.update.isNewerVersion
import xyz.azraellab.zapp.core.update.localDayEndMs
import xyz.azraellab.zapp.core.update.parseComponentVersion
import xyz.azraellab.zapp.core.update.updateAvailable

/**
 * Сравнение версий: одна строка лога на каждый случай.
 *
 * Таблица, а не несколько больших проверок: после провала видно, какой
 * именно переход сломался (например, `0.10.0 > 0.9.0` -- тот самый случай,
 * где строковое сравнение врёт).
 */
private data class VersionCase(
    val candidate: String,
    val current: String,
    val newer: Boolean
)

private val VERSION_CASES = listOf(
    VersionCase("0.10.0", "0.9.0", true),
    VersionCase("1.0.0", "0.99.99", true),
    VersionCase("2.0", "1.999", true),
    VersionCase("0.1.1", "0.1.0", true),
    VersionCase("v0.2.0", "0.1.0", true),
    VersionCase("0.2.0", "v0.1.0", true),
    VersionCase("1.0.0.1", "1.0.0", true),
    VersionCase("0.1.0", "0.1.0", false),
    VersionCase("v0.1.0", "0.1.0", false),
    VersionCase("0.1.0", "0.2.0", false),
    VersionCase("0.0.9", "0.1", false),
    VersionCase("1.0.0", "1.0.0.1", false),
    VersionCase("", "0.1.0", false),
    VersionCase("0.1.0", "", false),
    VersionCase("0.1.0-rc.1", "0.1.0", false),
    VersionCase("0.1.0", "0.1.0-rc.1", true),
    VersionCase("0.1.1-rc.1", "0.1.0", true),
    VersionCase("  0.3.0  ", "0.2.0", true),
    VersionCase("V0.3.0", "0.2.0", true),
    VersionCase("0.1.0+build5", "0.1.0", false),
    VersionCase("2.0.0", "1.0.0-rc.9", true)
)

internal fun updateTests(out: MutableList<DevTest>) {
    VERSION_CASES.forEach { c ->
        out += DevTest(
            id = "update/is-newer/${c.candidate.ifEmpty { "empty" }}-vs-${c.current.ifEmpty { "empty" }}",
            category = DevCategory.UPDATE,
            name = "isNewerVersion(\"${c.candidate}\", \"${c.current}\") == ${c.newer}"
        ) {
            checkEq(c.newer, isNewerVersion(c.candidate, c.current), "сравнение версий")
        }
    }

    out += DevTest(
        id = "update/app-version-format",
        category = DevCategory.UPDATE,
        name = "APP_VERSION -- три числовые части"
    ) {
        val parts = APP_VERSION.removePrefix("v").split(".")
        checkEq(3, parts.size, "частей версии")
        parts.forEachIndexed { i, p ->
            checkTrue(p.toIntOrNull() != null, "часть $i не число: $p")
        }
    }

    out += DevTest(
        id = "update/compare-is-symmetric-inverse",
        category = DevCategory.UPDATE,
        name = "Сравнение антисимметрично: если A новее B, то B не новее A"
    ) {
        for (c in VERSION_CASES) {
            if (c.newer && c.candidate.isNotBlank() && c.current.isNotBlank()) {
                checkFalse(
                    isNewerVersion(c.current, c.candidate),
                    "обратное сравнение для ${c.candidate} / ${c.current}"
                )
            }
        }
    }

    out += DevTest(
        id = "update/same-version-never-newer",
        category = DevCategory.UPDATE,
        name = "Версия никогда не новее самой себя"
    ) {
        for (v in listOf("0.0.1", "1.2.3", "10.20.30", "v5.6.7")) {
            checkFalse(isNewerVersion(v, v), "сама на себя: $v")
        }
    }

    out += DevTest(
        id = "update/prerelease-order",
        category = DevCategory.UPDATE,
        name = "Пререлиз старше своего релиза, но новее -- только релиз"
    ) {
        // rc не обгоняет релиз...
        checkFalse(isNewerVersion("1.0.0-rc.2", "1.0.0"), "rc vs release")
        // ...а релиз -- пререлиз обновление.
        checkTrue(isNewerVersion("1.0.0", "1.0.0-rc.2"), "release vs rc")
        // Разные пререлизы одной версии не считаются обновлением.
        checkFalse(isNewerVersion("1.0.0-rc.3", "1.0.0-rc.2"), "rc3 vs rc2")
    }

    out += DevTest(
        id = "update/compare-modes",
        category = DevCategory.UPDATE,
        name = "Режимы сравнения: по номеру и по смене тега"
    ) {
        // semver -- обычное «новее ли».
        checkTrue(updateAvailable("0.2.0", "0.1.0", UPDATE_COMPARE_SEMVER), "semver новее")
        checkFalse(updateAvailable("0.1.0", "0.1.0", UPDATE_COMPARE_SEMVER), "semver равны")
        // tag -- любое отличие, в обе стороны: и `beta-next`, и откат
        // с пререлиза на релиз той же линии.
        checkTrue(updateAvailable("next", "0.1.0", UPDATE_COMPARE_TAG), "нечисловой тег")
        checkTrue(updateAvailable("0.1.0", "0.1.0-rc.1", UPDATE_COMPARE_TAG), "тег отличается")
        checkFalse(updateAvailable("v0.1.0", "0.1.0", UPDATE_COMPARE_TAG), "тег совпал после v")
        checkFalse(updateAvailable("V0.1.0", "0.1.0", UPDATE_COMPARE_TAG), "тег совпал после V")
        checkFalse(updateAvailable("", "0.1.0", UPDATE_COMPARE_TAG), "пустой тег не обновление")
        checkFalse(updateAvailable("0.1.0", "", UPDATE_COMPARE_TAG), "пустая установка -- не сравнение")
        // Неизвестный режим (конфиг прошлой версии) работает как semver.
        checkTrue(updateAvailable("0.2.0", "0.1.0", "whatever"), "неизвестный режим -- semver")
    }

    out += DevTest(
        id = "update/snooze-day-end",
        category = DevCategory.UPDATE,
        name = "Откладывание упирается в ближайшую местную полночь"
    ) {
        val now = System.currentTimeMillis()
        val end = localDayEndMs(now)
        checkTrue(end > now, "конец суток в будущем")
        // Сутки с переводом часов бывают 25-часовыми -- граница с запасом
        // на fall-back, но не дальше следующей ночи.
        checkTrue(end - now <= 90_000_000L, "не дальше чем через сутки")
        // Сам момент -- полночь по часовому поясу устройства: в DST-дни
        // сутки бывают 23 или 25 часов, поэтому проверяем именно
        // выровненность по местному времени, а не прибавку ровно за сутки.
        val offset = java.util.TimeZone.getDefault().getOffset(end)
        checkEq(0L, (end + offset) % 86_400_000L, "конец -- местная полночь")
        // Из самой полуночи сутки сдвигаются вперёд, а не назад.
        checkTrue(localDayEndMs(end) > end, "из полуночи -- дальше")
    }

    out += DevTest(
        id = "update/component-version-parse",
        category = DevCategory.UPDATE,
        name = "Версии компонентов из вывода бинарей"
    ) {
        checkEq(
            "1.260910.0",
            parseComponentVersion(ZappComponent.BOX, """{"version": "1.260910.0", "listens": []}"""),
            "box -- JSON"
        )
        checkEq(
            "0.4.9.14",
            parseComponentVersion(ZappComponent.TOR, "Tor version 0.4.9.14 (git-deadbeef)\ncompiled"),
            "tor -- строка"
        )
        checkEq(
            "1.22.09.1",
            parseComponentVersion(ZappComponent.NFQWS, "nfqws 1.22.09.1\nusage follows\n"),
            "nfqws -- четыре части"
        )
        checkEq(
            "2.7.7",
            parseComponentVersion(ZappComponent.OPENVPN, "OpenVPN 2.7.7 [SSL] \n"),
            "openvpn -- три части"
        )
        checkEq(
            "1.0.20220331",
            parseComponentVersion(ZappComponent.WIREGUARD, "wireguard-tools v1.0.20220331 (x86_64)\n"),
            "wg -- дата как версия"
        )
        checkTrue(parseComponentVersion(ZappComponent.BOX, "") == null, "пустой вывод -- null")
        checkEq(
            "devel",
            parseComponentVersion(ZappComponent.LYREBIRD, "lyrebird devel\n"),
            "lyrebird -- версии нет, слово после имени бинаря"
        )
        checkTrue(
            parseComponentVersion(ZappComponent.BOX, "cannot execute: file not found") == null,
            "без версии -- null"
        )
        checkTrue(
            parseComponentVersion(ZappComponent.BOX, "\n\n\n\n\n1.2.3") == null,
            "версия только в хвосте -- не видим"
        )
    }

    out += DevTest(
        id = "update/config-fields",
        category = DevCategory.UPDATE,
        name = "Поля обновлений в конфиге: дефолт и JSON"
    ) {
        val defaults = AppConfig()
        checkEq(UPDATE_COMPARE_SEMVER, defaults.updateCompareMode, "режим по умолчанию")
        checkEq(0L, defaults.updateSnoozeUntilMs, "откладки по умолчанию нет")

        val tuned = defaults.copy(updateCompareMode = UPDATE_COMPARE_TAG, updateSnoozeUntilMs = 123_456L)
        val decoded = ConfigCodec.decodeConfig(ConfigCodec.encodeConfig(tuned))
        checkEq(UPDATE_COMPARE_TAG, decoded.updateCompareMode, "режим пережил JSON")
        checkEq(123_456L, decoded.updateSnoozeUntilMs, "откладка пережила JSON")

        // Старый конфиг без новых полей читается с дефолтами.
        val legacy = ConfigCodec.decodeConfig("""{"welcomeDone": true}""")
        checkEq(UPDATE_COMPARE_SEMVER, legacy.updateCompareMode, "режим из старого конфига")
        checkEq(0L, legacy.updateSnoozeUntilMs, "откладки в старом конфиге нет")
    }
}
