package xyz.azraellab.zapp.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Счётчики и проба туннеля из clash_api sing-box'а.
 *
 * Единственный источник, не зависящий ни от root, ни от прав на /proc:
 * сам box владеет tun и отдаёт и накопленные байты, и замер через узел на
 * `127.0.0.1:<port>`. Разбор -- чистые функции (тестируются), сами запросы
 * живут в jvmShared.
 */
data class ClashTraffic(val up: Long, val down: Long)

/** Разбор ответов clash_api; null, если тело непохоже на ожидаемое. */
object ClashStats {

    /** JSON только для структуры `/proxies`: числа по-прежнему ловит [field]. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Типы групп, на которые ссылаются правила маршрутизации.
     *
     * Именно через них уходит трафик, поэтому начинаем выбор с них, а не с
     * `GLOBAL`: глобальный селектор -- понятие clash-клиента, и в конфиге
     * без него (одиночный узел) его просто нет.
     */
    private val ACTIVE_GROUP_TYPES = setOf("URLTest", "Selector", "LoadBalance")

    /**
     * Типы записей, по которым `/delay` не меряет: у `Fallback` замерено
     * `404 Resource not found` за 7 мс, остальные идут мимо прокси вовсе.
     */
    private val SERVICE_TYPES = setOf(
        "Fallback", "Direct", "Compatible", "Reject", "RejectDrop", "DNS"
    )

    /** Служебные теги: замер по ним не означает ничего о туннеле. */
    private val META_NAMES = setOf(
        "GLOBAL", "DIRECT", "REJECT", "REJECT-DROP", "BLOCK", "PASS",
        "COMPATIBLE", "DNS"
    )

    /**
     * Сколько раз можно пройти по `now`, пока не выйдем на цель.
     *
     * Две-три -- обычный случай (группа -> узел); цикл из перекрёстных
     * указателей не должен был бы и случиться, но граница нужна, чтобы
     * сломанная топология вела к последнему известному имени, а не к
     * зависанию.
     */
    private const val MAX_PROBE_HOPS = 4

    /**
     * Накопительные байты из clash_api.
     *
     * У двух эндпоинтов разные имена полей: `/connections` отдаёт
     * `downloadTotal`/`uploadTotal`, `/traffic` -- `up`/`down`. В текущей
     * сборке sing-box второй **всегда отдаёт нули**, поэтому читается
     * первый; оба имени поддерживаются, чтобы парсер работал с обоими.
     */
    fun parse(body: String): ClashTraffic? {
        val down = field(body, "downloadTotal") ?: field(body, "down") ?: return null
        val up = field(body, "uploadTotal") ?: field(body, "up") ?: return null
        if (up < 0 || down < 0) return null
        return ClashTraffic(up, down)
    }

    /**
     * Задержка из ответа `/proxies/<tag>/delay`.
     *
     * null -- замер не прошёл: бокс не отвечает, туннель не пропускает
     * трафик или узел мёртв. Тогда в теле лежит `{"message":…}` вместо
     * `{"delay":…}`, и парсер честно отдаёт null.
     */
    fun delay(body: String): Int? {
        val value = field(body, "delay") ?: return null
        // Разумные границы: отрицательного времени не бывает, а больше
        // десяти минут -- это уже не замер, а зависший запрос.
        return if (value in 0..600_000L) value.toInt() else null
    }

    /**
     * Цель пробы: тело `GET /proxies`.
     *
     * Подставлять адрес наугад нельзя. Замерено на устройстве: с одним
     * активным профилем `/proxies` отдаёт три записи (`GLOBAL`, узел,
     * `Direct`), группы `auto` не существует вовсе -- и проба на
     * захардкоженный тег отвечала `404 Resource not found` за доли секунд.
     *
     * Путь -- от группы, выбранной правилами, по её `now` к конкретному
     * узлу. Если указателя нет (группа ещё ничего не выбирала), цель --
     * **сама группа**: трафик пойдёт именно через неё, и проба по ней
     * честна.
     *
     * Но не любая запись умеет отвечать на `/delay`. Замерено на устройстве:
     * группа `GLOBAL` в одиночном конфиге имеет тип `Fallback`, и запрос по
     * ней отвечает `404 Resource not found` за **7 мс**, тогда как тот же
     * запрос по реальному узлу отрабатывает полные 8 с (`504 Timeout`).
     * Прежняя версия возвращала `GLOBAL`, когда у того не было выбора, --
     * и сторож на живом туннеле читал мгновенный 404 как смерть сети,
     * ронял подключение через 15 с после старта. Это режим по умолчанию,
     * то есть ломалось большинство подключений. Поэтому дошли по цепочке
     * до конца и, если там оказалась служебная запись, берём реальный узел.
     */
    fun probeTarget(body: String): String? {
        val proxies = runCatching {
            json.parseToJsonElement(body).jsonObject["proxies"]?.jsonObject
        }.getOrNull() ?: return null
        if (proxies.isEmpty()) return null

        val types = HashMap<String, String>(proxies.size)
        val selected = HashMap<String, String>(proxies.size)
        for ((name, element) in proxies) {
            val node = element as? JsonObject ?: continue
            (node["type"] as? JsonPrimitive)?.content?.let { types[name] = it }
            // Пустой указатель не сохраняем отдельным случаем: у группы
            // без выбора цепочка обрывается на ней самой (см. ниже).
            (node["now"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?.let { selected[name] = it }
        }

        // Порядок ключей -- порядок ответа API, то есть стабильный.
        var cursor: String = types.entries.firstOrNull { it.value in ACTIVE_GROUP_TYPES }?.key
            ?: selected["GLOBAL"]
            ?: proxies.keys.firstOrNull { selected.containsKey(it) }
            ?: proxies.keys.firstOrNull()
            ?: return null
        var hops = 0
        while (hops <= MAX_PROBE_HOPS) {
            val next: String = selected[cursor] ?: break
            if (next == cursor) break
            cursor = next
            hops++
        }
        if (isProberable(cursor, types[cursor])) return cursor
        return proxies.keys.firstOrNull { isProberable(it, types[it]) }
    }

    /**
     * Запись, по которой `/delay` вообще отвечает на замер.
     *
     * Служебные теги (`GLOBAL` и прочие) и типы без собственного выхода
     * (`Fallback`, `Direct`, `Reject`...) -- не цель: по ним замер либо
     * бесполезен, либо -- как у `Fallback` -- отвечает 404 быстрее, чем
     * успеваешь моргнуть.
     */
    private fun isProberable(name: String, type: String?): Boolean {
        val kind = type ?: return false
        return name.uppercase() !in META_NAMES && kind !in SERVICE_TYPES
    }

    /** Значение числового поля: `"up": 123` или `"up":123`. */
    private fun field(body: String, key: String): Long? {
        val marker = "\"$key\""
        val idx = body.indexOf(marker)
        if (idx < 0) return null
        val rest = body.substring(idx + marker.length)
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        var start = colon + 1
        while (start < rest.length && rest[start].isWhitespace()) start++
        val comma = rest.indexOf(',', start)
        val brace = rest.indexOf('}', start)
        val end = when {
            comma >= 0 && brace >= 0 -> minOf(comma, brace)
            comma >= 0 -> comma
            brace >= 0 -> brace
            else -> rest.length
        }
        if (end <= start) return null
        return rest.substring(start, end).trim().toLongOrNull()
    }
}

/**
 * Текущий счётчик туннеля из clash_api sing-box'а.
 *
 * null, если api выключен, box не запущен или не отвечает: тикер в этом
 * случае падает на интерфейсные счётчики.
 */
expect suspend fun fetchClashTraffic(port: Int, secret: String, timeoutMs: Int): ClashTraffic?

/**
 * Проба **через** туннель: `GET /proxies/<цель>/delay?url=…&timeout=…`.
 *
 * Цель находится сама из `GET /proxies`: в конфиге может не быть группы
 * `auto` (она добавляется, только если узлов больше одного), и адрес
 * подставлять вслепую нельзя -- замерено, что в таком случае API отвечает
 * `404 Resource not found`, и проба выглядела бы сбоем на исправной сети.
 *
 * Ответ -- задержка в миллисекундах, которые box снял, пропустив запрос
 * к [url] через узел. Это единственный способ убедиться, что туннель
 * работает: счётчики при простое не растут (health-check'ы urltest сами
 * по себе ничего не прощупывают), а пинг в плашке меряет путь **в обход**
 * туннеля и о нём ничего не знает.
 *
 * [probeTimeoutMs] -- бюджет самого узла; на чтение ответа отводится
 * заметно больше, иначе клиент обрывал бы ровно на границе бюджета и
 * получал таймаут вместо медленного, но честного ответа.
 *
 * null -- замер не прошёл: не нашлось ни одной цели, бокс не отвечает,
 * узел мёртв или туннель не пропускает трафик. Сторож трактует это
 * как сбой подключения.
 */
expect suspend fun fetchClashProbe(
    port: Int,
    secret: String,
    url: String,
    probeTimeoutMs: Int
): Int?

/**
 * Сегмент пути clash_api: всё вне RFC 3986 `unreserved` -- в %XX.
 *
 * Обязателен **побайтовый** обход. Имена узлов -- юникод (эмодзи стран),
 * а посимвольный проход рассыпал бы пары суррогатов на половинки: каждая
 * ушла бы в кодировщик отдельно и превратилась в мусор. Замерено на
 * устройстве: цель `🇳🇱 The Netherlands, Haarlem | [BL]` в таком виде не
 * находилась, и проба отвечала `404 Resource not found` за 160 мс вместо
 * замера за восемь секунд -- то есть сторож ронял бы здоровый туннель.
 */
internal fun encodePath(value: String): String = percentEncode(value) { false }

/** То же для значения query-параметра: пробел и `+` обязаны уйти в %XX. */
internal fun encodeQuery(value: String): String =
    percentEncode(value) { c -> c == ' ' || c == '+' }

private fun percentEncode(value: String, alsoEncoded: (Char) -> Boolean): String {
    val out = StringBuilder(value.length * 3)
    for (raw in value.toByteArray(Charsets.UTF_8)) {
        val byte = raw.toInt() and 0xFF
        val unreserved = byte in 0x41..0x5A || byte in 0x61..0x7A || byte in 0x30..0x39 ||
            byte == 0x2D || byte == 0x2E || byte == 0x5F || byte == 0x7E
        // Нестрочные байты в предикат не попадают: там ждут ASCII-символ.
        if (unreserved && !alsoEncoded(byte.toChar())) {
            out.append(byte.toChar())
            continue
        }
        out.append('%')
        out.append(((byte shr 4) and 0xF).toString(16).uppercase())
        out.append((byte and 0xF).toString(16).uppercase())
    }
    return out.toString()
}

/**
 * Тело ответа clash_api: заголовки от тела, chunked снимается, **тело -- UTF-8**.
 *
 * Обязательно побайтовое разделение. Раньше ответ читался целиком как
 * ISO-8859-1, и юникод в JSON ломался: тег узла `🇳🇱 The Netherlands…`
 * превращался в строку из латинских байтов, `encodePath` кодировал их
 * заново, и путь не совпадал с тегом сервера. Замерено на устройстве:
 * `404 Resource not found` за миллисекунды на живом узле. В group-режиме
 * баг прятался -- там цель `auto`, чистый ASCII.
 *
 * null -- пустой ответ: читать такие тела нечего.
 */
internal fun decodeClashBody(raw: ByteArray): String? {
    val split = headerEnd(raw)
        ?: return raw.decodeToString().trim().takeIf { it.isNotBlank() }
    val headers = raw.decodeToString(0, split)
    val payload = raw.copyOfRange(split + 4, raw.size)
    val bytes = if (headers.contains("chunked", ignoreCase = true)) dechunkBytes(payload) else payload
    return bytes.decodeToString().takeIf { it.isNotBlank() }
}

/** Строка протокола ответа: `HTTP/1.1 404 Not Found` -> 404; 0, если её нет. */
internal fun clashStatus(raw: ByteArray): Int {
    val split = headerEnd(raw) ?: return 0
    val firstLine = raw.decodeToString(0, split).lineSequence().firstOrNull() ?: return 0
    return firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
}

/** Граница `\r\n\r\n`, то есть конец заголовков; null, если её нет. */
internal fun headerEnd(raw: ByteArray): Int? {
    for (i in 0..raw.size - 4) {
        if (raw[i] == CRLF && raw[i + 1] == LF && raw[i + 2] == CRLF && raw[i + 3] == LF) return i
    }
    return null
}

private const val CRLF: Byte = 13
private const val LF: Byte = 10

/** Transfer-Encoding: chunked на байтах: размер шестнадцатеричный, payload -- UTF-8. */
internal fun dechunkBytes(body: ByteArray): ByteArray {
    val out = ByteArray(body.size)
    var written = 0
    var at = 0
    while (at < body.size) {
        var lineEnd = at
        while (lineEnd + 1 < body.size && !(body[lineEnd] == CRLF && body[lineEnd + 1] == LF)) lineEnd++
        if (lineEnd + 1 >= body.size) break
        val size = body.decodeToString(at, lineEnd).trim().substringBefore(';').toIntOrNull(16) ?: return body
        if (size <= 0) break
        val start = lineEnd + 2
        if (start + size > body.size) break
        body.copyInto(out, written, start, start + size)
        written += size
        at = start + size + 2
    }
    return if (written == out.size) out else out.copyOf(written)
}