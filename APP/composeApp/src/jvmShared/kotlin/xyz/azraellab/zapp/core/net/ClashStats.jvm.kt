package xyz.azraellab.zapp.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.azraellab.zapp.core.log.AppLog

/*
 * Запросы к запущенному sing-box через clash_api.
 *
 * loopback доступен приложению всегда: это его собственный процесс, tun его
 * не касается. Голый сокет вместо `httpGet`: clash_api слушает только
 * `http://`, а с targetSdk 28+ cleartext запрещён политикой сети (network
 * security config), и для loopback `HttpURLConnection` в приложении падал
 * на этой проверке молча, часами не доходя до таймаута. Политика к голым
 * сокетам не относится, поэтому здесь и сокет, а не HTTP-клиент.
 *
 * Тела конечны и читаются целиком, `Connection: close` избавляет от
 * разбора Content-Length. Всё, что понимает структуру ответа, -- чистые
 * функции [ClashStats]; здесь только транспорт.
 */

/** Бюджет на поиск цели в `/proxies`: локальный запрос, ответ обязан быть быстрым. */
private const val DISCOVERY_TIMEOUT_MS = 4_000

/**
 * Запас клиента над бюджетом самого узла.
 *
 * Узлу уходит свой `timeout`, и ответ приходит ровно на его границе.
 * Клиент, ограниченный тем же числом, оборвал бы чтение в тот же момент
 * и получил таймаут вместо медленного, но честного замера.
 */
private const val SOCKET_HEADROOM_MS = 6_000

/**
 * Итоги туннеля: `GET /connections`.
 *
 * `/traffic` здесь не берётся сознательно: в текущей сборке sing-box он
 * отвечает всегда нулевыми счётчиками, и по ним UI показывал бы и скорость
 * 0, и объём 0 при живом трафике. Правда в том, что у `/connections`
 * сначала идёт весь список сессий и только потом `downloadTotal`/
 * `uploadTotal`, то есть дочитывать приходится до конца.
 */
actual suspend fun fetchClashTraffic(
    port: Int,
    secret: String,
    timeoutMs: Int
): ClashTraffic? = withContext(Dispatchers.IO) {
    val p = port.coerceIn(1, 65535)
    runCatching { getJson(p, secret, "/connections", timeoutMs) }.getOrNull()
        ?.let(ClashStats::parse)
}

/**
 * Проба через туннель: цель из `/proxies`, затем её `/delay`.
 *
 * Два вызова вместо одного -- цена того, что цель нельзя подставлять
 * вслепую: в одиночном конфиге группы `auto` не существует, и замер на неё
 * отвечает `404 Resource not found`, что на экране читалось бы как сбой
 * исправной сети. Теги -- юникодные (узлы названы странами), поэтому
 * сегмент пути процент-кодируется побайтово в UTF-8.
 *
 * Ответ -- либо `{"delay":3936}`, либо `{"message":"…"}` с ошибкой замера;
 * в обоих случаях парсер отдаёт null или число, лишних состояний нет.
 */
actual suspend fun fetchClashProbe(
    port: Int,
    secret: String,
    url: String,
    probeTimeoutMs: Int
): ClashProbe? = withContext(Dispatchers.IO) {
    val p = port.coerceIn(1, 65535)
    val list = runCatching { getJson(p, secret, "/proxies", DISCOVERY_TIMEOUT_MS) }.getOrNull()
    if (list == null) {
        AppLog.log("vpn", "watchdog: /proxies did not answer")
        return@withContext null
    }
    val target = ClashStats.probeTarget(list)
    if (target == null) {
        AppLog.log("vpn", "watchdog: no probe target in ${list.length} bytes")
        return@withContext null
    }
    val probe = probeVia(p, secret, target, url, probeTimeoutMs)
    // Цель и ответ обязательны в журнале: мгновенный отказ -- это обычно
    // не сбой сети, а 404 от записи, по которой `/delay` не меряет, и без
    // этих двух полей причину приходится отгадывать по косвенным признакам.
    AppLog.log(
        "vpn",
        "watchdog: probe via $target -> http=${probe.status} ${probe.delay?.let { "${it}ms" } ?: probe.body?.take(60)}"
    )
    probe.delay?.let { ClashProbe(target, it) }
}

/**
 * Роспись: участники активной группы из `GET /proxies` (A3).
 *
 * Один локальный запрос на тик; дальше тикер ходит по списку сам.
 */
actual suspend fun fetchClashRoster(
    port: Int,
    secret: String,
    timeoutMs: Int
): List<String>? = withContext(Dispatchers.IO) {
    val p = port.coerceIn(1, 65535)
    val list = runCatching { getJson(p, secret, "/proxies", timeoutMs) }.getOrNull() ?: return@withContext null
    ClashStats.probeRoster(list)
}

/**
 * Замер **одного** узла росписи (A3): цель уже известна, `/proxies` за
 * ней не ходим. Молчит о провалах: мёртвый узел в пачке -- норма, и
 * строка на каждый такой узел захлебнула бы журнал. Итог пачки пишет
 * вызывающий тикер.
 */
actual suspend fun fetchClashNodeProbe(
    port: Int,
    secret: String,
    tag: String,
    url: String,
    probeTimeoutMs: Int
): ClashProbe? = withContext(Dispatchers.IO) {
    val p = port.coerceIn(1, 65535)
    probeVia(p, secret, tag, url, probeTimeoutMs).delay?.let { ClashProbe(tag, it) }
}

/** Ответ `/delay` одной цели: код, тело и снятая задержка. */
private class ProbeReply(val delay: Int?, val status: Int, val body: String?)

/** `GET /proxies/<target>/delay` на готовом порту: транспорт без выбора цели. */
private suspend fun probeVia(
    port: Int,
    secret: String,
    target: String,
    url: String,
    probeTimeoutMs: Int
): ProbeReply {
    val path = "/proxies/${encodePath(target)}/delay?url=${encodeQuery(url)}&timeout=$probeTimeoutMs"
    val reply = runCatching {
        request(port, secret, path, probeTimeoutMs + SOCKET_HEADROOM_MS)
    }.getOrElse { return ProbeReply(null, 0, it::class.simpleName) }
    return ProbeReply(ClashStats.delay(reply.body.orEmpty()), reply.status, reply.body)
}

/** Ответ clash_api: тело и код статуса (0 -- соединение не состоялось). */
private class Reply(val body: String?, val status: Int)

/** Один GET. Тело -- null при пустом ответе; статус -- из строки протокола. */
private fun request(port: Int, secret: String, path: String, timeoutMs: Int): Reply =
    java.net.Socket().use { socket ->
        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            if (secret.isNotBlank()) append("Authorization: Bearer $secret\r\n")
            append("Accept: application/json\r\n")
            append("Accept-Encoding: identity\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
        val raw = socket.getInputStream().readBytes()
        // Байты, а не строка: заголовки ASCII, но тело -- UTF-8, и чтение
        // его как ISO-8859-1 ломало эмодзи в тегах узлов (см. decodeClashBody).
        Reply(decodeClashBody(raw), clashStatus(raw))
    }

/** Тело ответа, null при любом сбое или пустом ответе. */
private fun getJson(port: Int, secret: String, path: String, timeoutMs: Int): String? =
    runCatching { request(port, secret, path, timeoutMs).body }.getOrNull()

