package xyz.azraellab.zapp.core.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.azraellab.zapp.core.CmdResult
import xyz.azraellab.zapp.core.CmdRunner
import xyz.azraellab.zapp.core.ProxySnapshot
import xyz.azraellab.zapp.core.SnapshotStore

/**
 * Механика kill switch на десктопе.
 *
 * Все внешние вызовы подменены: тесты проверяют состав и порядок команд,
 * снимок «до» изменений и цикл возврата, но не трогают реальные
 * брандмауэры и DNS машины. Живой прогон -- отдельно, в TESTLOG.
 */
class KillSwitchTest {

    /** PowerShell-вызовы: argv плюс ответ скрипта. */
    private class FakePs(
        private val handler: (String) -> CmdResult = { CmdResult(0, "") }
    ) {
        val calls = mutableListOf<List<String>>()
        val cmd = CmdRunner { argv ->
            calls += argv
            handler(argv.last())
        }

        val scripts: List<String> get() = calls.map { it.last() }
        fun all(): String = scripts.joinToString("\n")
    }

    /** Root-команды Linux: запись плюс ответ. */
    private class FakeRoot(
        private val handler: (String) -> CmdResult = { CmdResult(0, "") }
    ) {
        val commands = mutableListOf<String>()
        fun run(command: String): CmdResult {
            commands += command
            return handler(command)
        }

        fun all(): String = commands.joinToString("\n")
    }

    private class MemStore : SnapshotStore {
        var snapshot: ProxySnapshot? = null
        override fun read(): ProxySnapshot? = snapshot
        override fun write(snapshot: ProxySnapshot) { this.snapshot = snapshot }
        override fun clear() { snapshot = null }
    }

    private val endpoints = listOf("192.0.2.10", "2001:db8::10")
    private val logs = mutableListOf<String>()
    private fun logged(): String = logs.joinToString("\n")

    private fun linuxEngine(root: FakeRoot, store: SnapshotStore = MemStore()) = KillSwitchEngine(
        cmd = CmdRunner { CmdResult(0, "") },
        store = store,
        osName = "Linux",
        root = { command, _ -> root.run(command) }
    )

    private fun windowsEngine(ps: FakePs, store: SnapshotStore = MemStore()) = KillSwitchEngine(
        cmd = ps.cmd,
        store = store,
        osName = "Windows 11",
        root = { _, _ -> CmdResult(0, "") }
    )

    /** Успешный снимок Windows: политика Allow, DNS у двух адаптеров. */
    private fun okCapture(script: String): CmdResult = when {
        "Get-NetFirewallProfile" in script ->
            CmdResult(0, "Domain|Allow\r\nPrivate|Allow\r\nPublic|Allow\r\n")
        "Get-DnsClientServerAddress" in script ->
            CmdResult(0, "Ethernet|192.168.1.1,8.8.8.8\r\nWi-Fi|\r\n")
        else -> CmdResult(0, "")
    }

    // --- Linux ---

    @Test
    fun linuxInstallBuildsFullRuleSet() {
        val root = FakeRoot()
        assertTrue(linuxEngine(root).install(endpoints, listOf("8.8.8.8")) { logs += it })
        val all = root.all()
        assertContains(all, "iptables -N ZAPP_KS 2>/dev/null; iptables -F ZAPP_KS")
        assertContains(all, "iptables -A ZAPP_KS -o lo -j RETURN")
        assertContains(all, "iptables -A ZAPP_KS -o tun+ -j RETURN")
        assertContains(all, "iptables -A ZAPP_KS -o utun+ -j RETURN")
        assertContains(all, "iptables -A ZAPP_KS -m state --state ESTABLISHED,RELATED -j RETURN")
        assertContains(all, "iptables -A ZAPP_KS -d 192.0.2.10/32 -j RETURN")
        assertFalse(
            all.contains("iptables -A ZAPP_KS -d 2001:db8::10"),
            "v6-адрес не попадает в ipv4-цепочку"
        )
        assertContains(all, "iptables -A ZAPP_KS -d 8.8.8.8/32 -j RETURN")
        assertContains(all, "iptables -A ZAPP_KS -j REJECT --reject-with icmp-port-unreachable")
        // IPv6 той же блокировкой отдельной цепочкой
        assertContains(all, "ip6tables -N ZAPP_KS6 2>/dev/null; ip6tables -F ZAPP_KS6")
        assertContains(all, "ip6tables -A ZAPP_KS6 -d 2001:db8::10/128 -j RETURN")
        assertContains(all, "ip6tables -A ZAPP_KS6 -j REJECT")
        assertContains(all, "iptables -C OUTPUT -j ZAPP_KS 2>/dev/null || iptables -I OUTPUT 1 -j ZAPP_KS")
        assertContains(all, "ip6tables -C OUTPUT -j ZAPP_KS6 2>/dev/null || ip6tables -I OUTPUT 1 -j ZAPP_KS6")
        assertContains(logged(), "kill switch: on")
    }

    @Test
    fun linuxWithoutRootSkips() {
        val root = FakeRoot {
            if ("-N ZAPP_KS" in it) CmdResult(1, "iptables: Permission denied") else CmdResult(0, "")
        }
        assertFalse(linuxEngine(root).install(endpoints, listOf("8.8.8.8")) { logs += it })
        assertContains(logged(), "note: iptables unavailable; kill switch skipped")
        assertEquals(1, root.commands.size, "после отказа цепочка не достраивается")
    }

    @Test
    fun linuxWithoutDnsListHasOnlyEndpointExemptions() {
        val root = FakeRoot()
        linuxEngine(root).install(endpoints, emptyList()) { logs += it }
        val exemptions = root.commands.count { "iptables -A ZAPP_KS -d " in it && "-j RETURN" in it }
        assertEquals(1, exemptions, "без флага DNS исключаются только адреса узлов")
    }

    @Test
    fun linuxRemoveCleansBothChainsAndLogsOff() {
        // Пробы цепочки после установки уже её не видят -- чистильщик
        // удаляет цепочку, и следующее снятие молчит.
        val root = FakeRoot { if ("-n -L" in it) CmdResult(1, "No chain") else CmdResult(0, "") }
        val engine = linuxEngine(root)
        engine.install(endpoints, listOf("8.8.8.8")) { logs += it }
        engine.remove { logs += it }
        val all = root.all()
        assertContains(all, "while iptables -D OUTPUT -j ZAPP_KS 2>/dev/null; do :; done")
        assertContains(all, "iptables -F ZAPP_KS 2>/dev/null; iptables -X ZAPP_KS 2>/dev/null")
        assertContains(all, "while ip6tables -D OUTPUT -j ZAPP_KS6 2>/dev/null; do :; done")
        assertContains(logged(), "kill switch: off")
        val before = logs.size
        engine.remove { logs += it }
        assertEquals(before, logs.size, "повторное снятие молчит")
    }

    @Test
    fun linuxRemoveWithoutInstallCleansSilently() {
        val root = FakeRoot {
            if ("-n -L" in it) CmdResult(1, "No chain") else CmdResult(0, "")
        }
        linuxEngine(root).remove { logs += it }
        assertTrue(root.commands.any { "while iptables -D OUTPUT" in it }, "остатки чистятся")
        assertEquals(0, logs.size, "не ставилось -- и в журнале тишина")
    }

    @Test
    fun linuxRemoveAfterCrashLogsOff() {
        // Цепочка осталась от убитой сессии: свежий движок её видит и чистит.
        val root = FakeRoot {
            if ("-n -L" in it) CmdResult(0, "Chain ZAPP_KS") else CmdResult(0, "")
        }
        linuxEngine(root).remove { logs += it }
        assertTrue(root.commands.any { "iptables -X ZAPP_KS" in it })
        assertContains(logged(), "kill switch: off")
    }

    // --- Windows ---

    @Test
    fun windowsInstallCapturesThenApplies() {
        val ps = FakePs { okCapture(it) }
        val store = MemStore()
        assertTrue(windowsEngine(ps, store).install(endpoints, listOf("8.8.8.8")) { logs += it })

        // Снимок строго до изменений: политика и DNS -- первыми вызовами.
        val firstCapture = ps.scripts.indexOfFirst { "Get-NetFirewallProfile" in it }
        val firstChange = ps.scripts.indexOfFirst { "New-NetFirewallRule" in it }
        assertTrue(firstCapture in 0 until firstChange, "снимок делается до правок")
        assertContains(ps.all(), "Set-NetFirewallProfile -Profile Domain,Private,Public -DefaultOutboundAction Block")

        // Разрешающие правила: туннель по локальному адресу, loopback,
        // DHCP, узлы, свои DNS.
        val all = ps.all()
        assertContains(all, "-LocalAddress '172.19.0.1/32','fdfe:dcba:9876::1/126'")
        assertContains(all, "-RemoteAddress '127.0.0.1'")
        assertContains(all, "-Protocol UDP -RemoteAddress '255.255.255.255'")
        assertContains(all, "-RemoteAddress '192.0.2.10'")
        assertContains(all, "-RemoteAddress '2001:db8::10'")
        assertContains(all, "-RemoteAddress '8.8.8.8'")

        // Системный DNS уходит внутрь туннеля.
        assertContains(all, "Set-DnsClientServerAddress -InterfaceAlias 'Ethernet' -ServerAddresses @('172.19.0.1')")
        assertContains(all, "Set-DnsClientServerAddress -InterfaceAlias 'Wi-Fi' -ServerAddresses @('172.19.0.1')")

        val snapshot = assertNotNull(store.snapshot)
        assertEquals("windows-net", snapshot.platform)
        assertEquals("Allow", snapshot.values["profile:Domain"])
        assertEquals("192.168.1.1,8.8.8.8", snapshot.values["dns:Ethernet"])
        assertEquals("", snapshot.values["dns:Wi-Fi"], "DHCP-адаптер тоже попадает в снимок")

        // Запуск -- обычный powershell с одним аргументом-скриптом.
        val argv = ps.calls.first()
        assertEquals("powershell", argv.first())
        assertContains(argv, "-NoProfile")
        assertContains(logged(), "kill switch: on")
    }

    @Test
    fun windowsWithoutAdminRollsBack() {
        val ps = FakePs { script ->
            if ("New-NetFirewallRule" in script) CmdResult(1, "New-NetFirewallRule : Access is denied.")
            else okCapture(script)
        }
        val store = MemStore()
        assertFalse(windowsEngine(ps, store).install(endpoints, emptyList()) { logs += it })
        assertContains(logged(), "note: kill switch: needs administrator rights")
        assertNull(store.snapshot, "снимок без изменений не сохраняется")
        assertContains(ps.all(), "Get-NetFirewallRule -DisplayName 'ZAPP KS*'")
        assertFalse(ps.scripts.any { "DefaultOutboundAction Block" in it }, "политика не трогалась")
    }

    @Test
    fun windowsPolicyFailureRollsBack() {
        val ps = FakePs { script ->
            if ("DefaultOutboundAction Block" in script) CmdResult(1, "Set-NetFirewallProfile : denied.")
            else okCapture(script)
        }
        val store = MemStore()
        assertFalse(windowsEngine(ps, store).install(endpoints, emptyList()) { logs += it })
        assertContains(logged(), "note: kill switch: cannot switch firewall policy")
        assertNull(store.snapshot)
        assertContains(ps.all(), "Get-NetFirewallRule -DisplayName 'ZAPP KS*'")
    }

    @Test
    fun windowsDnsFailureRestoresEverything() {
        val ps = FakePs { script ->
            if ("Set-DnsClientServerAddress" in script && "'172.19.0.1'" in script) {
                CmdResult(1, "Set-DnsClientServerAddress : access denied.")
            } else okCapture(script)
        }
        val store = MemStore()
        assertFalse(windowsEngine(ps, store).install(endpoints, listOf("8.8.8.8")) { logs += it })
        assertContains(logged(), "note: kill switch: DNS switch failed")
        val all = ps.all()
        // Откат: политика по снимку, DNS по снимку, правила сняты.
        assertContains(all, "Set-NetFirewallProfile -Profile 'Domain' -DefaultOutboundAction Allow")
        assertContains(all, "Set-DnsClientServerAddress -InterfaceAlias 'Ethernet' -ServerAddresses @('192.168.1.1','8.8.8.8')")
        assertContains(all, "Set-DnsClientServerAddress -InterfaceAlias 'Wi-Fi' -ResetServerAddress")
        assertContains(all, "Get-NetFirewallRule -DisplayName 'ZAPP KS*'")
        assertNull(store.snapshot)
    }

    @Test
    fun windowsRemoveRestoresAndCleans() {
        val ps = FakePs { okCapture(it) }
        val store = MemStore()
        val engine = windowsEngine(ps, store)
        engine.install(endpoints, listOf("8.8.8.8")) { logs += it }
        engine.remove { logs += it }
        val all = ps.all()
        assertContains(all, "Set-NetFirewallProfile -Profile 'Domain' -DefaultOutboundAction Allow")
        assertContains(all, "Set-DnsClientServerAddress -InterfaceAlias 'Ethernet' -ServerAddresses @('192.168.1.1','8.8.8.8')")
        assertContains(all, "Set-DnsClientServerAddress -InterfaceAlias 'Wi-Fi' -ResetServerAddress")
        assertContains(all, "Get-NetFirewallRule -DisplayName 'ZAPP KS*'")
        assertNull(store.snapshot, "снимок чистится после успешного возврата")
        assertContains(logged(), "kill switch: off")
    }

    @Test
    fun windowsRemoveWithoutSnapshotCleansSilently() {
        val ps = FakePs()
        windowsEngine(ps, MemStore()).remove { logs += it }
        assertContains(ps.all(), "Get-NetFirewallRule -DisplayName 'ZAPP KS*'")
        assertEquals(0, logs.size)
    }

    @Test
    fun windowsInstallKeepsExistingSnapshot() {
        // Снимок уже лежит (краш до восстановления): повторное включение
        // не перезаписывает исходное состояние системы своими значениями.
        val ps = FakePs { okCapture(it) }
        val store = MemStore().apply {
            snapshot = ProxySnapshot(
                "windows-net",
                mapOf("profile:Domain" to "Block", "dns:Ethernet" to "10.0.0.1")
            )
        }
        assertTrue(windowsEngine(ps, store).install(endpoints, emptyList()) { logs += it })
        assertFalse(ps.scripts.any { "Get-NetFirewallProfile" in it }, "повторный снимок не снимается")
        assertEquals("Block", store.snapshot?.values?.get("profile:Domain"))
    }

    // --- macOS и прочее ---

    @Test
    fun macosIsHonestRefusal() {
        val ps = FakePs()
        val root = FakeRoot()
        val engine = KillSwitchEngine(
            cmd = ps.cmd,
            store = MemStore(),
            osName = "Mac OS X",
            root = { command, _ -> root.run(command) }
        )
        assertFalse(engine.install(endpoints, listOf("8.8.8.8")) { logs += it })
        assertContains(logged(), "not supported on this platform")
        engine.remove { logs += it }
        assertEquals(0, ps.calls.size, "ни одной команды в систему")
        assertEquals(0, root.commands.size)
        assertEquals(1, logs.size, "отказ -- единственная запись")
    }
}
