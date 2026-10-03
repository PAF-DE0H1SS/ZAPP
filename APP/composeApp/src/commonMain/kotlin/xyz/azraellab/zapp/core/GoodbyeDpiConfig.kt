package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable

/**
 * Настройки GoodbyeDPI.
 *
 * Отличие от Zapret принципиальное: Zapret работает с пакетами, а GoodbyeDPI --
 * в первом рукопожатии, меняя метку у TLS ClientHello. Поэтому здесь нет ручек
 * для TTL, портов и desync, зато есть то, что относится к самому рукопожатию.
 *
 * Все поля переводятся в настоящие опции `src/goodbyedpi.c`: короткие -- в
 * режимы `-1..-9`, длинные -- в свои флаги. Режимы, у которых нет реального
 * аналога, в конфиге не существуют: показывать ручку, не влияющую на команду,
 * -- то же враньё, что и лишний флаг в команде.
 */
@Serializable
data class GoodbyeDpiConfig(
    /** Режим из [GoodbyeDpiMode]. */
    val mode: String = GoodbyeDpiMode.AUTO.code,

    // --- Где резать ---
    /** Позиция разреза в ClientHello: `-f` и `-e` режима по позиции. */
    val splitPos: String = "2",

    // --- Как выглядит подмена ---
    /** Подменять порядковый номер: `--wrong-seq`. */
    val fakeSeq: Int? = null,
    /** Подменять контрольную сумму: `--wrong-chksum`. */
    val fakeCsum: Boolean = false,
    /** SNI для `--fake-with-sni` режима Fake SNI; без него режим не стартует. */
    val fakeSni: String = "",

    // --- Что пропускать ---
    /** Проверять домен перед применением правила; выкл -- `--allow-no-sni`. */
    val hostCheck: Boolean = true,

    // --- Область действия ---
    /** Домены через запятую: пишутся в файл для `--blacklist`. */
    val domains: String = "",

    // --- Права и запуск ---
    /** Путь к демону; пусто -- искать `goodbyedpi` в PATH. */
    val daemonPath: String = "",
    /** Перезапускать демон при изменении настроек. */
    val autoRestart: Boolean = true,
    /** Запускать при старте приложения, если режим не выключен. */
    val autoStart: Boolean = false,
    val logging: Boolean = false
) {
    fun domainList(): List<String> = domains.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Собирает командную строку goodbyedpi.
     *
     * [blacklistRef] -- подставляемый в `--blacklist` путь к файлу со списком
     * доменов: сам файл пишет движок, здесь нужна только ссылка `{{имя}}`.
     * Режим [GoodbyeDpiMode.DISABLED] отдаёт пустую команду: запускать
     * нечего, и это не ошибка.
     */
    fun toArgs(blacklistRef: String? = null): List<String> {
        val modeset = when (GoodbyeDpiMode.of(mode)) {
            GoodbyeDpiMode.DISABLED -> emptyList()
            // Дефолтный современный режим (сам goodbyedpi стартует с него).
            GoodbyeDpiMode.AUTO -> listOf("-9")
            // Двойной разрез: HTTP и HTTPS, с auto-ttl.
            GoodbyeDpiMode.SPLIT2 -> listOf("-5")
            // Разрез строго по указанной позиции.
            GoodbyeDpiMode.SPLIT_POS -> listOf(
                "-f", splitPos.ifBlank { "2" },
                "-e", splitPos.ifBlank { "2" }
            )
            // Подмена пакета с неверной контрольной суммой.
            GoodbyeDpiMode.FAKE_PATCH -> listOf("-7")
            // Подмена с SEQ в прошлом.
            GoodbyeDpiMode.FAKE_SSL -> listOf("-6")
            // Подмена пакета с настоящим SNI из профиля.
            GoodbyeDpiMode.FAKE_SNI -> listOf("-f", "2", "-e", "2", "--fake-with-sni", fakeSni)
        }.toMutableList()

        if (fakeCsum && GoodbyeDpiMode.of(mode) != GoodbyeDpiMode.FAKE_PATCH) modeset.add("--wrong-chksum")
        if (fakeSeq != null && GoodbyeDpiMode.of(mode) != GoodbyeDpiMode.FAKE_SSL) modeset.add("--wrong-seq")
        if (!hostCheck) modeset.add("--allow-no-sni")
        if (blacklistRef != null) modeset.add("--blacklist=$blacklistRef")
        return modeset
    }

    /** Человекочитаемая строка для показа и копирования. */
    fun toCommandLine(binary: String = daemonPath.ifBlank { "goodbyedpi" }): String =
        (listOf(binary) + toArgs()).joinToString(" ")
}

/** Режимы GoodbyeDPI; короткие имена -- реальные modesets goodbyedpi. */
enum class GoodbyeDpiMode(val code: String, val ru: String, val en: String) {
    AUTO("auto", "Авто", "Auto"),
    DISABLED("disabled", "Выключено", "Disabled"),
    SPLIT2("split2", "Двойной разрез", "Split 2"),
    SPLIT_POS("split-pos", "Разрез по позиции", "Split by position"),
    FAKE_PATCH("fake-patch", "Подмена пакета", "Fake packet"),
    FAKE_SNI("fake-sni", "Подмена SNI", "Fake SNI"),
    FAKE_SSL("fake-ssl", "Подмена SSL", "Fake SSL");

    companion object {
        fun of(code: String?): GoodbyeDpiMode =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: AUTO
    }
}
