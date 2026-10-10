package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.root.RootShell

/**
 * NFQUEUE-правила на iptables (общий JVM-слой: Android и десктоп).
 *
 * Один su-вызов на операцию: цепочка собирается строкой через `;`, чтобы
 * установка правил не стоила десятка перезапусков su и не рвалась
 * посередине из-за таймаута. Сам текст скрипта живёт в общем слое
 * (DpiScript.kt) -- там он проверяется тестами без устройства.
 */
actual object DpiFirewall {

    private const val TIMEOUT_MS = 15_000L

    actual fun enable(
        tcpPorts: List<String>,
        udpPorts: List<String>,
        uids: List<Int>,
        excludeUids: List<Int>
    ) {
        if (!RootShell.available()) return
        RootShell.run(
            dpiFirewallScript(tcpPorts, udpPorts, uids, excludeUids),
            TIMEOUT_MS
        )
    }

    actual fun disable() {
        if (!RootShell.available()) return
        RootShell.run(
            buildString {
                repeat(8) {
                    appendLine("iptables -w -t mangle -D OUTPUT -j ZAPP_DPI 2>/dev/null || true")
                }
                appendLine("iptables -w -t mangle -F ZAPP_DPI 2>/dev/null || true")
                appendLine("iptables -w -t mangle -X ZAPP_DPI 2>/dev/null || true")
            },
            TIMEOUT_MS
        )
    }
}
