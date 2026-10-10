package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AutoStrategy
import xyz.azraellab.zapp.core.ZapretConfig
import xyz.azraellab.zapp.core.net.TlsFailure
import xyz.azraellab.zapp.core.net.TlsPhase
import xyz.azraellab.zapp.core.net.tlsFailureNote
import xyz.azraellab.zapp.core.net.tlsVerdict
import xyz.azraellab.zapp.core.probe.CandidateScore
import xyz.azraellab.zapp.core.probe.PRIORITY_PRODUCTS
import xyz.azraellab.zapp.core.probe.ProbeCandidate
import xyz.azraellab.zapp.core.probe.REQUIRED_PRODUCTS
import xyz.azraellab.zapp.core.probe.RelayTuning
import xyz.azraellab.zapp.core.probe.STRATEGY_TARGETS
import xyz.azraellab.zapp.core.probe.StrategyProbeCoordinator
import xyz.azraellab.zapp.core.probe.TargetOutcome
import xyz.azraellab.zapp.core.probe.goodbyeDpiCandidates
import xyz.azraellab.zapp.core.probe.relayCandidates
import xyz.azraellab.zapp.core.probe.socks5Greeting
import xyz.azraellab.zapp.core.probe.socks5SelectedNoAuth
import xyz.azraellab.zapp.core.probe.zapretCandidates
import xyz.azraellab.zapp.core.probe.zapretCandidates

/**
 * Кандидатный подбор стратегии: цели, скоринг и координатор.
 *
 * Здесь -- чистая логика пробы без сокетов и процессов: что перебирается,
 * как сравниваются результаты и как устроен лок. Сам перебор инстансами
 * проверяется живьём на устройстве, а скоринг обязан быть точным уже
 * в тестах: именно он решает, какая стратегия станет боевой.
 */
internal fun probeTests(out: MutableList<DevTest>) {

    // --- Цели: полнота продуктового покрытия ---

    out += DevTest("probe/targets-cover-required", DevCategory.ZAPRET, "Обязательные продукты есть среди целей") {
        val products = STRATEGY_TARGETS.map { it.product }.toSet()
        for (required in REQUIRED_PRODUCTS) {
            checkTrue(required in products, "цель для $required обязательна")
        }
    }

    out += DevTest("probe/targets-cover-priority", DevCategory.ZAPRET, "Приоритетные продукты есть среди целей") {
        val products = STRATEGY_TARGETS.map { it.product }.toSet()
        for (priority in PRIORITY_PRODUCTS) {
            checkTrue(priority in products, "цель для $priority нужна для голоса/видео")
        }
    }

    out += DevTest("probe/targets-unique", DevCategory.ZAPRET, "Цели не повторяются") {
        val hosts = STRATEGY_TARGETS.map { it.host }
        checkEq(hosts.size, hosts.distinct().size, "хосты обязаны быть уникальны")
        val products = STRATEGY_TARGETS.map { it.product }
        checkEq(products.size, products.distinct().size, "продукты не должны делить хосты")
    }

    // --- Кандидаты: что уходит в перебор ---

    out += DevTest("probe/candidates-zapret-sane", DevCategory.ZAPRET, "Кандидаты zapret без DISABLED/AUTO") {
        val ids = zapretCandidates("auto").map { it.id }
        checkTrue(ids.isNotEmpty(), "кандидаты должны быть")
        checkFalse("disabled" in ids, "выключенный обход нечего проверять")
        checkFalse("auto" in ids, "авто -- это выбор, а не кандидат")
        checkEq(ids.size, ids.distinct().size, "кандидаты без дублей")
    }

    out += DevTest("probe/candidates-zapret-head", DevCategory.ZAPRET, "Текущая стратегия zapret идёт первой") {
        val ids = zapretCandidates("fake-sni").map { it.id }
        checkEq("fake-sni", ids.firstOrNull(), "текущая стратегия должна проверяться первой")
    }

    out += DevTest("probe/candidates-gdp-sane", DevCategory.DPI, "Кандидаты goodbyedpi без DISABLED и Fake SNI") {
        val ids = goodbyeDpiCandidates("auto").map { it.id }
        checkTrue(ids.isNotEmpty(), "кандидаты должны быть")
        checkFalse("disabled" in ids, "выключенный режим нечего проверять")
        checkFalse("fake-sni" in ids, "Fake SNI без домена не стартует вообще")
        checkEq(ids.size, ids.distinct().size, "кандидаты без дублей")
    }

    out += DevTest("probe/candidates-gdp-head", DevCategory.DPI, "Текущий режим goodbyedpi идёт первой") {
        val ids = goodbyeDpiCandidates("split-pos").map { it.id }
        checkEq("split-pos", ids.firstOrNull(), "текущий режим должен проверяться первой")
    }

    out += DevTest("probe/candidates-relay-current-first", DevCategory.DPI, "Текущий тюнинг релея идёт первой") {
        val current = RelayTuning(delayMs = 50, cuts = 2)
        val ids = relayCandidates(current).map { it.id }
        checkEq("relay:${current.id}", ids.firstOrNull(), "текущий тюнинг обязан проверяться первым")
        checkTrue(ids.size >= 5, "в переборе нужен контроль и обе гаммы: $ids")
    }

    out += DevTest("probe/candidates-relay-labels", DevCategory.DPI, "Подписи кандидата релея на двух языках") {
        val candidate = relayCandidates(RelayTuning(delayMs = 10, cuts = 1)).first()
        checkContains(candidate.label(AppLang.RU), "1", "русская подпись с числом разрезов")
        checkContains(candidate.label(AppLang.RU), "10", "русская подпись с паузой")
        checkContains(candidate.label(AppLang.EN), "Split", "английская подпись")
        checkFalse(
            candidate.label(AppLang.RU).equals(candidate.label(AppLang.EN), ignoreCase = true),
            "языки обязаны отличаться"
        )
    }

    // --- Тюнинг релея: идентификатор -- объект и обратно ---

    out += DevTest("probe/relay-tuning-roundtrip", DevCategory.DPI, "RelayTuning переживает разбор идентификатора") {
        val tuning = RelayTuning(delayMs = 50, cuts = 2)
        checkEq(tuning, RelayTuning.of(tuning.id), "id должен разбираться обратно в тот же объект")
        checkEq(RelayTuning(delayMs = 0, cuts = 0), RelayTuning.of("0-0"), "нулевой тюнинг тоже разбирается")
    }

    out += DevTest("probe/relay-tuning-rejects-garbage", DevCategory.DPI, "Мусорный идентификатор тюнинга отвергается") {
        checkEq(null, RelayTuning.of("x"), "без дефолта не разобрать")
        checkEq(null, RelayTuning.of("1-2-3"), "лишние части -- не идентификатор")
        checkEq(null, RelayTuning.of("1-"), "вторая половина обязательна")
        checkEq(null, RelayTuning.of("a-b"), "числа обязательны")
    }

    out += DevTest("probe/relay-tuning-normalized", DevCategory.DPI, "Тюнинг зажимается в рабочие границы") {
        val wild = RelayTuning(delayMs = 5000, cuts = 99).normalized()
        checkEq(500, wild.delayMs, "пауза не дольше 500 мс")
        checkEq(3, wild.cuts, "разрезов не больше трёх")
        val negative = RelayTuning(delayMs = -5, cuts = -1).normalized()
        checkEq(0, negative.delayMs, "минусовая пауза не бывает")
        checkEq(0, negative.cuts, "минусовых разрезов не бывает")
    }

    // --- Скоринг: порядок сравнения ---

    fun score(
        latencies: Map<String, Long?>,
        udpOk: Boolean = false
    ) = CandidateScore(
        outcomes = STRATEGY_TARGETS.map { target ->
            TargetOutcome(target, latencies[target.product])
        },
        udpOk = udpOk
    )

    val full = score(
        mapOf(
            "discord-core" to 100L,
            "youtube-core" to 120L,
            "discord-gateway" to 90L,
            "discord-media" to 95L,
            "telegram" to 80L
        ),
        udpOk = true
    )

    out += DevTest("probe/score-full-beats-partial", DevCategory.ZAPRET, "Полный успех важнее частичного") {
        val partial = score(mapOf("discord-core" to 50L, "youtube-core" to 60L))
        checkTrue(full.isBetterThan(partial), "полное покрытие обязано побеждать")
        checkFalse(partial.isBetterThan(full), "и обратное сравнение строго в обратную сторону")
    }

    out += DevTest("probe/score-eligible-first", DevCategory.ZAPRET, "Обязательные продукты важнее остального") {
        // Без youtube-core зато весь приоритет и udp: всё равно проигрывает.
        val noRequired = score(
            mapOf(
                "discord-core" to 10L,
                "discord-gateway" to 10L,
                "discord-media" to 10L,
                "telegram" to 10L
            ),
            udpOk = true
        )
        val eligible = score(mapOf("discord-core" to 300L, "youtube-core" to 300L))
        checkTrue(eligible.isBetterThan(noRequired), "eligible должен важнее широты")
        checkFalse(noRequired.isBetterThan(eligible), "без обязательных -- нет выигрыша")
    }

    out += DevTest("probe/score-priority-wins", DevCategory.ZAPRET, "Приоритетные продукты решают при равенстве") {
        val withPriority = score(
            mapOf("discord-core" to 100L, "youtube-core" to 100L, "discord-gateway" to 100L)
        )
        val without = score(
            mapOf("discord-core" to 100L, "youtube-core" to 100L, "telegram" to 100L)
        )
        checkTrue(withPriority.isBetterThan(without), "gateway важнее телеграма")
    }

    out += DevTest("probe/score-udp-wins", DevCategory.ZAPRET, "QUIC-медиа решает при равном покрытии") {
        val withUdp = score(
            mapOf("discord-core" to 100L, "youtube-core" to 100L),
            udpOk = true
        )
        val withoutUdp = score(
            mapOf("discord-core" to 100L, "youtube-core" to 100L),
            udpOk = false
        )
        checkTrue(withUdp.isBetterThan(withoutUdp), "udpOk -- преимущество")
        checkFalse(withoutUdp.isBetterThan(withUdp), "udpOk не может проигрывать")
    }

    out += DevTest("probe/score-latency-breaks-tie", DevCategory.ZAPRET, "Скорость -- последний рубеж") {
        val fast = score(mapOf("discord-core" to 50L, "youtube-core" to 50L))
        val slow = score(mapOf("discord-core" to 500L, "youtube-core" to 500L))
        checkTrue(fast.isBetterThan(slow), "меньшая сумма latency выигрывает")
        checkFalse(fast.isBetterThan(fast), "равный себе не лучше себя")
        checkTrue(fast.isBetterThan(null), "любой результат лучше отсутствия")
    }

    out += DevTest("probe/score-summary", DevCategory.ZAPRET, "Короткая строка итога") {
        checkEq("5/7, udp", full.summary(), "успехи/всего плюс udp")
        val plain = score(mapOf("discord-core" to 10L))
        checkEq("1/7", plain.summary(), "без udp -- без суффикса")
    }

    // --- Координатор: лок и прогресс ---

    out += DevTest("probe/coordinator-lock", DevCategory.ZAPRET, "Повторный запуск пробы отсекается локом") {
        checkTrue(StrategyProbeCoordinator.begin(3), "первый заход обязан брать лок")
        checkFalse(StrategyProbeCoordinator.begin(3), "второй -- обязан упираться в лок")
        StrategyProbeCoordinator.finish()
        checkTrue(StrategyProbeCoordinator.begin(1), "после finish лок свободен")
        StrategyProbeCoordinator.finish()
    }

    out += DevTest("probe/coordinator-progress", DevCategory.ZAPRET, "Прогресс несёт кандидат и границы") {
        StrategyProbeCoordinator.begin(4)
        val candidate = ProbeCandidate("multipath", "Мультипуть", "Multipath")
        StrategyProbeCoordinator.tick(2, candidate)
        val progress = StrategyProbeCoordinator.progress.value
        checkTrue(progress != null, "прогресс должен быть виден")
        checkEq(3, progress?.index, "третий кандидат -- индекс с единицы")
        checkEq(4, progress?.total, "всего кандидатов")
        checkEq(candidate, progress?.candidate, "кандидат целиком")
        StrategyProbeCoordinator.finish()
        checkEq(null, StrategyProbeCoordinator.progress.value, "после finish прогресс обнуляется")
    }

    // --- Командная строка: номер очереди пробного инстанса ---

    out += DevTest("probe/zapret-queue-arg", DevCategory.ZAPRET, "toArgs подставляет номер очереди nfqws") {
        checkIn("--qnum=0", ZapretConfig().toArgs(), "боевой демон -- очередь по умолчанию")
        checkIn("--qnum=1", ZapretConfig().toArgs(queueNum = 1), "пробный инстанс -- своя очередь")
        checkIn("--qnum=7", ZapretConfig(enabled = true).toArgs(queueNum = 7), "номер произвольный")
    }

    // --- TLS: фаза сбоя решает, DPI это или мёртвый сервер ---

    out += DevTest("probe/tls-verdict-connect-unknown", DevCategory.ZAPRET, "Сбой соединения -- не «режется», а неизвестно") {
        for (failure in listOf(TlsFailure.REFUSED, TlsFailure.TIMEOUT, TlsFailure.OTHER)) {
            checkEq(
                AutoStrategy.ProbeResult.Unknown,
                tlsVerdict(TlsPhase.CONNECT, failure),
                "connect+$failure -- сервер лёг, целью обхода быть не может"
            )
        }
    }

    out += DevTest("probe/tls-verdict-handshake-blocked", DevCategory.ZAPRET, "Обрыв после ClientHello -- DPI режет") {
        for (failure in listOf(TlsFailure.SSL_ERROR, TlsFailure.TIMEOUT, TlsFailure.OTHER)) {
            checkEq(
                AutoStrategy.ProbeResult.Blocked,
                tlsVerdict(TlsPhase.HANDSHAKE, failure),
                "handshake+$failure -- классический ssl_err"
            )
        }
    }

    out += DevTest("probe/tls-verdict-edge-cases", DevCategory.ZAPRET, "Платформа и refused на handshake -- не блокировка") {
        checkEq(
            AutoStrategy.ProbeResult.Unknown,
            tlsVerdict(TlsPhase.HANDSHAKE, TlsFailure.PROTOCOL_UNSUPPORTED),
            "нет TLS 1.3 -- про платформу, не про сеть"
        )
        checkEq(
            AutoStrategy.ProbeResult.Unknown,
            tlsVerdict(TlsPhase.CONNECT, TlsFailure.PROTOCOL_UNSUPPORTED),
            "платформа важнее фазы"
        )
        checkEq(
            AutoStrategy.ProbeResult.Unknown,
            tlsVerdict(TlsPhase.HANDSHAKE, TlsFailure.REFUSED),
            "refused на handshake -- не обрыв записи"
        )
    }

    out += DevTest("probe/tls-failure-note", DevCategory.ZAPRET, "Заметка причины читается и не пуста") {
        checkEq("connect: refused", tlsFailureNote(TlsPhase.CONNECT, TlsFailure.REFUSED), "подпись connect-фазы")
        checkEq("handshake: ssl error", tlsFailureNote(TlsPhase.HANDSHAKE, TlsFailure.SSL_ERROR), "подпись handshake-фазы")
    }

    // --- SOCKS5: готовность релея по протоколу ---

    out += DevTest("probe/socks5-greeting", DevCategory.ZAPRET, "Приветствие -- версия 5, один метод no-auth") {
        checkEq(listOf<Byte>(0x05, 0x01, 0x00), socks5Greeting().toList(), "байты приветствия")
    }

    out += DevTest("probe/socks5-select-parse", DevCategory.ZAPRET, "Выбор no-auth распознаётся, остальное -- нет") {
        checkTrue(socks5SelectedNoAuth(byteArrayOf(0x05, 0x00)), "05 00 -- готов")
        checkFalse(socks5SelectedNoAuth(byteArrayOf(0x05, 0x02)), "05 02 -- метод не выбран")
        checkFalse(socks5SelectedNoAuth(byteArrayOf(0x05)), "один байт -- не ответ")
        checkFalse(socks5SelectedNoAuth(null), "нет ответа -- не готов")
        checkFalse(socks5SelectedNoAuth(byteArrayOf(0x00, 0x00)), "не SOCKS5")
    }
}
