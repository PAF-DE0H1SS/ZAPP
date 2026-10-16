package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.ByeDpiConfig
import xyz.azraellab.zapp.core.ByeDpiStrategy
import xyz.azraellab.zapp.core.DpiBackend
import xyz.azraellab.zapp.core.GoodbyeDpiConfig
import xyz.azraellab.zapp.core.GoodbyeDpiMode
import xyz.azraellab.zapp.core.daemon.DpiOverlay

/**
 * GoodbyeDPI: режимы короткими кодами `-1..-9` и флаги рукопожатия.
 *
 * Как и у Zapret, точные аргументы -- контракт с `src/goodbyedpi.c`:
 * лишний флаг роняет демон, отсутствующий -- молча не работает настройка.
 */
internal fun dpiTests(out: MutableList<DevTest>) {

    // --- Режимы ---

    out += DevTest("dpi/mode-of", DevCategory.DPI, "GoodbyeDpiMode.of") {
        GoodbyeDpiMode.entries.forEach { mode ->
            checkEq(mode, GoodbyeDpiMode.of(mode.code), mode.code)
            checkEq(mode, GoodbyeDpiMode.of(" ${mode.code.uppercase()} "), mode.code)
        }
        checkEq(GoodbyeDpiMode.AUTO, GoodbyeDpiMode.of("не то"), "неизвестный")
        checkEq(GoodbyeDpiMode.AUTO, GoodbyeDpiMode.of(null), "null")
    }

    out += DevTest("dpi/mode-codes-unique", DevCategory.DPI, "Коды и имена режимов уникальны") {
        val codes = GoodbyeDpiMode.entries.map { it.code }
        checkEq(codes.size, codes.toSet().size, "коды: $codes")
        val ru = GoodbyeDpiMode.entries.map { it.ru }
        checkEq(ru.size, ru.toSet().size, "имена: $ru")
    }

    // --- Командная строка по режимам ---

    val modeCommands = listOf(
        "disabled" to (GoodbyeDpiMode.DISABLED to emptyList<String>()),
        "auto" to (GoodbyeDpiMode.AUTO to listOf("-9")),
        "split2" to (GoodbyeDpiMode.SPLIT2 to listOf("-5")),
        "fake-patch" to (GoodbyeDpiMode.FAKE_PATCH to listOf("-7")),
        "fake-ssl" to (GoodbyeDpiMode.FAKE_SSL to listOf("-6"))
    )
    modeCommands.forEach { (id, case) ->
        val (mode, expected) = case
        out += DevTest("dpi/command-$id", DevCategory.DPI, "Режим $id -> $expected") {
            checkEq(expected, GoodbyeDpiConfig(mode = mode.code).toArgs(), "args")
        }
    }

    out += DevTest("dpi/command-split-pos", DevCategory.DPI, "split-pos пишет позицию в -f и -e") {
        checkEq(
            listOf("-f", "7", "-e", "7"),
            GoodbyeDpiConfig(mode = GoodbyeDpiMode.SPLIT_POS.code, splitPos = "7").toArgs(),
            "свою позицию"
        )
        checkEq(
            listOf("-f", "2", "-e", "2"),
            GoodbyeDpiConfig(mode = GoodbyeDpiMode.SPLIT_POS.code, splitPos = " ").toArgs(),
            "пустая позиция -> 2"
        )
    }

    out += DevTest("dpi/command-fake-sni", DevCategory.DPI, "fake-sni кладёт SNI в хвост") {
        val args = GoodbyeDpiConfig(
            mode = GoodbyeDpiMode.FAKE_SNI.code,
            fakeSni = "www.example.com"
        ).toArgs()
        checkEq(listOf("-f", "2", "-e", "2", "--fake-with-sni", "www.example.com"), args, "args")
    }

    // --- Спутниковые флаги ---

    out += DevTest("dpi/wrong-chksum", DevCategory.DPI, "--wrong-chksum, кроме fake-patch") {
        checkIn("--wrong-chksum", GoodbyeDpiConfig(mode = "auto", fakeCsum = true).toArgs(), "auto")
        checkIn("--wrong-chksum", GoodbyeDpiConfig(mode = "split2", fakeCsum = true).toArgs(), "split2")
        checkTrue(
            GoodbyeDpiConfig(mode = "fake-patch", fakeCsum = true).toArgs().none { it == "--wrong-chksum" },
            "fake-patch делает то же самое"
        )
        checkTrue(GoodbyeDpiConfig(mode = "auto").toArgs().none { it == "--wrong-chksum" }, "выключено")
    }

    out += DevTest("dpi/wrong-seq", DevCategory.DPI, "--wrong-seq, кроме fake-ssl") {
        checkIn("--wrong-seq", GoodbyeDpiConfig(mode = "auto", fakeSeq = 1).toArgs(), "auto")
        checkTrue(
            GoodbyeDpiConfig(mode = "fake-ssl", fakeSeq = 1).toArgs().none { it == "--wrong-seq" },
            "fake-ssl делает то же самое"
        )
        checkTrue(GoodbyeDpiConfig(mode = "auto").toArgs().none { it == "--wrong-seq" }, "null -- нет флага")
    }

    out += DevTest("dpi/host-check", DevCategory.DPI, "--allow-no-sni при выключённой проверке хоста") {
        checkIn("--allow-no-sni", GoodbyeDpiConfig(hostCheck = false).toArgs(), "выкл")
        checkTrue(GoodbyeDpiConfig(hostCheck = true).toArgs().none { it == "--allow-no-sni" }, "вкл")
    }

    out += DevTest("dpi/blacklist-ref", DevCategory.DPI, "--blacklist только со ссылкой") {
        checkIn("--blacklist=/data/x.txt", GoodbyeDpiConfig(domains = "a.com").toArgs("/data/x.txt"), "с ссылкой")
        checkTrue(
            GoodbyeDpiConfig(domains = "a.com").toArgs(null).none { it.startsWith("--blacklist") },
            "без ссылки"
        )
        checkTrue(GoodbyeDpiConfig().toArgs("/data/x.txt").any { it == "--blacklist=/data/x.txt" }, "список не нужен для ссылки")
    }

    out += DevTest("dpi/combined-flags", DevCategory.DPI, "Режим плюс спутники в одной команде") {
        val args = GoodbyeDpiConfig(
            mode = GoodbyeDpiMode.SPLIT2.code,
            fakeCsum = true,
            fakeSeq = 3,
            hostCheck = false
        ).toArgs()
        checkEq("-5", args.first(), "режим первым")
        checkIn("--wrong-chksum", args, "chksum")
        checkIn("--wrong-seq", args, "seq")
        checkIn("--allow-no-sni", args, "allow-no-sni")
        checkEq(args.size, args.toSet().size, "без дублей")
    }

    out += DevTest("dpi/domain-list", DevCategory.DPI, "Список доменов") {
        val config = GoodbyeDpiConfig(domains = " a.com , b.com ,,")
        checkEq(listOf("a.com", "b.com"), config.domainList(), "domainList")
        checkTrue(GoodbyeDpiConfig().domainList().isEmpty(), "пусто")
    }

    // C7: подпись области в журнале. Раньше пустые порты подписывались
    // «all tcp», хотя через релей в таком случае не шло ни байта.
    out += DevTest("dpi/scope-label", DevCategory.DPI, "Область обхода подписана честно") {
        DpiOverlay.update(listOf("80", "443"), quicBlock = false)
        checkEq("80, 443", DpiOverlay.scopeLabel(), "только порты")

        DpiOverlay.update(listOf("443"), quicBlock = false, hosts = listOf("youtube.com"))
        checkEq("443 for youtube.com", DpiOverlay.scopeLabel(), "порты и домены")

        DpiOverlay.update(emptyList(), quicBlock = true, hosts = listOf("youtube.com"))
        checkEq("domains only: youtube.com", DpiOverlay.scopeLabel(), "домены без портов")

        DpiOverlay.update(emptyList(), quicBlock = false)
        checkEq("nothing routed", DpiOverlay.scopeLabel(), "пусто -- не «all tcp»")
    }

    out += DevTest("dpi/command-line", DevCategory.DPI, "Строка для показа") {
        checkEq("goodbyedpi -9", GoodbyeDpiConfig().toCommandLine(), "дефолтный бинарь")
        checkEq("/bin/gd -9", GoodbyeDpiConfig(daemonPath = "/bin/gd").toCommandLine(), "свой путь")
        checkEq(
            "goodbyedpi",
            GoodbyeDpiConfig(mode = GoodbyeDpiMode.DISABLED.code).toCommandLine(),
            "выключено: только бинарь"
        )
    }

    out += DevTest("dpi/defaults", DevCategory.DPI, "Настройки по умолчанию консервативны") {
        val config = GoodbyeDpiConfig()
        checkEq(GoodbyeDpiMode.AUTO.code, config.mode, "авто-режим")
        checkTrue(config.hostCheck, "проверка хоста включена")
        checkFalse(config.autoStart, "не стартует сам")
        checkFalse(config.fakeCsum, "подмена выключена")
        checkEq(null, config.fakeSeq, "seq не задан")
    }

    // --- byedpi: пресеты, аргументы, выбор механизма ---

    out += DevTest("dpi/byedpi-strategies", DevCategory.DPI, "Пресеты byedpi: коды и аргументы") {
        val codes = ByeDpiStrategy.entries.map { it.code }
        checkEq(codes.size, codes.toSet().size, "коды уникальны: $codes")
        ByeDpiStrategy.entries.forEach { strategy ->
            checkEq(strategy, ByeDpiStrategy.of(strategy.code), strategy.code)
            checkEq(strategy, ByeDpiStrategy.of(" ${strategy.code.uppercase()} "), strategy.code)
            checkTrue(strategy.args.isNotEmpty(), "${strategy.code}: аргументы есть")
            checkTrue(strategy.args.all { it.isNotBlank() }, "${strategy.code}: пустых аргументов нет")
            checkTrue(strategy.args.first().startsWith("--"), "${strategy.code}: первый аргумент -- флаг")
            checkTrue(strategy.ru.isNotBlank() && strategy.en.isNotBlank(), "${strategy.code}: подписи")
        }
        checkEq(ByeDpiStrategy.DISORDER, ByeDpiStrategy.of("не то"), "неизвестный -- дефолт")
        checkEq(ByeDpiStrategy.DISORDER, ByeDpiStrategy.of(null), "null -- дефолт")
    }

    out += DevTest("dpi/byedpi-command", DevCategory.DPI, "Аргументы byedpi: порт релея, без лишнего") {
        val args = ByeDpiConfig().toArgs()
        // Слушатель обязан быть тем же портом, что у релея: бэкенды
        // подменяют друг друга на месте, конфиг туннеля не меняется.
        checkEq("-i", args.getOrNull(0), "bind первым")
        checkEq("127.0.0.1", args.getOrNull(1), "только loopback")
        checkEq("-p", args.getOrNull(2), "порт вторым")
        checkEq(DpiOverlay.RELAY_PORT.toString(), args.getOrNull(3), "порт релея")
        checkIn("--disorder", args, "стратегия по умолчанию")
        checkTrue(args.none { it == "-H" }, "без доменов -H не пишется")
        checkTrue(
            ByeDpiConfig(strategy = "tlsrec").toArgs().contains("--tlsrec"),
            "стратегия подставляется целиком"
        )
        // Дефолт -- дисклеймер: на Android машине это не веб-тест, тут
        // важен сам факт, что README-дефолт для Linux не потерян.
        checkEq(ByeDpiStrategy.DISORDER.code, ByeDpiConfig().strategy, "дефолтная стратегия")
        checkTrue(ByeDpiConfig().extraArgs.isEmpty(), "хвост пуст по умолчанию")
        checkTrue(
            ByeDpiConfig().toCommandLine().startsWith("byedpi -i 127.0.0.1 -p "),
            "карточка команды показывает реальную строку"
        )
    }

    out += DevTest("dpi/byedpi-hosts", DevCategory.DPI, "Список доменов уходит inline") {
        val args = ByeDpiConfig().toArgs(listOf("a.com", "b.com"))
        val at = args.indexOf("-H")
        checkTrue(at >= 0, "-H есть")
        checkEq(":a.com b.com", args.getOrNull(at + 1), "inline-строка через двоеточие")
        checkTrue(
            ByeDpiConfig().toArgs(emptyList()).none { it.startsWith(":") },
            "пустой список -- нет и строки"
        )
    }

    out += DevTest("dpi/byedpi-extra", DevCategory.DPI, "Хвост своих аргументов идёт после пресета") {
        val config = ByeDpiConfig(extraArgs = "  --ttl 5\t--timeout 3 ")
        checkEq(listOf("--ttl", "5", "--timeout", "3"), config.extraList(), "разбор хвоста")
        val args = config.toArgs()
        // После пресета: поздний флаг перекрывает ранний, иначе хвост
        // был бы мёртвым грузом после `--disorder 1`.
        checkTrue(
            args.indexOf("--ttl") > args.indexOf("--disorder"),
            "хвост после пресета"
        )
        checkEq("5", args.getOrNull(args.indexOf("--ttl") + 1), "значение хвоста")
        checkTrue(ByeDpiConfig(extraArgs = "   ").extraList().isEmpty(), "пробелы -- не аргументы")
    }

    out += DevTest("dpi/backend-of", DevCategory.DPI, "Выбор механизма: коды и дефолт") {
        DpiBackend.entries.forEach { backend ->
            checkEq(backend, DpiBackend.of(backend.code), backend.code)
            checkEq(backend, DpiBackend.of(" ${backend.code.uppercase()} "), backend.code)
        }
        checkEq(DpiBackend.RELAY, DpiBackend.of("не то"), "неизвестный -- релей")
        checkEq(DpiBackend.RELAY, DpiBackend.of(null), "null -- релей")
        checkEq(DpiBackend.BYEDPI, DpiBackend.of(DpiBackend.BYEDPI.code), "стабильный код")
    }

    out += DevTest("dpi/overlay-backend", DevCategory.DPI, "DpiOverlay принимает механизм из конфига") {
        DpiOverlay.updateByeDpi(DpiBackend.BYEDPI, ByeDpiConfig(strategy = "fake"))
        checkEq(DpiBackend.BYEDPI, DpiOverlay.backend, "бэкенд принят")
        checkEq("fake", DpiOverlay.byeDpi.strategy, "стратегия принята")
        // Возврат к дефолту обязателен: сьюты общий объект, состояние
        // после теста должно быть таким, каким его ждут остальные.
        DpiOverlay.updateByeDpi(DpiBackend.RELAY, ByeDpiConfig())
        checkEq(DpiBackend.RELAY, DpiOverlay.backend, "релей -- обратно")
    }
}
