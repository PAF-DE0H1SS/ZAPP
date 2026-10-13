package xyz.azraellab.zapp.core.update

/**
 * Встроенные бинари -- что реально положено в APK.
 *
 * Версия нужна не для сравнения (версии компонентов идут своим ходом),
 * а для диагностики: «box не работает» на фоне box 1.22 и на фоне
 * 1.260910 -- две разные истории. Список ровно совпадает с TOOLS в
 * native/pack.sh.
 */
enum class ZappComponent(val binary: String, val args: List<String>) {
    BOX("box", listOf("version")),
    TOR("tor", listOf("--version")),
    NFQWS("nfqws", listOf("--version")),
    GOODBYEDPI("goodbyedpi", listOf("--version")),
    WIREGUARD("wg", listOf("--version")),
    OPENVPN("openvpn", listOf("--version")),
    LYREBIRD("lyrebird", listOf("-version"));

    /** Отображаемое имя -- имя файла бинаря, оно же в `box --version`. */
    val title: String get() = binary
}

/**
 * Первая версия из вывода компонента, null -- прочитать не удалось.
 *
 * box отвечает JSON с `"version": "..."`, остальные -- строкой вида
 * `Tor version 0.4.9.14 (git-...)`. Ищем версию в первых строках:
 * это отсекает даты и размеры из развёрнутого usage, который часть
 * компонентов печатает вместе с версией.
 */
fun parseComponentVersion(component: ZappComponent, output: String): String? {
    if (output.isBlank()) return null
    if (component == ZappComponent.BOX) {
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(output)
            ?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    val head = output.lineSequence().take(5).joinToString(" ")
    // Семвер из трёх и более частей (zapret ходит с четвёртой: 1.22.09.1),
    // с редким постфиксом 1.2.3-rc1 / 2.7.7-2ubuntu...
    val full = Regex("\\d+(?:\\.\\d+){2,}(?:[-+][0-9A-Za-z.]+)?").find(head)?.value
    if (full != null) return full
    // Компоненты вида 0.4.9 (короткий хвост) или 1.0.20220331 (wg).
    val short = Regex("\\d+\\.\\d+(?:[-+][0-9A-Za-z]+)?").find(head)?.value
    if (short != null) return short
    // Версии нет вовсе, есть дата сборки: nfqws печатает
    // `self-built android version Oct  3 2026 03:28:49`, amnezia-wg --
    // `amneziawg-tools vawg-20260812`. Дата читается однозначно и по ней
    // видно, свежий ли бинарь стоит -- для диагностики это главное.
    val packed = Regex("\\b(20\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})\\b").find(head)
    if (packed != null) {
        val g = packed.groupValues
        return "${g[1]}-${g[2]}-${g[3]}"
    }
    val dated = Regex("\\b([A-Z][a-z]{2})\\s+(\\d{1,2})\\s+(20\\d{2})\\b").find(head)
    if (dated != null) {
        val g = dated.groupValues
        val month = MONTHS[g[1].lowercase()]
        if (month != null) return "${g[3]}-$month-${g[2].padStart(2, '0')}"
    }
    // Ни версии, ни даты. Хвост берём только когда строка вида
    // `<имя бинаря> <слово>`: lyrebird отвечает просто `lyrebird devel`,
    // и без подсказки в карточке нечем показать. Словесный вывод ошибки
    // (`cannot execute: file not found`) мы обязаны отдать как null -- иначе
    // на месте версии появится «execute:».
    val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
    val parts = line?.split(Regex("\\s+")) ?: return null
    return parts.getOrNull(1)
        ?.takeIf { parts[0].equals(component.binary, ignoreCase = true) && it.isNotBlank() }
}

/** Английские сокращения месяцев, как их пишут бинари из мира tor/nfqws. */
private val MONTHS = mapOf(
    "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
    "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
    "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12"
)

/**
 * Запускает компонент и читает его версию.
 *
 * Обе платформы -- JVM, поэтому actual один, в jvmShared. Таймаут 3 секунды:
 * любой честный `--version` отвечает мгновенно, зависший процесс
 * убивается и в карточке остаётся тире.
 */
expect suspend fun probeComponentVersion(component: ZappComponent): String?
