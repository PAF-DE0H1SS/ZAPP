package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.GoodbyeDpiConfig
import xyz.azraellab.zapp.core.GoodbyeDpiMode

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
}
