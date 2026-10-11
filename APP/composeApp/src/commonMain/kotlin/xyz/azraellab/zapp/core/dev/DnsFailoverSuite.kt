package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.net.DnsFailover

/**
 * Автозамена DNS: конструктор запроса, проверка ответа, выбор кандидата.
 *
 * Проверяется чистая математика из `core/net/DnsFailover.kt` -- та самая,
 * что решает, каким резолвером пойдёт туннель, когда свой список пуст.
 */
internal fun dnsFailoverTests(out: MutableList<DevTest>) {
    val cat = DevCategory.PARSERS

    out += DevTest("dns/query-shape", cat, "DNS-запрос: заголовок, RD, один вопрос") {
        val bytes = DnsFailover.queryBytes("example.com", 0x1234)
        checkEq(0x12, bytes[0].toInt() and 0xFF, "ID старший байт")
        checkEq(0x34, bytes[1].toInt() and 0xFF, "ID младший байт")
        checkEq(0x01, bytes[2].toInt() and 0xFF, "флаги: старший байт RD")
        checkEq(0x00, bytes[3].toInt() and 0xFF, "флаги: младший байт")
        checkEq(0x01, bytes[5].toInt() and 0xFF, "QDCOUNT = 1")
        // QNAME: label длины + имя + нулевой терминатор.
        checkEq(7, bytes[12].toInt() and 0xFF, "первый label -- 7 букв")
        checkEq('e'.code, bytes[13].toInt() and 0xFF, "первая буква имени")
        // QTYPE=A (1), QCLASS=IN (1) в самом хвосте.
        checkEq(0x01, bytes[bytes.size - 3].toInt() and 0xFF, "QTYPE = A")
        checkEq(0x01, bytes[bytes.size - 1].toInt() and 0xFF, "QCLASS = IN")
    }

    out += DevTest("dns/query-multi-label", cat, "Имя из нескольких label-ов") {
        val bytes = DnsFailover.queryBytes("a.bc", 1)
        // 12 заголовок + (1+1) + (1+2) + 1 терминатор + 4 вопрос = 22.
        checkEq(22, bytes.size, "размер: заголовок + labels + вопрос")
        checkEq(1, bytes[12].toInt() and 0xFF, "первый label -- 'a'")
        checkEq('b'.code, bytes[15].toInt() and 0xFF, "второй label начинается с 'b'")
    }

    out += DevTest("dns/response-ok", cat, "Ответ принят: наш ID, QR, NOERROR") {
        val id = 0x4321
        val response = DnsFailover.queryBytes("example.com", id).also {
            it[2] = (it[2].toInt() or 0x80).toByte() // QR = 1
        }
        checkTrue(DnsFailover.responseOk(response, id), "корректный ответ проходит")
    }

    out += DevTest("dns/response-reject", cat, "Отклонение: чужой ID, эхо, ошибка") {
        val id = 42
        val response = DnsFailover.queryBytes("example.com", id).also {
            it[2] = (it[2].toInt() or 0x80).toByte()
        }
        checkTrue(!DnsFailover.responseOk(response, 43), "чужой ID -- отказ")
        // QR=0 -- это эхо нашего запроса, а не ответ.
        val echo = DnsFailover.queryBytes("example.com", id)
        checkTrue(!DnsFailover.responseOk(echo, id), "QR=0 -- отказ")
        // RCODE=3 (NXDOMAIN) -- сервер ответил, но домен мёртв.
        response[3] = 0x03
        checkTrue(!DnsFailover.responseOk(response, id), "NXDOMAIN -- отказ")
        checkTrue(!DnsFailover.responseOk(null, id), "null -- отказ")
        checkTrue(!DnsFailover.responseOk(ByteArray(4), id), "короткий пакет -- отказ")
    }

    out += DevTest("dns/pick-first-alive", cat, "Победитель -- первый живой в порядке") {
        val probes = listOf(
            "8.8.8.8" to false,
            "1.1.1.1" to true,
            "9.9.9.9" to true
        )
        checkEq("1.1.1.1", DnsFailover.pick(probes), "первый живой, а не первый в списке")
        checkEq(
            "8.8.8.8",
            DnsFailover.pick(listOf("8.8.8.8" to true, "1.1.1.1" to true)),
            "первый выжил -- он и победитель"
        )
        checkEq(
            null,
            DnsFailover.pick(listOf("8.8.8.8" to false, "1.1.1.1" to false)),
            "никто не ответил -- null"
        )
        checkEq(null, DnsFailover.pick(emptyList()), "пусто -- null")
    }

    out += DevTest("dns/candidates-order", cat, "Явный список выше дефолта, дубли схлопнуты") {
        val candidates = DnsFailover.candidatesFor(listOf("10.0.0.1", "8.8.8.8"))
        checkEq("10.0.0.1", candidates.first(), "сервер пользователя -- первый")
        checkEq(1, candidates.count { it == "8.8.8.8" }, "дубль схлопнут")
        checkTrue(candidates.containsAll(DnsFailover.candidates), "все дефолты присутствуют")
        val defaults = DnsFailover.candidatesFor(listOf("", "  ", "1.1.1.1 "))
        checkEq("1.1.1.1", defaults.first(), "пробелы вычищены, пустые отброшены")
        checkEq(DnsFailover.candidates, DnsFailover.candidatesFor(emptyList()), "пусто -- дефолтный порядок")
    }
}
