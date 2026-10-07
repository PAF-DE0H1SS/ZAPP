package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AutoStrategy
import xyz.azraellab.zapp.core.GoodbyeDpiMode
import xyz.azraellab.zapp.core.ZapretConfig
import xyz.azraellab.zapp.core.ZapretDefaults
import xyz.azraellab.zapp.core.ZapretFamily
import xyz.azraellab.zapp.core.ZapretMode
import xyz.azraellab.zapp.core.ZapretStrategy

/**
 * Zapret: командная строка, режимы, стратегии, автоподбор.
 *
 * Главная связка здесь «настройка -> флаг nfqws»: каждое поле панели
 * обязано либо превратиться в реальную опцию, либо молча не попасть в
 * команду (опции, которых нет в `nfq/nfqws.c`, роняют демон на старте).
 * Поэтому точные таблицы аргументов -- не хрупкость, а контракт.
 */
private val DEFAULT_ARGS = listOf(
    "--hostcase",
    "--hostspell=HoST",
    "--filter-tcp=80,443",
    "--qnum=0",
    "--dpi-desync=fake"
)

internal fun zapretTests(out: MutableList<DevTest>) {

    // --- Командная строка: дефолты ---

    out += DevTest("zapret/args-defaults", DevCategory.ZAPRET, "Команда по умолчанию") {
        checkEq(DEFAULT_ARGS, ZapretConfig().toArgs(), "toArgs")
    }

    out += DevTest("zapret/args-enabled-changes-nothing", DevCategory.ZAPRET, "enabled не влияет на команду") {
        checkEq(DEFAULT_ARGS, ZapretConfig(enabled = true).toArgs(), "enabled=true")
    }

    // --- Командная строка: отдельные флаги ---

    val flagCases = listOf(
        "hostcase-off" to (ZapretConfig(hostCase = false) to { a: List<String> -> !a.contains("--hostcase") }),
        "hostspell-off" to (ZapretConfig(hostSpell = false) to { a: List<String> -> !a.contains("--hostspell=HoST") }),
        "hostnospace" to (ZapretConfig(hostNoSpace = true) to { a: List<String> -> a.contains("--hostnospace") }),
        "methodeol" to (ZapretConfig(methodEol = true) to { a: List<String> -> a.contains("--methodeol") }),
        "filter-udp" to (ZapretConfig(filterUdp = "53,443") to { a: List<String> -> a.contains("--filter-udp=53,443") }),
        "dpi-ports-ignored" to (ZapretConfig(dpiPorts = "8443") to { a: List<String> -> a.none { it.startsWith("--dpi-ports") } }),
        "filter-ip" to (ZapretConfig(filterIp = "1.2.3.4/32") to { a: List<String> -> a.none { it.contains("filter-ip") } }),
        "filter-tcp-custom" to (ZapretConfig(filterTcp = "443") to { a: List<String> -> a.contains("--filter-tcp=443") }),
        "filter-tcp-blank" to (ZapretConfig(filterTcp = "  ") to { a: List<String> -> a.none { it.startsWith("--filter-tcp") } })
    )
    flagCases.forEach { (id, case) ->
        val (config, expectation) = case
        out += DevTest("zapret/args-$id", DevCategory.ZAPRET, "Флаг $id: ${if (id.endsWith("-off")) "нет" else "есть"}") {
            checkTrue(expectation(config.toArgs()), "args=${config.toArgs()}")
        }
    }

    out += DevTest("zapret/args-wssize", DevCategory.ZAPRET, "--wssize из числа") {
        checkIn("--wssize=1024", ZapretConfig(wsSize = 1024).toArgs(), "wssize")
        checkTrue(ZapretConfig(wsSize = null).toArgs().none { it.startsWith("--wssize") }, "null -- нет флага")
    }

    out += DevTest("zapret/args-qnum-always", DevCategory.ZAPRET, "--qnum=0 всегда в команде") {
        for (config in listOf(ZapretConfig(), ZapretConfig(enabled = true, allTraffic = true))) {
            checkIn("--qnum=0", config.toArgs(), "qnum")
        }
    }

    out += DevTest("zapret/args-comment", DevCategory.ZAPRET, "--comment пишется, пустой -- нет") {
        checkIn("--comment=note", ZapretConfig(comment = "note").toArgs(), "непустой")
        checkTrue(ZapretConfig(comment = " ").toArgs().none { it.startsWith("--comment") }, "пробел -- пусто")
    }

    out += DevTest("zapret/args-domains", DevCategory.ZAPRET, "Домены списком") {
        val args = ZapretConfig(domains = " youtube.com, rutracker.org ,, ").toArgs()
        checkIn("--hostlist-domains=youtube.com,rutracker.org", args, "домены без пробелов")
    }

    out += DevTest("zapret/args-exclusions", DevCategory.ZAPRET, "Исключения списком") {
        val args = ZapretConfig(exclusions = "local.example,corp.example").toArgs()
        checkIn("--hostlist-exclude-domains=local.example,corp.example", args, "исключения")
    }

    out += DevTest("zapret/args-desync-methods-order", DevCategory.ZAPRET, "desync-методы в порядке добавления") {
        val args = ZapretConfig(desyncMethods = linkedSetOf("fakedsplit", "split2")).toArgs()
        checkIn("--dpi-desync=fakedsplit,split2", args, "порядок")
    }

    out += DevTest("zapret/args-fake-packet-dedup", DevCategory.ZAPRET, "fakePacket не дублирует fake в desync") {
        val args = ZapretConfig(desyncMethods = setOf("fake"), fakePacket = true).toArgs()
        checkIn("--dpi-desync=fake", args, "ровно один fake")
        checkEq(1, args.count { it.startsWith("--dpi-desync=") }, "только один desync-флаг")
    }

    out += DevTest("zapret/args-ttl-and-ttl6", DevCategory.ZAPRET, "ttl и ttl6") {
        val args = ZapretConfig(ttl = 2, ttl6 = true).toArgs()
        checkIn("--dpi-desync-ttl=2", args, "ttl")
        checkIn("--dpi-desync-ttl6=2", args, "ttl6")
        val only6 = ZapretConfig(ttl6 = true).toArgs()
        checkTrue(only6.none { it.contains("ttl6") }, "ttl6 без ttl не пишется")
    }

    out += DevTest("zapret/args-fooling", DevCategory.ZAPRET, "fooling собирается из ручек") {
        val args = ZapretConfig(fakeCsum = true, foolingTs = true).toArgs()
        checkIn("--dpi-desync-fooling=badsum,ts", args, "badsum+ts")
        val all = ZapretConfig(fakeCsum = true, fakeSeq = 1, foolingTs = true).toArgs()
        checkIn("--dpi-desync-fooling=badsum,badseq,ts", all, "порядок")
        checkTrue(ZapretConfig().toArgs().none { it.contains("fooling") }, "выключено -- нет флага")
    }

    out += DevTest("zapret/args-tcp-flags", DevCategory.ZAPRET, "--dpi-desync-tcp-flags-set") {
        checkIn("--dpi-desync-tcp-flags-set=6", ZapretConfig(fakeTcpFlags = 6).toArgs(), "flags")
        checkTrue(ZapretConfig().toArgs().none { it.contains("tcp-flags") }, "null -- нет флага")
    }

    out += DevTest("zapret/args-repeats-and-seqovl", DevCategory.ZAPRET, "repeats и split-seqovl") {
        val args = ZapretConfig(fakeRepeats = 6, dpiSplitSeqovl = 100, dpiSplitPos = "1").toArgs()
        checkIn("--dpi-desync-repeats=6", args, "repeats")
        checkIn("--dpi-desync-split-seqovl=100", args, "seqovl")
        checkIn("--dpi-desync-split-pos=1", args, "split-pos")
    }

    out += DevTest("zapret/args-nfqws-only-options", DevCategory.ZAPRET, "Нет опций старых форков") {
        val args = ZapretConfig(fakePacket = true, fakeTlsMode = "SNI", fakeTlsHost = "x.example").toArgs()
        checkTrue(args.none { it.startsWith("--fake-tls") }, "fake-tls* не существует в nfqws")
        checkTrue(args.none { it.startsWith("--fake-seq") }, "--fake-seq заменён fooling")
        checkTrue(args.none { it.startsWith("--fake-csum") }, "--fake-csum заменён fooling")
        checkTrue(args.none { it.startsWith("--fake-packet") }, "fake-packet -- это desync, не флаг")
    }

    out += DevTest("zapret/command-line", DevCategory.ZAPRET, "Строка для показа: бинарь + команда") {
        checkEq("nfqws " + DEFAULT_ARGS.joinToString(" "), ZapretConfig().toCommandLine(), "дефолтный бинарь")
        checkEq(
            "/data/local/tmp/x " + DEFAULT_ARGS.joinToString(" "),
            ZapretConfig(binaryPath = "/data/local/tmp/x").toCommandLine(),
            "свой путь"
        )
    }

    // --- hasWork ---

    val workCases = listOf(
        "disabled" to (ZapretConfig(enabled = false, allTraffic = true) to false),
        "all-traffic" to (ZapretConfig(enabled = true, allTraffic = true) to true),
        "domains" to (ZapretConfig(enabled = true, domains = "a.com") to true),
        "apps" to (ZapretConfig(enabled = true, apps = "ru.app") to true),
        "filter-ip" to (ZapretConfig(enabled = true, filterIp = "1.2.3.4") to true),
        "enabled-alone" to (ZapretConfig(enabled = true) to false),
        "domains-when-off" to (ZapretConfig(domains = "a.com") to false)
    )
    workCases.forEach { (id, case) ->
        val (config, expected) = case
        out += DevTest("zapret/haswork-$id", DevCategory.ZAPRET, "hasWork($id) == $expected") {
            checkEq(expected, config.hasWork(), "hasWork")
        }
    }

    out += DevTest("zapret/lists-parse", DevCategory.ZAPRET, "Списки доменов/приложений/исключений") {
        val config = ZapretConfig(
            domains = " a.com , b.com ,,",
            apps = " ru.app, com.app ",
            exclusions = " x.com,"
        )
        checkEq(listOf("a.com", "b.com"), config.domainList(), "domains")
        checkEq(listOf("ru.app", "com.app"), config.appList(), "apps")
        checkEq(listOf("x.com"), config.exclusionList(), "exclusions")
    }

    // --- Режимы и стратегии ---

    out += DevTest("zapret/mode-of", DevCategory.ZAPRET, "ZapretMode.of") {
        checkEq(ZapretMode.BYPASS, ZapretMode.of("bypass"), "bypass")
        checkEq(ZapretMode.BLOCK, ZapretMode.of(" BLOCK "), "блок с регистром")
        checkEq(ZapretMode.BYPASS, ZapretMode.of("не то"), "неизвестный")
        checkEq(ZapretMode.BYPASS, ZapretMode.of(null), "null")
    }

    out += DevTest("zapret/strategy-of", DevCategory.ZAPRET, "ZapretStrategy.of") {
        ZapretStrategy.entries.forEach { strategy ->
            checkEq(strategy, ZapretStrategy.of(strategy.code), strategy.code)
        }
        checkEq(ZapretStrategy.AUTO, ZapretStrategy.of("не то"), "неизвестный")
        checkEq(ZapretStrategy.AUTO, ZapretStrategy.of(null), "null")
    }

    out += DevTest("zapret/strategy-codes-unique", DevCategory.ZAPRET, "Коды стратегий уникальны") {
        val codes = ZapretStrategy.entries.map { it.code }
        checkEq(codes.size, codes.toSet().size, "дубликаты: $codes")
        val names = ZapretStrategy.entries.map { it.ru }
        checkEq(names.size, names.toSet().size, "дубликаты имён: $names")
    }

    out += DevTest("zapret/selectable-hides-auto", DevCategory.ZAPRET, "AUTO не показывается в выборе") {
        checkFalse(ZapretStrategy.selectable.contains(ZapretStrategy.AUTO), "AUTO в списке")
        checkEq(
            ZapretStrategy.entries.size - 1,
            ZapretStrategy.selectable.size,
            "selectable = все кроме AUTO"
        )
    }

    out += DevTest("zapret/families-sane", DevCategory.ZAPRET, "У каждой стратегии своё семейство") {
        checkEq(
            setOf(ZapretFamily.DESYNC, ZapretFamily.FAKE, ZapretFamily.PATCH),
            ZapretStrategy.entries.map { it.family }.toSet(),
            "все семейства представлены"
        )
        checkTrue(ZapretStrategy.entries.all { it.defaults != ZapretDefaults() || it == ZapretStrategy.AUTO || it == ZapretStrategy.DISABLED },
            "у непустых стратегий есть дефолты")
    }

    // --- Автоподбор ---

    fun probe(
        tlsBlocked: Boolean = false,
        tls13Blocked: Boolean = false,
        quicBlocked: Boolean = false,
        dpiPresent: Boolean = true,
        sniInspection: Boolean = false
    ) = AutoStrategy.Probe(tlsBlocked, tls13Blocked, quicBlocked, dpiPresent, sniInspection)

    val decisions = listOf(
        "dpi-absent" to Triple(probe(dpiPresent = false), ZapretStrategy.DISABLED.code, "dpi_absent"),
        "tls13" to Triple(probe(tls13Blocked = true), ZapretStrategy.MULTIPLY_ALPN.code, "tls13_blocked"),
        "sni" to Triple(probe(sniInspection = true), ZapretStrategy.FAKE_SNI_PATCH_TLS_CLIENT_HELLO.code, "sni_inspected"),
        "client-hello" to Triple(probe(tlsBlocked = true), ZapretStrategy.DESYNC_FAKE_TTL_TCP_ACK.code, "client_hello_dropped"),
        "quic" to Triple(probe(quicBlocked = true), ZapretStrategy.MULTIPLY_ALPN.code, "tls_extension_filtered")
    )
    decisions.forEach { (id, expected) ->
        val (probeValue, strategy, reason) = expected
        out += DevTest("zapret/choose-$id", DevCategory.ZAPRET, "Подбор $id -> $strategy ($reason)") {
            val decision = AutoStrategy.chooseZapretStrategy(probeValue, ZapretConfig())
            checkEq(strategy, decision.strategy, "стратегия")
            checkEq(reason, decision.reason, "причина")
        }
    }

    out += DevTest("zapret/choose-priority", DevCategory.ZAPRET, "Порядок веток: tls13 > sni > tls") {
        val everything = probe(tlsBlocked = true, tls13Blocked = true, sniInspection = true)
        checkEq(
            ZapretStrategy.MULTIPLY_ALPN.code,
            AutoStrategy.chooseZapretStrategy(everything, ZapretConfig()).strategy,
            "tls13 важнее всего"
        )
        val sniAndTls = probe(tlsBlocked = true, sniInspection = true)
        checkEq(
            ZapretStrategy.FAKE_SNI_PATCH_TLS_CLIENT_HELLO.code,
            AutoStrategy.chooseZapretStrategy(sniAndTls, ZapretConfig()).strategy,
            "sni важнее tls"
        )
    }

    out += DevTest("zapret/choose-nothing", DevCategory.ZAPRET, "Чистая сеть -> стратегия из настроек") {
        val decision = AutoStrategy.chooseZapretStrategy(probe(), ZapretConfig(strategy = ZapretStrategy.MULTIPATH.code))
        checkEq(ZapretStrategy.MULTIPATH.code, decision.strategy, "из конфига")
        checkEq("nothing_detected", decision.reason, "причина")
    }

    out += DevTest("zapret/choose-nothing-disabled-fallback", DevCategory.ZAPRET, "disabled в конфиге -> безопасный запас") {
        val decision = AutoStrategy.chooseZapretStrategy(probe(), ZapretConfig(strategy = ZapretStrategy.DISABLED.code))
        checkEq(ZapretStrategy.MULTIPLY_ALPN.code, decision.strategy, "запасная стратегия")
    }

    // --- Применение решения ---

    out += DevTest("zapret/apply-sets-strategy-and-defaults", DevCategory.ZAPRET, "Решение пишет и стратегию, и её значения") {
        val applied = AutoStrategy.applyZapret(
            ZapretConfig(enabled = true, domains = "a.com", ttl = 99),
            AutoStrategy.Decision(ZapretStrategy.FAKE_TLS_ALPN.code, "test")
        )
        checkEq(ZapretStrategy.FAKE_TLS_ALPN.code, applied.strategy, "стратегия")
        checkFalse(applied.autoStrategy, "автоподбор выключился")
        checkEq("multiback", applied.fakeTlsMode, "fakeTlsMode из стратегии")
        checkTrue(applied.fakeCutTls, "fakeCutTls")
        checkTrue(applied.hostCase, "hostCase")
        checkEq(99, applied.ttl, "ручки, которых стратегия не задаёт, остаются")
        checkTrue(applied.enabled, "enabled не тронут")
        checkEq("a.com", applied.domains, "домены не тронуты")
    }

    out += DevTest("zapret/apply-multiply-alpn", DevCategory.ZAPRET, "MULTIPLY_ALPN задаёт multiply и fake") {
        val applied = AutoStrategy.applyZapret(ZapretConfig(), AutoStrategy.Decision(ZapretStrategy.MULTIPLY_ALPN.code, "t"))
        checkEq(154, applied.multiply, "multiply")
        checkEq(setOf("fake"), applied.desyncMethods, "desync")
    }

    out += DevTest("zapret/apply-fake-sni", DevCategory.ZAPRET, "Fake SNI гасит ручки манипуляции хостом") {
        val applied = AutoStrategy.applyZapret(
            ZapretConfig(),
            AutoStrategy.Decision(ZapretStrategy.FAKE_SNI_PATCH_TLS_CLIENT_HELLO.code, "t")
        )
        checkEq("SNI", applied.fakeTlsMode, "fakeTlsMode")
        checkFalse(applied.hostCase, "hostCase")
        checkFalse(applied.hostSpell, "hostSpell")
        checkFalse(applied.methodSpace, "methodSpace")
    }

    out += DevTest("zapret/apply-preset-multisplit", DevCategory.ZAPRET, "multisplit-seqovl из zapret-discord-youtube") {
        val applied = AutoStrategy.applyZapret(
            ZapretConfig(),
            AutoStrategy.Decision(ZapretStrategy.MULTISPLIT_SEQOVL.code, "t")
        )
        checkEq(setOf("multisplit"), applied.desyncMethods, "desync")
        checkEq("1", applied.dpiSplitPos, "split-pos")
        checkEq(568, applied.dpiSplitSeqovl, "seqovl")
    }

    out += DevTest("zapret/apply-preset-fake-ts", DevCategory.ZAPRET, "fake-fakedsplit-ts: repeats и ts") {
        val applied = AutoStrategy.applyZapret(
            ZapretConfig(),
            AutoStrategy.Decision(ZapretStrategy.FAKE_FAKEDSPLIT_TS.code, "t")
        )
        checkEq(setOf("fake", "fakedsplit"), applied.desyncMethods, "desync")
        checkEq(6, applied.fakeRepeats, "repeats")
        checkTrue(applied.foolingTs, "foolingTs")
    }

    out += DevTest("zapret/apply-desync-ttl", DevCategory.ZAPRET, "desync-fake-ttl-tcp-ack") {
        val applied = AutoStrategy.applyZapret(
            ZapretConfig(),
            AutoStrategy.Decision(ZapretStrategy.DESYNC_FAKE_TTL_TCP_ACK.code, "t")
        )
        checkEq(2, applied.ttl, "ttl")
        checkEq(setOf("fake"), applied.desyncMethods, "desync")
        checkEq("seqovl", applied.dpiSplit, "split")
    }

    out += DevTest("zapret/apply-multipath", DevCategory.ZAPRET, "multipath") {
        val applied = AutoStrategy.applyZapret(ZapretConfig(), AutoStrategy.Decision(ZapretStrategy.MULTIPATH.code, "t"))
        checkTrue(applied.multipath, "multipath")
    }

    out += DevTest("zapret/apply-unknown-strategy-keeps-config", DevCategory.ZAPRET, "Неизвестный код -> AUTO, ручки не тронуты") {
        val applied = AutoStrategy.applyZapret(ZapretConfig(ttl = 5), AutoStrategy.Decision("что-то", "t"))
        checkEq(ZapretStrategy.AUTO.code, applied.strategy, "strategy")
        checkEq(5, applied.ttl, "ttl остался")
    }

    out += DevTest("zapret/apply-does-not-touch-work", DevCategory.ZAPRET, "Применение не меняет включённость") {
        val off = AutoStrategy.applyZapret(ZapretConfig(), AutoStrategy.Decision(ZapretStrategy.FAKE.code, "t"))
        checkFalse(off.enabled, "enabled остаётся выключенным")
        val on = AutoStrategy.applyZapret(ZapretConfig(enabled = true), AutoStrategy.Decision(ZapretStrategy.FAKE.code, "t"))
        checkTrue(on.enabled, "enabled остаётся включённым")
    }

    // --- Шаги проверки ---

    out += DevTest("zapret/steps-zapret", DevCategory.ZAPRET, "Шаги проверки zapret: 5, по порядку") {
        val steps = AutoStrategy.stepsForZapret()
        checkEq(5, steps.size, "количество")
        checkEq(listOf(1, 2, 3, 4, 5), steps.map { it.index }, "индексы")
        checkEq(steps.size, steps.map { it.id }.toSet().size, "id уникальны")
        checkIn("quic", steps.map { it.id }, "есть QUIC")
        checkIn("sni", steps.map { it.id }, "есть SNI")
    }

    out += DevTest("zapret/steps-goodbyedpi", DevCategory.ZAPRET, "Шаги проверки GoodbyeDPI: 3") {
        val steps = AutoStrategy.stepsForGoodbyeDpi()
        checkEq(3, steps.size, "количество")
        checkEq(listOf(1, 2, 3), steps.map { it.index }, "индексы")
        checkTrue(steps.all { it.title.isNotBlank() }, "заголовки непустые")
    }

    out += DevTest("zapret/gdpi-choice-cases", DevCategory.ZAPRET, "Выбор режима GoodbyeDPI по пробе") {
        fun g(tls: Boolean = false, tls13: Boolean = false, sni: Boolean = false, dpi: Boolean = true) =
            AutoStrategy.Probe(tlsBlocked = tls, tls13Blocked = tls13, quicBlocked = false, dpiPresent = dpi, sniInspection = sni)
        val cases = listOf(
            g(dpi = false) to Triple(GoodbyeDpiMode.DISABLED.code, "dpi_absent", "dpi-absent"),
            g(sni = true) to Triple(GoodbyeDpiMode.FAKE_SNI.code, "sni_inspected", "sni"),
            g(tls = true) to Triple(GoodbyeDpiMode.SPLIT2.code, "client_hello_dropped", "tls"),
            g(tls13 = true) to Triple(GoodbyeDpiMode.SPLIT2.code, "client_hello_dropped", "tls13"),
            g() to Triple(GoodbyeDpiMode.AUTO.code, "nothing_detected", "clean")
        )
        cases.forEach { (probeValue, expected) ->
            val decision = AutoStrategy.chooseGoodbyeDpiMode(probeValue)
            checkEq(expected.first, decision.strategy, expected.third)
            checkEq(expected.second, decision.reason, "${expected.third} reason")
        }
    }
}
