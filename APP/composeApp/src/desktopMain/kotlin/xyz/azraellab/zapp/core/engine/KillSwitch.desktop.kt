package xyz.azraellab.zapp.core.engine

import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import xyz.azraellab.zapp.core.CmdResult
import xyz.azraellab.zapp.core.CmdRunner
import xyz.azraellab.zapp.core.FileSnapshotStore
import xyz.azraellab.zapp.core.ProcessCmd
import xyz.azraellab.zapp.core.ProxySnapshot
import xyz.azraellab.zapp.core.SnapshotStore
import xyz.azraellab.zapp.core.net.SingboxConfig
import xyz.azraellab.zapp.core.root.RootShell
import xyz.azraellab.zapp.core.zappConfigRoot

/**
 * Реализация на десктопе.
 *
 * Linux: цепочки iptables/ip6tables в OUTPUT (ядро ходит к узлам по
 * адресам-исключениям; пометка fwmark не нужна -- маршрутизация и так
 * отправляет весь трафик в tun, а прямые соединения ядра покрыты
 * исключениями по адресам). Windows: разрешающие правила брандмауэра
 * плюс переключение политики профилей на «блокировать исходящий» --
 * разрешающие правила всегда бьют политику, и вопрос приоритета
 * allow/block-правил не встаёт. macOS не поддерживается.
 */
actual object KillSwitchCtl {

    private val engine by lazy {
        KillSwitchEngine(
            cmd = ProcessCmd,
            store = FileSnapshotStore(File(zappConfigRoot(), SNAPSHOT_NAME))
        )
    }

    actual fun install(endpoints: List<String>, dns: List<String>, log: (String) -> Unit): Boolean =
        runCatching { engine.install(endpoints, dns, log) }
            .getOrElse { e -> log("note: kill switch failed: ${e.message ?: "?"}"); false }

    actual fun remove(log: (String) -> Unit) {
        runCatching { engine.remove(log) }
            .onFailure { log("note: kill switch restore failed: ${it.message ?: "?"}") }
    }

    private const val SNAPSHOT_NAME = "windnet.json"
}

/**
 * Движок kill switch с подставными зависимостями: внешние команды
 * (iptables / PowerShell), хранилище снимка, имя ОС -- всё подменяется
 * тестами, вся механика считается без реальной системы.
 *
 * Снимок пишется ДО изменений: политика брандмауэра и системный
 * DNS Windows. Файл чистится только после успешного возврата; остатки
 * после краша убирает первый же запуск приложения.
 */
internal class KillSwitchEngine(
    private val cmd: CmdRunner,
    private val store: SnapshotStore,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val root: (String, Long) -> CmdResult = { command, timeout ->
        RootShell.run(command, timeout).let { CmdResult(it.code, it.output) }
    }
) {

    private var active = false

    fun install(endpoints: List<String>, dns: List<String>, log: (String) -> Unit): Boolean = when {
        osName.contains("mac", true) -> {
            log("note: kill switch: not supported on this platform")
            false
        }
        osName.contains("win", true) -> windowsInstall(endpoints, dns, log)
        else -> linuxInstall(endpoints, dns, log)
    }

    fun remove(log: (String) -> Unit) {
        when {
            osName.contains("mac", true) -> Unit
            osName.contains("win", true) -> windowsRemove(log)
            else -> linuxRemove(log)
        }
    }

    // --- Linux ---

    private fun linuxInstall(endpoints: List<String>, dns: List<String>, log: (String) -> Unit): Boolean {
        val setup = root("iptables -N ZAPP_KS 2>/dev/null; iptables -F ZAPP_KS", 3000)
        if (setup.code != 0) {
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
            // Шестёрка -- только в ip6tables: ipv4-цепочка адрес v6
            // не принимает, команда упала бы с предупреждением.
            if (endpoint.contains(':')) continue
            rules += "iptables -A ZAPP_KS -d $endpoint/32 -j RETURN"
        }
        // Свои DNS при kill switch: без них мёртв и резолвер --
        // переподключение по доменному имени не поднимется.
        for (server in dns) {
            val addr = runCatching { InetAddress.getByName(server) }.getOrNull() ?: continue
            if (addr is Inet6Address) continue
            rules += "iptables -A ZAPP_KS -d $server/32 -j RETURN"
        }
        rules += "iptables -A ZAPP_KS -j REJECT --reject-with icmp-port-unreachable"
        for (rule in rules) {
            val r = root(rule, 3000)
            if (r.code != 0) log("warning: kill switch: ${lastLine(r.output)}")
        }
        installV6(endpoints, dns, log)
        val jump = root("iptables -C OUTPUT -j ZAPP_KS 2>/dev/null || iptables -I OUTPUT 1 -j ZAPP_KS", 3000)
        if (jump.code == 0) {
            active = true
            log("kill switch: on")
        } else {
            log("note: cannot attach kill switch chain")
        }
        return jump.code == 0
    }

    /**
     * IPv6 той же блокировкой отдельной цепочкой: без неё шестёрка уходит
     * мимо ipv4-правил. Нет ip6tables -- пропускаем, ipv4 работает.
     */
    private fun installV6(endpoints: List<String>, dns: List<String>, log: (String) -> Unit) {
        val setup = root("ip6tables -N ZAPP_KS6 2>/dev/null; ip6tables -F ZAPP_KS6", 3000)
        if (setup.code != 0) return
        val rules = mutableListOf(
            "ip6tables -A ZAPP_KS6 -o lo -j RETURN",
            "ip6tables -A ZAPP_KS6 -o tun+ -j RETURN",
            "ip6tables -A ZAPP_KS6 -o utun+ -j RETURN",
            "ip6tables -A ZAPP_KS6 -m state --state ESTABLISHED,RELATED -j RETURN"
        )
        for (endpoint in endpoints) {
            if (endpoint.contains(':')) rules += "ip6tables -A ZAPP_KS6 -d $endpoint/128 -j RETURN"
        }
        for (server in dns) {
            val addr = runCatching { InetAddress.getByName(server) }.getOrNull() ?: continue
            if (addr is Inet6Address) rules += "ip6tables -A ZAPP_KS6 -d $server/128 -j RETURN"
        }
        rules += "ip6tables -A ZAPP_KS6 -j REJECT"
        for (rule in rules) {
            val r = root(rule, 3000)
            if (r.code != 0) log("warning: kill switch v6: ${lastLine(r.output)}")
        }
        root("ip6tables -C OUTPUT -j ZAPP_KS6 2>/dev/null || ip6tables -I OUTPUT 1 -j ZAPP_KS6", 3000)
    }

    private fun linuxRemove(log: (String) -> Unit) {
        // Цепочки может не быть (ничего не ставилось или чистили раньше):
        // тогда все шаги молча отваливаются. Чистятся обе -- остатки
        // после краша блокируют сеть, пока о них не вспомнят.
        val existed = root("iptables -n -L ZAPP_KS >/dev/null 2>&1", 3000).code == 0 ||
            root("ip6tables -n -L ZAPP_KS6 >/dev/null 2>&1", 3000).code == 0
        root(
            "while iptables -D OUTPUT -j ZAPP_KS 2>/dev/null; do :; done; " +
                "iptables -F ZAPP_KS 2>/dev/null; iptables -X ZAPP_KS 2>/dev/null",
            4000
        )
        root(
            "while ip6tables -D OUTPUT -j ZAPP_KS6 2>/dev/null; do :; done; " +
                "ip6tables -F ZAPP_KS6 2>/dev/null; ip6tables -X ZAPP_KS6 2>/dev/null",
            4000
        )
        val was = active || existed
        active = false
        if (was) log("kill switch: off")
    }

    // --- Windows ---

    private fun windowsInstall(endpoints: List<String>, dns: List<String>, log: (String) -> Unit): Boolean {
        val snapshot = store.read() ?: captureWindows()?.also { store.write(it) }
        if (snapshot == null) {
            log("note: kill switch: cannot capture network state")
            return false
        }
        if (!windowsRules(endpoints, dns, log)) {
            store.clear()
            windowsCleanupRules(log)
            log("note: kill switch: needs administrator rights")
            return false
        }
        if (!ps("Set-NetFirewallProfile -Profile Domain,Private,Public -DefaultOutboundAction Block").ok) {
            store.clear()
            windowsCleanupRules(log)
            log("note: kill switch: cannot switch firewall policy")
            return false
        }
        if (dns.isNotEmpty() && !windowsApplyDns(snapshot, log)) {
            windowsRestore(snapshot, log)
            windowsCleanupRules(log)
            log("note: kill switch: DNS switch failed")
            return false
        }
        active = true
        log("kill switch: on")
        return true
    }

    /**
     * Снимок политики брандмауэра и системного DNS до вмешательства.
     * Значения -- машинные токены (Allow/Block, адреса), не локализованный
     * текст вывода, поэтому разбор не зависит от языка системы.
     */
    private fun captureWindows(): ProxySnapshot? {
        val profiles = ps(
            "Get-NetFirewallProfile | ForEach-Object { \$_.Name + '|' + \$_.DefaultOutboundAction }"
        )
        if (!profiles.ok) return null
        val dnsServers = ps(
            "Get-DnsClientServerAddress -AddressFamily IPv4 | " +
                "ForEach-Object { \$_.InterfaceAlias + '|' + (\$_.ServerAddresses -join ',') }"
        )
        if (!dnsServers.ok) return null
        val values = mutableMapOf<String, String>()
        for (line in profiles.output.lineSequence()) {
            val parts = line.split('|', limit = 2)
            if (parts.size == 2 && parts[1].trim().isNotBlank()) values["profile:${parts[0].trim()}"] = parts[1].trim()
        }
        for (line in dnsServers.output.lineSequence()) {
            val parts = line.split('|', limit = 2)
            if (parts.size == 2) values["dns:${parts[0].trim()}"] = parts[1].trim()
        }
        if (values.keys.none { it.startsWith("profile:") }) return null
        return ProxySnapshot(SNAPSHOT_PLATFORM, values)
    }

    /** Разрешающие правила: туннель, loopback, DHCP, узлы и свои DNS. */
    private fun windowsRules(endpoints: List<String>, dns: List<String>, log: (String) -> Unit): Boolean {
        val statements = mutableListOf<String>()
        fun allow(name: String, display: String, conditions: String) {
            statements += "Remove-NetFirewallRule -Name ${q(name)} -ErrorAction SilentlyContinue"
            statements += (
                "New-NetFirewallRule -Name ${q(name)} -DisplayName ${q(display)} " +
                    "-Direction Outbound -Action Allow -Enabled True $conditions | Out-Null"
                )
        }
        // Локальный адрес -- единственное, чем пакет в туннеле отличается
        // от прямого: адреса назначения у них совпадают.
        val tunAddresses = listOf("${SingboxConfig.TUN_LOCAL_V4}/32", "${SingboxConfig.TUN_LOCAL_V6}/126")
        allow("ZAPP-KS-tun", "ZAPP KS tun", "-LocalAddress ${tunAddresses.joinToString(",") { q(it) }}")
        allow("ZAPP-KS-loop", "ZAPP KS loop", "-RemoteAddress ${q("127.0.0.1")}")
        allow("ZAPP-KS-dhcp", "ZAPP KS dhcp", "-Protocol UDP -RemoteAddress ${q("255.255.255.255")}")
        endpoints.forEachIndexed { index, endpoint ->
            allow("ZAPP-KS-ep${index + 1}", "ZAPP KS ep${index + 1}", "-RemoteAddress ${q(endpoint)}")
        }
        dns.forEachIndexed { index, server ->
            val addr = runCatching { InetAddress.getByName(server) }.getOrNull() ?: return@forEachIndexed
            allow(
                "ZAPP-KS-dns${index + 1}", "ZAPP KS dns${index + 1}",
                "-RemoteAddress ${q(addr.hostAddress ?: server)}"
            )
        }
        val result = ps(statements.joinToString("; "))
        if (result.ok) return true
        log("warning: kill switch: ${lastLine(result.output)}")
        return false
    }

    private fun windowsApplyDns(snapshot: ProxySnapshot, log: (String) -> Unit): Boolean {
        val aliases = snapshot.values.keys.filter { it.startsWith("dns:") }.map { it.removePrefix("dns:") }
        if (aliases.isEmpty()) return true
        val statements = aliases.map { alias ->
            "Set-DnsClientServerAddress -InterfaceAlias ${q(alias)} -ServerAddresses @(${q(SingboxConfig.TUN_LOCAL_V4)})"
        }
        val result = ps(statements.joinToString("; "))
        if (result.ok) return true
        log("warning: kill switch: DNS switch: ${lastLine(result.output)}")
        return false
    }

    private fun windowsRemove(log: (String) -> Unit) {
        val snapshot = store.read()
        if (snapshot != null) windowsRestore(snapshot, log)
        windowsCleanupRules(log)
        val was = active || snapshot != null
        active = false
        if (was) log("kill switch: off")
    }

    /** Возврат политики и DNS по снимку; файл чистится только при успехе. */
    private fun windowsRestore(snapshot: ProxySnapshot, log: (String) -> Unit) {
        var ok = true
        val profileStatements = snapshot.values
            .filterKeys { it.startsWith("profile:") }
            .map { (key, value) ->
                "Set-NetFirewallProfile -Profile ${q(key.removePrefix("profile:"))} -DefaultOutboundAction $value"
            }
        if (profileStatements.isNotEmpty()) {
            val result = ps(profileStatements.joinToString("; "))
            if (!result.ok) {
                ok = false
                log("warning: kill switch: policy restore: ${lastLine(result.output)}")
            }
        }
        val dnsStatements = snapshot.values
            .filterKeys { it.startsWith("dns:") }
            .map { (key, value) ->
                val alias = q(key.removePrefix("dns:"))
                if (value.isBlank()) "Set-DnsClientServerAddress -InterfaceAlias $alias -ResetServerAddress"
                else {
                    val servers = value.split(',').filter { it.isNotBlank() }.joinToString(",") { q(it.trim()) }
                    "Set-DnsClientServerAddress -InterfaceAlias $alias -ServerAddresses @($servers)"
                }
            }
        if (dnsStatements.isNotEmpty()) {
            val result = ps(dnsStatements.joinToString("; "))
            if (!result.ok) {
                ok = false
                log("warning: kill switch: DNS restore: ${lastLine(result.output)}")
            }
        }
        if (ok) store.clear()
    }

    private fun windowsCleanupRules(log: (String) -> Unit) {
        // По префиксу DisplayName, а не по именам: имена правил после
        // краша в памяти движка уже не угадать, а префикс знаем всегда.
        val result = ps("Get-NetFirewallRule -DisplayName 'ZAPP KS*' | Remove-NetFirewallRule -ErrorAction SilentlyContinue")
        if (!result.ok) log("warning: kill switch: rules remove: ${lastLine(result.output)}")
    }

    private fun ps(script: String): CmdResult = cmd.run(
        listOf(POWERSHELL, "-NoProfile", "-Command", "\$ErrorActionPreference='Stop'; $script")
    )

    /** Экранирование строки для одинарных кавычек PowerShell. */
    private fun q(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun lastLine(text: String): String =
        text.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()

    private companion object {
        const val POWERSHELL = "powershell"
        const val SNAPSHOT_PLATFORM = "windows-net"
    }
}
