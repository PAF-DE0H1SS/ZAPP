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
    return Regex("\\d+\\.\\d+(?:[-+][0-9A-Za-z]+)?").find(head)?.value
}

/**
 * Запускает компонент и читает его версию.
 *
 * Обе платформы -- JVM, поэтому actual один, в jvmShared. Таймаут 3 секунды:
 * любой честный `--version` отвечает мгновенно, зависший процесс
 * убивается и в карточке остаётся тире.
 */
expect suspend fun probeComponentVersion(component: ZappComponent): String?
