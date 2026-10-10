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
 */
internal fun dpiFirewallScript(
    tcpPorts: List<String>,
    udpPorts: List<String>,
    owners: List<Int>,
    excludes: List<Int>
): String {
    val tcp = tcpPorts.mapNotNull(::portToken)
    val udp = udpPorts.mapNotNull(::portToken)
    // Владельцы и исключения уникальны и отсортированы: порядок правил
    // не влияет на результат, а детерминированность облегчает дебаг в
    // iptables -L.
    val ownerUids = owners.distinct().sorted()
    val excludeUids = excludes.distinct().sorted()

    return buildString {
        appendLine("iptables -w -t mangle -N ZAPP_DPI 2>/dev/null || true")
        appendLine("iptables -w -t mangle -F ZAPP_DPI")
        for (net in LOCAL_NETS) {
            appendLine("iptables -w -t mangle -A ZAPP_DPI -d $net -j RETURN")
        }
        // Исключения строго до очереди: RETURN из цепочки прекращает
        // обход для этого пакета, поставь мы его после NFQUEUE.
        for (uid in excludeUids) {
            appendLine("iptables -w -t mangle -A ZAPP_DPI -m owner --uid-owner $uid -j RETURN")
        }
        // Правило одно портовое, но на владельцев расщепляется:
        // owner-match берёт ровно один uid, а пакет приходит от
        // конкретного приложения. Пустой owners -- include-фильтра нет,
        // очередь получает весь трафик порта (исключения выше уже
        // вернули свои пакеты).
        fun queue(prefix: String) {
            if (ownerUids.isEmpty()) {
                appendLine("$prefix -j NFQUEUE --queue-num 0 --queue-bypass")
            } else {
                for (uid in ownerUids) {
                    appendLine("$prefix -m owner --uid-owner $uid -j NFQUEUE --queue-num 0 --queue-bypass")
                }
            }
        }
        if (tcp.isEmpty()) {
            queue("iptables -w -t mangle -A ZAPP_DPI -p tcp")
        } else {
            for (token in tcp) {
                queue("iptables -w -t mangle -A ZAPP_DPI -p tcp --dport $token")
            }
        }
        for (token in udp) {
            queue("iptables -w -t mangle -A ZAPP_DPI -p udp --dport $token")
        }
        // Старые прыжки снимаются все, а не один: за одну сессию их
        // могло накопиться больше одного, и каждый лишний -- двойная
        // очередь на пакет.
        repeat(8) {
            appendLine("iptables -w -t mangle -D OUTPUT -j ZAPP_DPI 2>/dev/null || true")
        }
        appendLine("iptables -w -t mangle -A OUTPUT -j ZAPP_DPI")
    }
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
