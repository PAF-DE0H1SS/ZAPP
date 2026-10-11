package xyz.azraellab.zapp.core.net

/**
 * Автоподбор DNS-сервера туннеля.
 *
 * Оператор может резать или подменять UDP/53 к чужим резолверам, и ядро,
 * стартуя с заведомо мёртвого адреса, не разрешит адреса своих исходящих
 * подключений -- туннель поднимется, но молча умрёт на первом же домене.
 * Поэтому при пустом списке DNS пользователя перед подключением серверы
 * перебираются настоящим DNS-запросом: выжил тот, кто ответил на свой же
 * вопрос.
 *
 * Здесь -- только чистая логика: конструктор запроса, проверка ответа,
 * порядок кандидатов и выбор победителя. Сеть живёт в platform-реализации
 * [probeDns], вся она покрыта dev-тестами (`DnsFailoverSuite`).
 */
object DnsFailover {

    /**
     * Кандидаты по умолчанию, если пользователь не задал своих.
     *
     * Порядок -- не случайный: сперва общедоступные
     * локальные для региона использования (Яндекс), затем запасные
     * зарубежные. Так «русский» вариант пробуется раньше заведомо
     * далёких.
     */
    val candidates: List<String> = listOf(
        "8.8.8.8",
        "1.1.1.1",
        "77.88.8.8",
        "9.9.9.9",
        "208.67.222.222"
    )

    /** Имя для пробы: живой домен с длинным TTL, ответ -- всегда A. */
    const val probeName: String = "example.com"

    /** Таймаут одной пробы; весь перебор -- параллельно, это максимум ожидания. */
    const val probeTimeoutMs: Int = 2000

    /**
     * Байты стандартного DNS-запроса A/IN с RD.
     *
     * Ровно тот минимум, который ждёт любой резолвер: заголовок с флагом
     * рекурсии, один вопрос, имя в label-кодировании, тип A, класс IN.
     * EDNS не нужен -- пробе важен сам факт ответа, а не размер буфера.
     */
    fun queryBytes(name: String, id: Int): ByteArray {
        val labels = name.trim().trim('.').split('.').filter { it.isNotEmpty() }
        val qname = labels.flatMap { label ->
            listOf(label.length.toByte()) + label.encodeToByteArray().toList()
        } + listOf(0)
        val header = byteArrayOf(
            (id shr 8).toByte(), id.toByte(), // ID
            0x01, 0x00,                       // flags: RD
            0x00, 0x01,                       // QDCOUNT = 1
            0x00, 0x00,                       // ANCOUNT
            0x00, 0x00,                       // NSCOUNT
            0x00, 0x00                        // ARCOUNT
        )
        val question = byteArrayOf(
            0x00, 0x01, // QTYPE = A
            0x00, 0x01  // QCLASS = IN
        )
        return header + qname + question
    }

    /**
     * Это DNS-ответ на наш запрос с кодом NOERROR.
     *
     * Проверяется ровно три признака и ничего больше: ID совпадает (наш
     * запрос), QR=1 (ответ, а не эхо), RCODE=0 (домен разрешился).
     * Наличие самой A-записи не проверяется -- для пробы «резолвер жив»
     * важен ответ, а не конкретный адрес.
     */
    fun responseOk(packet: ByteArray?, id: Int): Boolean {
        if (packet == null || packet.size < 12) return false
        val gotId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        if (gotId != (id and 0xFFFF)) return false
        if ((packet[2].toInt() and 0x80) == 0) return false
        return (packet[3].toInt() and 0x0F) == 0
    }

    /** Первый живой кандидат в порядке списка; null -- никто не ответил. */
    fun pick(probes: List<Pair<String, Boolean>>): String? =
        probes.firstOrNull { it.second }?.first

    /**
     * Кандидаты к пробе: явный список пользователя (или пусто) иначе
     * дефолтный порядок. Дубли схлопываются, первое вхождение сохраняет
     * позицию -- приоритет пользователя выше глобального порядка.
     */
    fun candidatesFor(explicit: List<String>): List<String> {
        val cleaned = explicit.map { it.trim() }.filter { it.isNotEmpty() }
        return (cleaned + candidates).distinct()
    }
}

/**
 * Проба DNS-сервера настоящим A-запросом: true -- ответил NOERROR.
 *
 * Сервер молчит, режется или подменяет ответ -- все три случая это false,
 * и кандидат выбывает из автоподбора. Реализация -- в platform-слое.
 */
expect suspend fun probeDns(server: String, name: String, timeoutMs: Int): Boolean
