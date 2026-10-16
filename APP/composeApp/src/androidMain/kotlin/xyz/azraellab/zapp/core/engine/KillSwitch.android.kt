package xyz.azraellab.zapp.core.engine

import java.net.Inet6Address
import java.net.InetAddress
import xyz.azraellab.zapp.core.root.RootShell

/**
 * Android: цепочка iptables от root (и «чёрная дыра» VPN-сессии, когда
 * root нет). Порядок команд и тексты журнала совпадают с прежним
 * поведением TunnelEngine -- оно проверено на устройстве.
 */
actual object KillSwitchCtl {

    private var installed = false

    actual fun install(endpoints: List<String>, dns: List<String>, log: (String) -> Unit): Boolean {
        if (!RootShell.available()) {
            // Noroot-сборка: эквивалент kill switch -- сама сессия
            // VpnService: пока туннель поднят, весь трафик идёт в tun, а
            // упавший бэкенд молча отбрасывает пакеты (чёрная дыра).
            // iptables-цепочка здесь не нужна и не создаётся.
            log("note: kill switch -- tun blackhole (session mode)")
            return false
        }
        installed = true
        val setup = RootShell.run(
            """
            iptables -N ZAPP_KS 2>/dev/null
            iptables -F ZAPP_KS
            """.trimIndent().replace("\n", "; "),
            3000
        )
        if (!setup.ok) {
            log("note: iptables unavailable; kill switch skipped")
            return false
        }
        val rules = mutableListOf(
            "iptables -A ZAPP_KS -o lo -j RETURN",
            "iptables -A ZAPP_KS -o tun+ -j RETURN",
            "iptables -A ZAPP_KS -o utun+ -j RETURN",
            "iptables -A ZAPP_KS -m state --state ESTABLISHED,RELATED -j RETURN"
        )
        for (endpoint in endpoints) {
            rules += "iptables -A ZAPP_KS -d $endpoint/32 -j RETURN"
        }
        // Свои DNS при kill switch: без них мёртв не только трафик, но и
        // резолвер -- переподключение по доменному имени не поднимется.
        // Пропускаются только серверы самого конфига, не «любой DNS».
        for (server in dns) {
            val addr = runCatching { InetAddress.getByName(server) }.getOrNull() ?: continue
            val mask = if (addr is Inet6Address) 128 else 32
            rules += "iptables -A ZAPP_KS -d $server/$mask -j RETURN"
        }
        rules += "iptables -A ZAPP_KS -j REJECT --reject-with icmp-port-unreachable"
        for (rule in rules) {
            val r = RootShell.run(rule, 3000)
            if (!r.ok) log("warning: kill switch: ${lastLine(r.output)}")
        }
        val jump = RootShell.run("iptables -C OUTPUT -j ZAPP_KS 2>/dev/null || iptables -I OUTPUT 1 -j ZAPP_KS", 3000)
        if (jump.ok) log("kill switch: on")
        else log("note: cannot attach kill switch chain")
        return jump.ok
    }

    actual fun remove(log: (String) -> Unit) {
        if (!installed) return
        installed = false
        if (!RootShell.available()) return
        RootShell.run(
            "while iptables -D OUTPUT -j ZAPP_KS 2>/dev/null; do :; done; iptables -F ZAPP_KS 2>/dev/null; iptables -X ZAPP_KS 2>/dev/null",
            4000
        )
        log("kill switch: off")
    }

    private fun lastLine(text: String): String =
        text.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
}
