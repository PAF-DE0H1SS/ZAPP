package xyz.azraellab.zapp.core.daemon

/**
 * Текст iptables-скрипта цепочки обхода DPI: чистая функция, чтобы
 * порядок правил (исключения -- до очереди) проверялся тестами, а не
 * живым устройством.
 *
 * Структура цепочки:
 * 1. локальные сети -- мимо;
 * 2. исключённые uid (exclude-режим) -- RETURN до очереди: пакет
 *    приложения, выведенного из обхода, не должен дойти до NFQUEUE;
 * 3. портовые правила queue (с owner-расщеплением, если заданы owners);
 * 4. прыжок OUTPUT -> цепочка (старые прыжки вычищаются все).
 *
 * Скрипт -- полная пересборка (для холодного старта и disable),
 * тела правил отдельно нужны reconcile'у: он сверяет их с живой
 * цепочкой и двигает по одной мутации, не трогая остальные.
 */
internal fun dpiFirewallScript(
    tcpPorts: List<String>,
    udpPorts: List<String>,
    owners: List<Int>,
    excludes: List<Int>
): String = buildString {
    appendLine("iptables -w -t mangle -N ZAPP_DPI 2>/dev/null || true")
    appendLine("iptables -w -t mangle -F ZAPP_DPI")
    for (line in dpiFirewallRuleLines(tcpPorts, udpPorts, owners, excludes)) {
        appendLine("iptables -w -t mangle -A ZAPP_DPI $line")
    }
    // Старые прыжки снимаются все, а не один: за одну сессию их
    // могло накопиться больше одного, и каждый лишний -- двойная
    // очередь на пакет.
    repeat(8) {
        appendLine("iptables -w -t mangle -D OUTPUT -j ZAPP_DPI 2>/dev/null || true")
    }
    appendLine("iptables -w -t mangle -A OUTPUT -j ZAPP_DPI")
}

/**
 * Тела правил цепочки (без обвязки `iptables -w -t mangle -A ZAPP_DPI`)
 * в порядке применения.
 *
 * Владельцы и исключения уникальны и отсортированы: порядок правил
 * внутри семейств не влияет на результат, а детерминированность
 * облегчает дебаг в `iptables -S ZAPP_DPI` и сверку в reconcile.
 */
internal fun dpiFirewallRuleLines(
    tcpPorts: List<String>,
    udpPorts: List<String>,
    owners: List<Int>,
    excludes: List<Int>
): List<String> {
    val tcp = tcpPorts.mapNotNull(::portToken)
    val udp = udpPorts.mapNotNull(::portToken)
    val ownerUids = owners.distinct().sorted()
    val excludeUids = excludes.distinct().sorted()
    val lines = mutableListOf<String>()

    for (net in LOCAL_NETS) {
        lines += "-d $net -j RETURN"
    }
    // Исключения строго до очереди: RETURN из цепочки прекращает
    // обход для этого пакета, поставь мы его после NFQUEUE.
    for (uid in excludeUids) {
        lines += "-m owner --uid-owner $uid -j RETURN"
    }
    // Правило одно портовое, но на владельцев расщепляется:
    // owner-match берёт ровно один uid, а пакет приходит от
    // конкретного приложения. Пустой owners -- include-фильтра нет,
    // очередь получает весь трафик порта (исключения выше уже
    // вернули свои пакеты).
    fun queue(prefix: String) {
        if (ownerUids.isEmpty()) {
            lines += "$prefix -j NFQUEUE --queue-num 0 --queue-bypass"
        } else {
            for (uid in ownerUids) {
                lines += "$prefix -m owner --uid-owner $uid -j NFQUEUE --queue-num 0 --queue-bypass"
            }
        }
    }
    if (tcp.isEmpty()) {
        queue("-p tcp")
    } else {
        for (token in tcp) {
            queue("-p tcp --dport $token")
        }
    }
    for (token in udp) {
        queue("-p udp --dport $token")
    }
    return lines
}

/**
 * Длина префикса: правил, чей порядок относителен (до первой очереди).
 *
 * Локальные сети и исключения обязаны идти раньше NFQUEUE, поэтому
 * reconcile точечно работает только когда этот префикс в живой цепочке
 * цел; иначе -- полная пересборка, которая восстановит и порядок.
 */
internal fun dpiFirewallPrefixLen(rules: List<String>): Int {
    val firstQueue = rules.indexOfFirst { "-j NFQUEUE" in it }
    return if (firstQueue < 0) rules.size else firstQueue
}

/** Локальные сети: им правила обхода не нужны и не должны мешать. */
private val LOCAL_NETS = listOf(
    "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
)

/**
 * Токен вида `80`, `8000:8010` или `8000-8010`; всё прочее -- не порт,
 * молча пропускаем.
 *
 * Дефис -- синтаксис диапазона у nfqws (`--filter-udp=27015-27020`), а
 * iptables диапазон пишет через двоеточие, поэтому форма из конфига
 * нормализуется под конкретного потребителя здесь же.
 */
internal fun portToken(raw: String): String? {
    val token = raw.trim().replace('-', ':')
    if (!token.matches(Regex("""\d+(:\d+)?"""))) return null
    if (token.startsWith("0:")) return null
    return token
}
