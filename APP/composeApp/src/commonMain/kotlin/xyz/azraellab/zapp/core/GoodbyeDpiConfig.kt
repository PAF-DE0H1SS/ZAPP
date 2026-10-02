package xyz.azraellab.zapp.core

/**
 * Настройки GoodbyeDPI.
 *
 * Отличие от Zapret принципиальное: Zapret работает с пакетами, а GoodbyeDPI --
 * в первом рукопожатии, меняя метку у TLS ClientHello. Поэтому здесь нет ручек
 * для TTL, портов и desync, зато есть то, что относится к самому рукопожатию.
 */
@kotlinx.serialization.Serializable
data class GoodbyeDpiConfig(
    /** Автоматически подбирать режим под текущую сеть. */
    val autoMode: Boolean = true,
    /** Режим из [GoodbyeDpiMode]. */
    val mode: String = GoodbyeDpiMode.AUTO.code,

    // --- Где резать ---
    /** Позиция разреза в ClientHello: после заголовков или после первого блока. */
    val splitPos: String = "2",
    /** Смещение от позиции разреза, то есть сколько байт сдвинуть. */
    val splitOffset: Int? = null,
    /** Сдвигать на указанное число байт вперёд. */
    val splitAt: Int? = null,

    // --- Как выглядит подмена ---
    /** Размер фейкового пакета. */
    val fakeLen: Int? = null,
    /** Заполнитель фейкового пакета: нули, `0xDEADBEEF` или случайный. */
    val fakeVal: String = "",
    /** Подменять порядковый номер. */
    val fakeSeq: Int? = null,
    /** Подменять контрольную сумму. */
    val fakeCsum: Boolean = false,
    /** Подменять флаги TCP. */
    val fakeFlags: Int? = null,
    /** Подменять MSS в фейковом пакете. */
    val fakeMss: Int? = null,

    // --- Что пропускать ---
    /** Пропускать TLS-расширения, которые делают DPI слепым. */
    val skipAlpn: Boolean = false,
    /** Пропускать TLS 1.3. */
    val skipTls13: Boolean = false,
    /** Оставлять SNI как есть, не вырезать. */
    val keepSni: Boolean = false,
    /** Проверять домен перед применением правила. */
    val hostCheck: Boolean = true,

    // --- Область действия ---
    val domains: String = "",
    val apps: String = "",
    val allTraffic: Boolean = false,
    val exclusions: String = "",
    val ipv4Only: Boolean = false,

    // --- Права и запуск ---
    /** Путь к демону, когда приложение не может поднять его само. */
    val daemonPath: String = "",
    /** Перезапускать демон при изменении настроек. */
    val autoRestart: Boolean = true,
    val logging: Boolean = false,
    val comment: String = ""
) {
    fun domainList(): List<String> = domains.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun appList(): List<String> = apps.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun exclusionList(): List<String> = exclusions.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Режимы GoodbyeDPI. */
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
