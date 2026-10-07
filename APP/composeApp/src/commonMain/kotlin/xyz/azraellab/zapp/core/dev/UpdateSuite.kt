package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.update.APP_VERSION
import xyz.azraellab.zapp.core.update.isNewerVersion

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
}
