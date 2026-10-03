package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable

/**
 * Настройки Zapret, разложенные по настоящим флагам.
 *
 * Имена полей повторяют флаги форка `sukisamfork`, потому что иначе пришлось бы
 * держать таблицу соответствия в голове автора приложения и в документации
 * одновременно. Совпадение имён даёт главное: то, что написано в панели,
 * дословно превращается в аргумент командной строки через [toArgs].
 *
 * Детализация намеренно высокая. Инструмент рассчитан на человека, который уже
 * знает, какой стратегии не хватает, и без тонких ручек подобрать её руками
 * невозможно. Порядок полей совпадает с порядком в панели настроек.
 */
@Serializable
data class ZapretConfig(
    // --- Общие ---
    /** Включить правила Zapret. */
    val enabled: Boolean = false,
    /** Путь к бинарю; пусто -- искать `zapret` в PATH. */
    val binaryPath: String = "",
    /** Подбирать стратегию автоматически по результату проверки сети. */
    val autoStrategy: Boolean = true,
    /** Явно выбранная стратегия; при [autoStrategy] её переопределяет подбор. */
    val strategy: String = ZapretStrategy.AUTO.code,
    /** Режим: `block` блокирует заблокированное, `bypass` обходит его. */
    val mode: String = ZapretMode.BYPASS.code,
    /** Проверять найденную стратегию перед применением. */
    val verifyStrategy: Boolean = true,
    /** Перезапускать процесс, если он упал при включённом тумблере. */
    val autoRestart: Boolean = true,

    // --- Область действия ---
    /** Домены через запятую, например `example.com,youtube.com`. */
    val domains: String = "",
    /** Приложения через запятую, на Android по имени пакета. */
    val apps: String = "",
    /** Применять ко всему трафику, а не только к спискам. */
    val allTraffic: Boolean = false,
    /** Исключения через запятую: к ним правила не применяются. */
    val exclusions: String = "",
    /** Только IPv4. Иначе IPv6 идёт мимо правил. */
    val ipv4Only: Boolean = false,

    // --- Фильтры ---
    /** `--filter-tcp`: порты назначения TCP, например `80,443`. */
    val filterTcp: String = "80,443",
    /** `--filter-udp`: порты назначения UDP. */
    val filterUdp: String = "",
    /** `--dpi-ports`: порты, на которых стоит DPI. */
    val dpiPorts: String = "443",
    /** `--filter-ip`: адреса и подсети через запятую. */
    val filterIp: String = "",

    // --- Синтаксис и списки ---
    /** `--new`: включает новый синтаксис списков с оператором `~`. */
    val newSyntax: Boolean = true,
    /** Автоинкремент счётчика при совпадении, то есть `--bg-inc`. */
    val bgIncrement: Boolean = false,

    // --- Модификация запроса ---
    /** `--hostcase`: приводить имя хоста к нижнему регистру. */
    val hostCase: Boolean = true,
    /** `--hostspell`: разбивать имя хоста. */
    val hostSpell: Boolean = true,
    /** `--hostnospace`: убивать пробелы в имени хоста. */
    val hostNoSpace: Boolean = false,
    /** `--methodspace`: добавлять пробел после метода. */
    val methodSpace: Boolean = true,
    /** `--methodeol`: добавлять перевод строки после метода. */
    val methodEol: Boolean = false,
    /** `--unixeol`: переводы строк в стиле Unix вместо CRLF. */
    val unixEol: Boolean = false,
    /** `--tlsrec`: пересобирать TLS-запись целиком. */
    val tlsRec: Boolean = false,
    /** `--wssize=N`: размер окна для WebSocket. */
    val wsSize: Int? = null,
    /** `--hosthash`: заменить имя хоста на хеш. */
    val hostHash: Boolean = false,

    // --- DPI: desync ---
    /** `--dpi-desync`: методы рассинхронизации через запятую. */
    val desyncMethods: Set<String> = setOf("fake"),
    /** `--dpi-split`: `seqovl` или `multiback`. */
    val dpiSplit: String = "",
    /** `--dpi-split-pos`: позиции разреза, например `1,m1`. */
    val dpiSplitPos: String = "",
    /** `--dpi-split-seqovl`: сдвиг порядкового номера. */
    val dpiSplitSeqovl: Int? = null,
    /** `--dpi-split2-pos`: позиция второго разреза. */
    val dpiSplit2Pos: String = "",
    /** `--dpi-split2-seqovl`. */
    val dpiSplit2Seqovl: Int? = null,

    // --- DPI: fake ---
    /** `--fake-tls`: подменять TLS-пакет. */
    val fakeTlsMode: String = "",
    /** `--fake-tls-host`: SNI для подмены. */
    val fakeTlsHost: String = "",
    /** `--fake-tls-padlen`: длина выравнивания записи. */
    val fakeTlsPadLen: Int? = null,
    /** `--fake-tls-cutlen`: где обрывать запись. */
    val fakeTlsCutLen: Int? = null,
    /** `--fake-packet`: подменять пакет целиком. */
    val fakePacket: Boolean = false,
    /** `--fake-skip`: пропускать подмену на этом пакете. */
    val fakeSkip: Boolean = false,
    /** `--fake-tcp`: подменять как TCP, иначе как UDP. */
    val fakeTcp: Boolean = false,
    /** `--fake-dport`: порт назначения подмены. */
    val fakeDport: Int? = null,
    /** `--fake-dport-ttl`: порт для правил по TTL. */
    val fakeDportTtl: Int? = null,
    /** `--fake-sport`: порт источника подмены. */
    val fakeSport: Int? = null,
    /** `--fake-srcaddr`: подменить адрес источника. */
    val fakeSrcAddr: String = "",
    /** `--fake-seq`: сдвиг порядкового номера. */
    val fakeSeq: Int? = null,
    /** `--fake-csum`: подменить контрольную сумму. */
    val fakeCsum: Boolean = false,
    /** `--fake-tcp-flags`: подменить флаги TCP. */
    val fakeTcpFlags: Int? = null,
    /** `--fake-mss`: подменить MSS. */
    val fakeMss: Int? = null,
    /** `--fake-repeats=N`: сколько раз повторять подмену. */
    val fakeRepeats: Int? = null,
    /** `--fake-cut`: обрезать подмену. */
    val fakeCut: Boolean = false,
    /** `--fake-cut-tls`: обрезать подмену по TLS-записи. */
    val fakeCutTls: Boolean = false,
    /** `--fake-sniff`: подменять содержимое по снифферу. */
    val fakeSniff: Boolean = false,

    // --- TTL и окно ---
    /** `--ttl`: TTL исходящих пакетов. */
    val ttl: Int? = null,
    /** `--ttl-end`: значение, на котором обрывается линия. */
    val ttlEnd: Int? = null,
    /** `--ttl-6`: линейное изменение TTL для 6 байт. */
    val ttl6: Boolean = false,
    /** `--skip-ttl`: не трогать TTL у SYN. */
    val skipTtl: Boolean = false,
    /** `--wsize`: размер окна TCP. */
    val wsize: Int? = null,
    /** `--mss`: максимальный размер сегмента. */
    val mss: Int? = null,
    /** `--multiply=N`: умножать позиции разреза на N. */
    val multiply: Int? = null,
    /** `--multipath`: разрезать оба пути. */
    val multipath: Boolean = false,

    // --- Прочее ---
    /** `--ipdiag`: использовать диагностический адрес вместо отброшенного. */
    val ipdiag: Boolean = false,
    /** Метка в логах: помогает понять, какое правило сработало. */
    val comment: String = "",
    /** Показывать логи. */
    val logging: Boolean = false
) {
    fun domainList(): List<String> = domains.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun appList(): List<String> = apps.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun exclusionList(): List<String> = exclusions.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Применять ли что-то вообще. */
    fun hasWork(): Boolean = enabled &&
        (allTraffic || domainList().isNotEmpty() || appList().isNotEmpty() || filterIp.isNotBlank())

    /**
     * Собирает командную строку zapret.
     *
     * Флаги сверены с `nfq/nfqws.c` официального `bol-van/zapret`: каждая
     * строка здесь -- либо `long_options` nfqws, либо ничего. Порядок
     * фиксирован, чтобы одинаковые настройки всегда давали одинаковую
     * строку: иначе сравнение двух пресетов в diff показывает изменения
     * там, где их нет.
     *
     * Поля, у которых нет флагов nfqws (per-app фильтры, `--fake-*` старых
     * форков), в команду не попадают: неверная опция роняет демон на старте,
     * а это хуже, чем несработавшая настройка.
     */
    fun toArgs(): List<String> = buildList {
        appendFlag("hostcase", hostCase)
        if (hostSpell) add("--hostspell=HoST")
        appendFlag("hostnospace", hostNoSpace)
        appendFlag("methodeol", methodEol)
        appendValue("--wssize", wsSize)

        appendValue("--filter-tcp", filterTcp)
        appendValue("--filter-udp", filterUdp)

        // Списки доменов -- настоящие опции nfqws: значения кладутся прямо
        // в команду, файл для них не нужен.
        if (domainList().isNotEmpty()) {
            add("--hostlist-domains=" + domainList().joinToString(","))
        }
        if (exclusionList().isNotEmpty()) {
            add("--hostlist-exclude-domains=" + exclusionList().joinToString(","))
        }

        // Подмена пакета -- это режим fake у desync, а не отдельная опция.
        val methods = desyncMethods + if (fakePacket) setOf("fake") else emptySet()
        if (methods.isNotEmpty()) {
            add("--dpi-desync=" + methods.joinToString(","))
        }
        appendValue("--dpi-desync-split-pos", dpiSplitPos)
        appendValue("--dpi-desync-split-seqovl", dpiSplitSeqovl)
        appendValue("--dpi-desync-ttl", ttl)
        if (ttl6 && ttl != null) add("--dpi-desync-ttl6=$ttl")
        appendValue("--wsize", wsize)
        fakeRepeats?.let { add("--dpi-desync-repeats=$it") }

        // Fooling: неверная контрольная сумма и SEQ в прошлом -- две реальные
        // опции nfqws, заменяющие собой старые `--fake-csum`/`--fake-seq`.
        val fooling = listOfNotNull(
            "badsum".takeIf { fakeCsum },
            "badseq".takeIf { fakeSeq != null }
        )
        if (fooling.isNotEmpty()) add("--dpi-desync-fooling=" + fooling.joinToString(","))
        if (fakeTcpFlags != null) add("--dpi-desync-tcp-flags-set=$fakeTcpFlags")

        if (comment.isNotBlank()) add("--comment=$comment")
    }

    private fun MutableList<String>.appendFlag(name: String, on: Boolean) {
        if (on) add("--$name")
    }

    private fun MutableList<String>.appendValue(name: String, value: Any?) {
        val text = when (value) {
            null -> return
            is String -> value.trim()
            else -> value.toString()
        }
        if (text.isNotEmpty()) add("$name=$text")
    }

    /** Человекочитаемая строка для показа и копирования. */
    fun toCommandLine(binary: String = binaryPath.ifBlank { "zapret" }): String =
        (listOf(binary) + toArgs()).joinToString(" ")
}

/**
 * Семейство стратегии: способ вмешательства в поток.
 *
 * Разделение не для красоты, а потому что у способов взаимоисключающие
 * наборы ручек. В [DESYNC] важны TTL, окно и позиции разреза; в [FAKE] --
 * размер и содержимое поддельного пакета; в [PATCH] -- пересборка самого
 * запроса. Если применять два набора одновременно, результат непредсказуем.
 */
enum class ZapretFamily {
    /** Ломает поток по TTL и позициям разреза. */
    DESYNC,

    /** Подделывает пакет целиком. */
    FAKE,

    /** Пересобирает сам запрос: подмена SNI, разрез, обрезка. */
    PATCH
}

/**
 * Значения, которые по природе принадлежат стратегии.
 *
 * Держатся рядом со стратегией, а не в настройках, потому что относятся к её
 * способу вмешательства: стратегия «разрез по ALPN» по определению
 * использует `multiply=154`. Подставляются при выборе стратегии, поэтому
 * список ручек в панели сразу соответствует выбранному способу.
 *
 * Все поля nullable с одним смыслом: `null` означает «не трогать настройку
 * пользователя». Для `Boolean` это `false`, поэтому там тип `Boolean?`.
 */
data class ZapretDefaults(
    val newSyntax: Boolean? = null,
    val hostCase: Boolean? = null,
    val hostSpell: Boolean? = null,
    val hostNoSpace: Boolean? = null,
    val methodSpace: Boolean? = null,
    val multipath: Boolean? = null,
    val ipdiag: Boolean? = null,

    val desync: Set<String> = emptySet(),
    val split: String? = null,
    val splitPos: String? = null,
    val splitSeqovl: Int? = null,

    val multiply: Int? = null,
    val ttl: Int? = null,
    val wsize: Int? = null,
    val mss: Int? = null,

    val fakeTlsMode: String? = null,
    val fakePacket: Boolean? = null,
    val fakeTcp: Boolean? = null,
    val fakeCutTls: Boolean? = null,
    val fakeSeq: Int? = null,
    val fakeCsum: Boolean? = null
)

/** Режим работы Zapret. */
enum class ZapretMode(val code: String, val ru: String, val en: String) {
    BYPASS("bypass", "Обход", "Bypass"),
    BLOCK("block", "Блокировка", "Block");

    companion object {
        fun of(code: String?): ZapretMode =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: BYPASS
    }
}

/**
 * Стратегии Zapret.
 *
 * `AUTO` означает подбор, а не фиксированную стратегию: приложение перебирает
 * варианты и оставляет тот, который реально снимает блокировку. У каждой
 * стратегии есть [family] -- способ вмешательства -- и [defaults]: значения,
 * которые ей по природе принадлежат. Семейство важно, потому что у desync и
 * fake взаимоисключающие наборы ручек.
 */
enum class ZapretStrategy(
    val code: String,
    val ru: String,
    val en: String,
    val family: ZapretFamily,
    val defaults: ZapretDefaults
) {
    AUTO("auto", "Авто", "Auto", ZapretFamily.PATCH, ZapretDefaults()),
    DISABLED("disabled", "Выключено", "Disabled", ZapretFamily.PATCH, ZapretDefaults()),

    MULTIPATH(
        "multipath", "Мультипуть", "Multipath",
        ZapretFamily.PATCH, ZapretDefaults(multipath = true)
    ),
    MULTIPLY_ALPN(
        "multiply-alpn", "Разрез по ALPN", "ALPN split",
        ZapretFamily.DESYNC,
        ZapretDefaults(multiply = 154, desync = setOf("fake"))
    ),
    DESYNC_FAKE_TTL_TCP_ACK(
        "desync-fake-ttl-tcp-ack", "Desync по TTL", "Desync by TTL",
        ZapretFamily.DESYNC,
        ZapretDefaults(ttl = 2, desync = setOf("fake"), split = "seqovl")
    ),
    DESYNC_FAKEDSPLIT(
        "desync-fakedsplit", "Desync + fakedsplit", "Desync + fakedsplit",
        ZapretFamily.DESYNC,
        ZapretDefaults(desync = setOf("fakedsplit"))
    ),
    DPI_DESYNC(
        "dpi-desync", "Desync по DPI", "DPI desync",
        ZapretFamily.DESYNC,
        ZapretDefaults(desync = setOf("fake", "split2"))
    ),
    FAKE_SNI_PATCH_TLS_CLIENT_HELLO(
        "fake-sni", "Подмена SNI", "Fake SNI",
        ZapretFamily.PATCH,
        ZapretDefaults(fakeTlsMode = "SNI", hostCase = false, hostSpell = false, methodSpace = false)
    ),
    FAKE_TLS_ALPN(
        "fake-tls-alpn", "TLS с ALPN", "Fake TLS ALPN",
        ZapretFamily.PATCH,
        ZapretDefaults(fakeTlsMode = "multiback", fakeCutTls = true, hostCase = true)
    ),
    SYNTAX_V3(
        "syntax-v3", "Синтаксис v3", "Syntax v3",
        ZapretFamily.PATCH,
        ZapretDefaults(newSyntax = true, hostCase = true, methodSpace = true)
    ),
    FAKE(
        "fake", "Подмена пакета", "Fake packet",
        ZapretFamily.FAKE,
        ZapretDefaults(fakePacket = true, desync = setOf("fake"))
    );

    companion object {
        fun of(code: String?): ZapretStrategy =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: AUTO

        /** Стратегии, которые имеет смысл показывать в списке выбора. */
        val selectable: List<ZapretStrategy> = entries.filter { it != AUTO }
    }
}
