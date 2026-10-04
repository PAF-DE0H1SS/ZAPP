package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.root.RootShell

/**
 * NFQUEUE-правила на iptables (общий JVM-слой: Android и десктоп).
 *
 * Один su-вызов на операцию: цепочка собирается строкой через `;`, чтобы
 * установка правил не стоила десятка перезапусков su и не рвалась
 * посередине из-за таймаута. Дубли прыжка вычищаются до добавления --
 * `-C` есть не в каждой сборке iptables, а цепочка, прыгнувшая дважды,
 * отдала бы каждый пакет в очередь дважды.
 */
actual object DpiFirewall {

    private const val CHAIN = "ZAPP_DPI"
    private const val QUEUE = "--queue-num 0 --queue-bypass"
    private const val TIMEOUT_MS = 15_000L

    /** Локальные сети: им правила обхода не нужны и не должны мешать. */
    private val LOCAL_NETS = listOf(
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
    )

    actual fun enable(tcpPorts: List<String>, udpPorts: List<String>) {
        if (!RootShell.available()) return
        val tcp = tcpPorts.mapNotNull(::portToken)
        val udp = udpPorts.mapNotNull(::portToken)

        val script = buildString {
            appendLine("iptables -w -t mangle -N $CHAIN 2>/dev/null || true")
            appendLine("iptables -w -t mangle -F $CHAIN")
            for (net in LOCAL_NETS) {
                appendLine("iptables -w -t mangle -A $CHAIN -d $net -j RETURN")
            }
            if (tcp.isEmpty()) {
                appendLine("iptables -w -t mangle -A $CHAIN -p tcp -j NFQUEUE $QUEUE")
            } else {
                for (token in tcp) {
                    appendLine(
                        "iptables -w -t mangle -A $CHAIN -p tcp --dport $token -j NFQUEUE $QUEUE"
                    )
                }
            }
            for (token in udp) {
                appendLine(
                    "iptables -w -t mangle -A $CHAIN -p udp --dport $token -j NFQUEUE $QUEUE"
                )
            }
            // Старые прыжки снимаются все, а не один: за одну сессию их
            // могло накопиться больше одного, и каждый лишний -- двойная
            // очередь на пакет.
            repeat(8) {
                appendLine("iptables -w -t mangle -D OUTPUT -j $CHAIN 2>/dev/null || true")
            }
            appendLine("iptables -w -t mangle -A OUTPUT -j $CHAIN")
        }
        RootShell.run(script, TIMEOUT_MS)
    }

    actual fun disable() {
        if (!RootShell.available()) return
        RootShell.run(
            buildString {
                repeat(8) {
                    appendLine("iptables -w -t mangle -D OUTPUT -j $CHAIN 2>/dev/null || true")
                }
                appendLine("iptables -w -t mangle -F $CHAIN 2>/dev/null || true")
                appendLine("iptables -w -t mangle -X $CHAIN 2>/dev/null || true")
            },
            TIMEOUT_MS
        )
    }

    /** Токен вида `80` или `8000:8010`; всё прочее -- не порт, молча пропускаем. */
    private fun portToken(raw: String): String? {
        val token = raw.trim()
        if (!token.matches(Regex("""\d+(:\d+)?"""))) return null
        if (token.startsWith("0:")) return null
        return token
    }
}
