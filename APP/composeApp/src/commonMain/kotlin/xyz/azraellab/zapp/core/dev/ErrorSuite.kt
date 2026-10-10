package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.daemon.DaemonEvent
import xyz.azraellab.zapp.core.gps.GpsEvent
import xyz.azraellab.zapp.core.log.ErrorLog
import xyz.azraellab.zapp.core.log.capLines
import xyz.azraellab.zapp.core.log.causeChain
import xyz.azraellab.zapp.core.log.diagnosisLine
import xyz.azraellab.zapp.core.log.errorEvent
import xyz.azraellab.zapp.core.log.errorLine
import xyz.azraellab.zapp.core.log.isError
import xyz.azraellab.zapp.core.log.looksLikeErrorLine
import xyz.azraellab.zapp.core.log.redactSensitive

/**
 * Причины сбоя в журналах.
 *
 * Состояние ERROR без показанной причины -- «голая ошибка», и именно эти
 * функции решают, какая строка попадёт под статус. Здесь проверяется выбор
 * строки: показ и перевод -- дело UI, но выбирается строка одинаково везде.
 */
internal fun errorTextTests(out: MutableList<DevTest>) {

    // --- Признаки ошибки в сырой строке ---

    out += DevTest("log/error-line-matches", DevCategory.UI, "Явные признаки ошибки распознаются") {
        listOf(
            "error: tor failed to start: Exit code 1",
            "Oct 05 12:34 [err] Reading config failed--see warnings above.",
            "Oct 05 12:34 [warn] Directory /data cannot be read: Permission denied",
            "nfq_create_queue(): Operation not permitted",
            "box fatal error: failed to listen",
            "socks port 9050 busy: address already in use",
            "unknown option --foo"
        ).forEach { line ->
            checkTrue(looksLikeErrorLine(line), "не распознана ошибка: $line")
        }
    }

    out += DevTest("log/error-line-ignores-plain", DevCategory.UI, "Обычные строки -- не ошибка") {
        listOf(
            "connecting",
            "box is running",
            "Oct 05 12:34 [notice] Tor 0.4.9.13 running on Linux",
            "note: socks port 9050 held by orphan tor; killing it",
            ""
        ).forEach { line ->
            checkFalse(looksLikeErrorLine(line), "ложная ошибка: $line")
        }
    }

    // --- Журнал туннеля ---

    out += DevTest("log/tunnel-prefers-error", DevCategory.UI, "Причина туннеля -- строка ошибки, а не последняя") {
        val log = listOf(
            "connecting",
            "error: tor failed to start: [err] Reading config failed",
            "note: cleanup done"
        )
        checkContains(log.errorLine() ?: "", "error: tor failed", "выбрана не ошибка")
    }

    out += DevTest("log/tunnel-fallback-last", DevCategory.UI, "Без строки ошибки берётся последняя непустая") {
        val log = listOf("connecting", "cleanup", "")
        checkEq("cleanup", log.errorLine(), "fallback")
        checkEq(null, emptyList<String>().errorLine(), "пустой журнал")
    }

    // --- Журнал демона ---

    out += DevTest("log/daemon-raw-error-over-exit", DevCategory.UI, "Сырой вывод важнее события «Exit code»") {
        // Ровно та последовательность, что падала без root: заметки tor'а,
        // строка с причиной и только потом событие о выходе.
        val log = listOf(
            DaemonEvent(text = Str.DAEMON_LOG_STARTING, arg = "tor"),
            DaemonEvent(raw = "Oct 05 [notice] Tor 0.4.9.13 running"),
            DaemonEvent(raw = "Oct 05 [warn] Directory denied"),
            DaemonEvent(raw = "Oct 05 [err] Reading config failed"),
            DaemonEvent(text = Str.DAEMON_LOG_EXIT, arg = "1")
        )
        val reason = log.errorEvent()
        checkTrue(reason != null, "причина не найдена")
        checkContains(reason!!.raw, "Reading config failed", "выбрана не причина")
    }

    out += DevTest("log/daemon-exit-as-reason", DevCategory.UI, "Без вывода причина -- код выхода") {
        val log = listOf(
            DaemonEvent(text = Str.DAEMON_LOG_STARTING, arg = "nfqws"),
            DaemonEvent(text = Str.DAEMON_LOG_EXIT, arg = "1")
        )
        val reason = log.errorEvent()
        checkEq(Str.DAEMON_LOG_EXIT, reason?.text, "событие-причина")
        checkEq("1", reason?.arg, "аргумент")
    }

    out += DevTest("log/daemon-skips-service-events", DevCategory.UI, "Служебные события -- не причина") {
        val log = listOf(
            DaemonEvent(text = Str.DAEMON_LOG_STARTING, arg = "box"),
            DaemonEvent(text = Str.DAEMON_LOG_STOPPED)
        )
        checkEq(null, log.errorEvent(), "причина из служебных строк")
    }

    out += DevTest("log/daemon-event-is-error", DevCategory.UI, "События-причины помечены как ошибка") {
        checkTrue(DaemonEvent(text = Str.DAEMON_LOG_EXIT, arg = "1").isError(), "Exit -- ошибка")
        checkTrue(DaemonEvent(raw = "fatal: cannot bind").isError(), "fatal -- ошибка")
        checkFalse(DaemonEvent(text = Str.DAEMON_LOG_RUNNING, arg = "box").isError(), "running -- не ошибка")
        checkFalse(DaemonEvent(raw = "[notice] bootstrapped 100%").isError(), "notice -- не ошибка")
    }

    // --- Журнал GPS ---

    out += DevTest("log/gps-reason-event", DevCategory.UI, "Причина сбоя GPS -- событие-причина") {
        val log = listOf(
            GpsEvent(Str.GPS_LOG_STARTING, "vpn"),
            GpsEvent(Str.GPS_LOG_NO_GATEWAY)
        )
        checkEq(Str.GPS_LOG_NO_GATEWAY, log.errorEvent()?.text, "выбрано не событие причины")
    }

    out += DevTest("log/gps-fallback-not-starting", DevCategory.UI, "Fallback GPS пропускает служебный старт") {
        val log = listOf(
            GpsEvent(Str.GPS_LOG_STARTING, "fixed"),
            GpsEvent(Str.GPS_LOG_PROVIDER)
        )
        checkEq(Str.GPS_LOG_PROVIDER, log.errorEvent()?.text, "fallback")
        checkEq(null, emptyList<GpsEvent>().errorEvent(), "пустой журнал")
    }

    out += DevTest("log/gps-event-is-error", DevCategory.UI, "Ошибки GPS помечены, служебные -- нет") {
        checkTrue(GpsEvent(Str.GPS_LOG_NO_PROVIDER).isError(), "no provider -- ошибка")
        checkTrue(GpsEvent(Str.GPS_LOG_START_FAILED, "boom").isError(), "start failed -- ошибка")
        checkFalse(GpsEvent(Str.GPS_LOG_STARTING, "fixed").isError(), "старт -- не ошибка")
        checkFalse(GpsEvent(Str.GPS_LOG_RESOLVED, "55.7, 37.6").isError(), "точка VPN -- не ошибка")
    }

    // --- Журнал ошибок: что уезжает в багрепорт ---

    out += DevTest("log/errorlog-captures-same-as-ui", DevCategory.UI, "В журнал ошибок идёт то же, что красится в красный") {
        listOf(
            "error: connect failed: refused",
            "[1728] [vpn] box fatal error: failed to listen",
            "nfq_create_queue(): Operation not permitted",
            "socks port 9050 busy: address already in use",
            "tor bootstrap timeout after 120s",
            "source GitHub: fetch failed (404)",
            "update check failed: java.net.UnknownHostException"
        ).forEach { line ->
            checkTrue(ErrorLog.captures(line), "не попало в ошибки: $line")
        }
        listOf(
            "connect requested (group=true)",
            "ticker start",
            "ping=45",
            "box is running",
            "Tor 0.4.9.13 running on Linux",
            ""
        ).forEach { line ->
            checkFalse(ErrorLog.captures(line), "ложная ошибка: $line")
        }
    }

    out += DevTest("log/errorlog-cap-lines", DevCategory.UI, "Буфер журнала держит хвост, а не голову") {
        val few = listOf("a", "b")
        checkEq(few, capLines(few, 10), "в пределах лимита -- без изменений")
        val many = (1..12).map { "line$it" }
        checkEq((3..12).map { "line$it" }, capLines(many, 10), "лишнее срезано с начала")
        checkEq(emptyList<String>(), capLines(emptyList(), 10), "пустой")
    }

    // --- Уровень лога важнее маркеров ---

    out += DevTest("log/error-line-level-wins", DevCategory.UI, "DEBUG-пробы urltest -- не ошибка, ERROR -- ошибка") {
        checkTrue(
            looksLikeErrorLine("+0000 2026-10-06 11:57:09 ERROR [963335102 5.1s] outbound/urltest[auto]: dial tcp 1.2.3.4:443: i/o timeout"),
            "ERROR с timeout -- ошибка"
        )
        checkFalse(
            looksLikeErrorLine("+0000 2026-10-06 12:07:52 DEBUG outbound/urltest[auto]: outbound node unavailable: dial tcp 1.2.3.4:443: i/o timeout"),
            "DEBUG-проба с timeout -- не ошибка"
        )
        checkFalse(
            looksLikeErrorLine("+0000 2026-10-06 12:07:52 INFO dns: lookup failed for example.com"),
            "INFO -- не ошибка"
        )
        checkTrue(
            looksLikeErrorLine("+0000 2026-10-06 11:57:04 WARN network: netlink unavailable: permission denied"),
            "WARN с маркером -- ошибка"
        )
        checkFalse(
            looksLikeErrorLine("+0000 2026-10-06 11:57:04 WARN network: netlink unavailable"),
            "WARN без маркера -- не ошибка"
        )
    }

    out += DevTest("log/error-line-ansi-and-noerror", DevCategory.UI, "ANSI снимается, NOERROR -- не ошибка") {
        val colored = "+0000 2026-10-06 12:07:50 \u001b[37mDEBUG\u001b[0m [2186476041 298ms] dns: exchanged api.vk.com NOERROR 838"
        checkFalse(looksLikeErrorLine(colored), "успешный DNS с цветами -- не ошибка")
        checkTrue(looksLikeErrorLine("+0000 2026-10-06 12:07:50 \u001b[31mERROR\u001b[0m create service: failed"), "цветной ERROR -- ошибка")
        checkFalse(looksLikeErrorLine("2026-10-06 12:07:50 NOERROR 838"), "голый NOERROR -- не ошибка")
    }

    out += DevTest("log/error-line-untagged-daemon-kept", DevCategory.UI, "Строки тор/openvpn без уровня решают маркеры") {
        checkTrue(looksLikeErrorLine("[warn] Problem bootstrapping Tor server (connection refused)"), "тор [warn] с маркером")
        checkFalse(looksLikeErrorLine("[notice] Bootstrapped 100% (done)"), "тор notice -- не ошибка")
        checkTrue(looksLikeErrorLine("OPTIONS ERROR: --management-query-passwords failed"), "openvpn error")
        checkFalse(looksLikeErrorLine("2026-10-06 12:07:50 INFO: using --management"), "openvpn info")
    }

    // --- Одна строка диагностики ---

    out += DevTest("log/diagnosis-line-full", DevCategory.UI, "Диагностика: время, компонент, причина") {
        val line = diagnosisLine("12:34:56", "nfqws", listOf("nfq_create_queue(): Operation not permitted"))
        checkEq("12:34:56 · nfqws · nfq_create_queue(): Operation not permitted", line, "полная строка")
    }

    out += DevTest("log/diagnosis-line-trims-and-skips", DevCategory.UI, "Пустые части диагностики пропускаются") {
        checkEq("nfqws · refused", diagnosisLine("", "nfqws", listOf("", "refused")), "без времени")
        checkEq("12:00:00", diagnosisLine("12:00:00", "", emptyList()), "без причины -- только время")
        checkEq("", diagnosisLine("", "", listOf("")), "совсем пусто")
    }

    out += DevTest("log/diagnosis-line-chain-cap", DevCategory.UI, "Причин -- не больше четырёх, без повторов") {
        val causes = listOf("a", "b", "c", "d", "e")
        val line = diagnosisLine("t", "box", causes)
        checkContains(line, "a → b → c → d", "четыре звена")
        checkNotContains(line, " → e", "пятая причина отброшена")
        val deduped = diagnosisLine("t", "box", listOf("same", "same", "other"))
        checkContains(deduped, "same → other", "дубликат схлопнут")
    }

    out += DevTest("log/diagnosis-line-redacts-tokens", DevCategory.UI, "Длинные ключи в диагностике под маской") {
        val secret = "a".repeat(44) + "="
        val line = diagnosisLine("", "box", listOf("reality private_key=$secret ok"))
        checkNotContains(line, secret, "секрет не уехал в показ")
        checkContains(line, "***", "маска на месте")
        checkEq("***", redactSensitive(secret), "голый токен маскируется целиком")
        checkContains(
            diagnosisLine("", "box", listOf("/data/local/tmp/zapp/bin/nfqws")),
            "nfqws",
            "короткие пути не трогаются"
        )
    }

    out += DevTest("log/cause-chain-walks-wrappers", DevCategory.UI, "Цепочка причин идёт по cause вглубь") {
        val root = IllegalStateException("Connection refused")
        val middle = RuntimeException("urltest dial", root)
        val outer = IllegalStateException("box start", middle)
        val chain = outer.causeChain()
        checkContains(chain, "box start", "корень цепочки")
        checkContains(chain, "urltest dial", "среднее звено")
        checkContains(chain, "Connection refused", "корневая причина")
        checkContains(chain, " → ", "звенья соединены")
    }

    out += DevTest("log/cause-chain-depth-limit", DevCategory.UI, "Цепочка причин ограничена четырьмя звеньями") {
        var deepest = IllegalStateException("level0")
        for (i in 1..6) deepest = IllegalStateException("level$i", deepest)
        val chain = deepest.causeChain()
        checkContains(chain, "level6", "корень виден")
        checkContains(chain, "level3", "граница глубины -- четвёртое звено")
        checkNotContains(chain, "level2", "пятое звено отброшено")
        checkEq("", IllegalStateException().causeChain(), "без сообщений -- пустая строка")
    }
}
