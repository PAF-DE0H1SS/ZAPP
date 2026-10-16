package xyz.azraellab.zapp.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Механика системного прокси на десктопе.
 *
 * Все внешние вызовы подменены: тесты проверяют формат команд, разбор
 * вывода ОС, порядок действий и цикл снимок->восстановление, но не
 * трогают реальные настройки машины. Реальная система проверяется
 * отдельным живым прогоном (TESTLOG).
 */
class SystemProxyTest {

    /** Каждый вызов: argv плюс готовый ответ; по умолчанию -- успех без вывода. */
    private class FakeCmd(
        private val handler: (List<String>) -> CmdResult = { CmdResult(0, "") }
    ) : CmdRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>): CmdResult {
            calls += argv
            return handler(argv)
        }
    }

    private class MemStore : SnapshotStore {
        var snapshot: ProxySnapshot? = null
        override fun read(): ProxySnapshot? = snapshot
        override fun write(snapshot: ProxySnapshot) { this.snapshot = snapshot }
        override fun clear() { snapshot = null }
    }

    private fun tempDir(): File =
        createTempDirectory("zapp-sysproxy-test").toFile().apply { deleteOnExit() }

    // --- Windows ---

    @Test
    fun windowsCaptureParsesRegistry() {
        val cmd = FakeCmd { argv ->
            when {
                "ProxyEnable" in argv -> CmdResult(0, "HKEY_CURRENT_USER\\Software\\...\r\n" +
                    "    ProxyEnable    REG_DWORD    0x1\r\n")
                "ProxyServer" in argv -> CmdResult(0, "    ProxyServer    REG_SZ    http=old:8080\r\n")
                "ProxyOverride" in argv -> CmdResult(1, "ERROR: The system was unable to find")
                else -> CmdResult(1, "")
            }
        }
        val values = assertNotNull(WindowsOps(cmd).capture())
        assertEquals("0x1", values["enable"])
        assertEquals("http=old:8080", values["server"])
        assertNull(values["override"], "отсутствующее значение не выдумывается")
    }

    @Test
    fun windowsApplyWritesSchemeAndNotifies() {
        val cmd = FakeCmd()
        assertTrue(WindowsOps(cmd).applyMark("127.0.0.1", 19060, 19061))
        val adds = cmd.calls.filter { it.getOrNull(1) == "add" }
        val server = adds.first { "ProxyServer" in it }
        assertEquals(
            "http=127.0.0.1:19061;https=127.0.0.1:19061;socks=127.0.0.1:19060",
            server[server.indexOf("/d") + 1],
            "тройная схема: http, https и socks на свои порты"
        )
        assertTrue(adds.any { "ProxyEnable" in it && "1" in it }, "ProxyEnable включается")
        assertTrue(adds.any { "ProxyOverride" in it }, "обход локальных имён пишется")
        assertTrue(cmd.calls.any { it.firstOrNull() == "powershell" }, "уведомление системы обязательно")
    }

    @Test
    fun windowsRestoreReturnsOldAndDeletesMissing() {
        val cmd = FakeCmd()
        val ops = WindowsOps(cmd)
        assertTrue(ops.restoreMark(mapOf("enable" to "0x0", "server" to "http=old:8080")))
        val adds = cmd.calls.filter { it.getOrNull(1) == "add" }
        assertTrue(adds.any { "http=old:8080" in it }, "прежний сервер возвращается")
        assertTrue(adds.any { "ProxyEnable" in it && "0x0" in it }, "прежний флаг возвращается")
        assertTrue(
            cmd.calls.any { it.getOrNull(1) == "delete" && "ProxyOverride" in it },
            "значения, которого не было, должно не остаться"
        )
        assertTrue(cmd.calls.any { it.firstOrNull() == "powershell" }, "и опять уведомление")
    }

    // --- GNOME ---

    private fun gnomeGetHandler(argv: List<String>): CmdResult {
        if (argv.getOrNull(1) != "get") return CmdResult(0, "")
        val schema = argv[2]
        val key = argv[3]
        return when {
            schema == "org.gnome.system.proxy" && key == "mode" -> CmdResult(0, "'manual'\n")
            schema == "org.gnome.system.proxy" && key == "autoconfig-url" ->
                CmdResult(0, "'http://pac.local/pac'\n")
            key == "enabled" -> CmdResult(0, "true\n")
            key == "host" && schema.endsWith(".http") -> CmdResult(0, "'10.0.0.2'\n")
            key == "host" && schema.endsWith(".socks") -> CmdResult(0, "'10.0.0.3'\n")
            key == "port" && schema.endsWith(".http") -> CmdResult(0, "8080\n")
            key == "port" && schema.endsWith(".socks") -> CmdResult(0, "1080\n")
            else -> CmdResult(0, "''\n")
        }
    }

    @Test
    fun gnomeCaptureUnquotesGVariant() {
        val values = assertNotNull(GnomeOps(FakeCmd(::gnomeGetHandler)).capture())
        assertEquals("manual", values["mode"])
        assertEquals("10.0.0.2", values["http.host"])
        assertEquals("8080", values["http.port"])
        assertEquals("10.0.0.3", values["socks.host"])
        assertEquals("1080", values["socks.port"])
        assertEquals("http://pac.local/pac", values["autoconfig-url"])
    }

    @Test
    fun gnomeCaptureFailsWithoutMode() {
        val cmd = FakeCmd { if (it.getOrNull(1) == "get") CmdResult(1, "") else CmdResult(0, "") }
        assertNull(GnomeOps(cmd).capture(), "без чтения режима снимок не существует")
    }

    @Test
    fun gnomeApplyTurnsModeOnLast() {
        val cmd = FakeCmd()
        assertTrue(GnomeOps(cmd).applyMark("127.0.0.1", 19060, 19061))
        val sets = cmd.calls.filter { it.getOrNull(1) == "set" }
        val modeIdx = sets.indexOfFirst { it[3] == "mode" }
        assertTrue(modeIdx == sets.lastIndex, "режим manual включается последним")
        val httpPort = sets.first { it[3] == "port" && it[2].endsWith("http") }
        assertEquals("19061", httpPort[4], "http-порт числом")
        val socksHost = sets.first { it[3] == "host" && it[2].endsWith(".socks") }
        assertEquals("'127.0.0.1'", socksHost[4], "хост -- строка GVariant")
    }

    @Test
    fun gnomeRestoreReturnsFieldsThenMode() {
        val cmd = FakeCmd()
        val values = mapOf(
            "mode" to "auto",
            "autoconfig-url" to "'http://pac.local/pac'",
            "http.enabled" to "false",
            "http.host" to "'10.0.0.2'",
            "http.port" to "8080",
            "socks.host" to "'10.0.0.3'",
            "socks.port" to "1080"
        )
        assertTrue(GnomeOps(cmd).restoreMark(values))
        val sets = cmd.calls.filter { it.getOrNull(1) == "set" }
        assertEquals("mode", sets.last()[3], "режим возвращается в конце")
        assertEquals("'auto'", sets.last()[4], "прежний режим дословно")
        assertTrue(sets.any { it[3] == "enabled" && it[4] == "false" }, "прежний enabled возвращается")
        assertTrue(sets.any { it[3] == "autoconfig-url" }, "прежний PAC возвращается")
    }

    // --- KDE ---

    @Test
    fun kdeApplyWritesEnvAndRestoreDropsOwnFile() {
        val file = File(tempDir(), "environment.d/zapp-proxy.conf")
        val cmd = FakeCmd()
        val ops = KdeOps(cmd, file)
        val before = ops.capture()
        assertTrue(ops.applyMark("127.0.0.1", 19060, 19061))
        val text = file.readText()
        assertTrue("HTTP_PROXY=http://127.0.0.1:19061" in text, "http-переменные пишутся")
        assertTrue("all_proxy=socks5://127.0.0.1:19060" in text, "socks-переменные пишутся")
        assertTrue(cmd.calls.any { it.getOrNull(2) == "set-environment" }, "user manager обновляется")
        assertTrue(ops.restoreMark(before), "восстановление -- без своего файла")
        assertFalse(file.exists(), "свой файл удалён")
        assertTrue(cmd.calls.any { it.getOrNull(2) == "unset-environment" }, "и из менеджера тоже")
    }

    @Test
    fun kdeRestoreKeepsForeignContent() {
        val file = File(tempDir(), "environment.d/zapp-proxy.conf")
        file.parentFile.mkdirs()
        file.writeText("HTTP_PROXY=http://corp:3128")
        val ops = KdeOps(FakeCmd(), file)
        val before = ops.capture()
        assertEquals("true", before["existed"], "прежнее содержимое зафиксировано")
        ops.applyMark("127.0.0.1", 19060, 19061)
        assertTrue(ops.restoreMark(before), "восстановление прошло")
        assertEquals("HTTP_PROXY=http://corp:3128", file.readText(), "чужой текст возвращён байт в байт")
    }

    // --- macOS ---

    private fun macHandler(argv: List<String>): CmdResult = when {
        argv.firstOrNull() == "route" -> CmdResult(0, "   route to: default\n   interface: en0\n")
        "-listallhardwareports" in argv -> CmdResult(
            0,
            "Hardware Port: Ethernet\nDevice: en7\n\nHardware Port: Wi-Fi\nDevice: en0\n"
        )
        "-getwebproxy" in argv -> CmdResult(0, "Enabled: Yes\nServer: old.example\nPort: 8080\n" +
            "Authentication Proxy Enabled: 0\n")
        "-getsecurewebproxy" in argv -> CmdResult(0, "Enabled: No\nServer: \nPort: 0\n")
        "-getsocksfirewallproxy" in argv -> CmdResult(0, "Enabled: Yes\nServer: 127.0.0.1\nPort: 1080\n")
        else -> CmdResult(0, "")
    }

    @Test
    fun macCaptureParsesProxyGetters() {
        val values = assertNotNull(MacOps(FakeCmd(::macHandler)).capture())
        assertEquals("Wi-Fi", values["service"], "сервис берётся по интерфейсу маршрута")
        assertEquals("Yes", values["web.enabled"])
        assertEquals("old.example", values["web.server"])
        assertEquals("8080", values["web.port"])
        assertEquals("No", values["secure.enabled"])
        assertEquals("1080", values["socks.port"])
    }

    @Test
    fun macApplySetsAllThreeSchemes() {
        val cmd = FakeCmd(::macHandler)
        assertTrue(MacOps(cmd).applyMark("127.0.0.1", 19060, 19061))
        val sets = cmd.calls.filter { it.firstOrNull() == "networksetup" && it[1].endsWith("proxy") }
        val web = sets.first { it[1] == "-setwebproxy" }
        assertEquals(listOf("networksetup", "-setwebproxy", "Wi-Fi", "127.0.0.1", "19061"), web)
        val socks = sets.first { it[1] == "-setsocksfirewallproxy" }
        assertEquals("19060", socks.last(), "socks идёт на свой порт")
        assertEquals(3, sets.size, "web, secure и socks")
        assertTrue(
            cmd.calls.count { it.getOrNull(1)?.endsWith("proxystate") == true && it.last() == "on" } == 3,
            "все три схемы включаются состоянием"
        )
    }

    @Test
    fun macRestoreDisablesWhatWasOff() {
        val cmd = FakeCmd(::macHandler)
        val values = mapOf(
            "service" to "Wi-Fi",
            "web.enabled" to "No",
            "secure.enabled" to "Yes",
            "secure.server" to "old.secure",
            "secure.port" to "8443",
            "socks.enabled" to "No"
        )
        assertTrue(MacOps(cmd).restoreMark(values))
        assertTrue(
            cmd.calls.any { it.getOrNull(1) == "-setwebproxystate" && it.last() == "off" },
            "выключенный web возвращается в off"
        )
        val secure = cmd.calls.firstOrNull { it.getOrNull(1) == "-setsecurewebproxy" && "old.secure" in it }
        assertNotNull(secure, "secure-сервер возвращается")
        assertTrue(cmd.calls.any { it.getOrNull(1) == "-setsecurewebproxystate" && it.last() == "on" })
    }

    // --- Движок: диспетчеризация, снимок, идемпотентность ---

    private fun engine(
        cmd: CmdRunner,
        store: SnapshotStore = MemStore(),
        os: String = "Linux",
        de: String? = "GNOME"
    ) = SystemProxyEngine(
        cmd = cmd,
        store = store,
        osName = os,
        env = { key -> if (key == "XDG_CURRENT_DESKTOP") de else null },
        userHome = "/home/test"
    )

    @Test
    fun engineDispatchesByOsAndDesktop() {
        assertTrue(assertNotNull(engine(FakeCmd(), os = "Windows 11").ops()) is WindowsOps)
        assertTrue(assertNotNull(engine(FakeCmd(), os = "Mac OS X").ops()) is MacOps)
        assertTrue(assertNotNull(engine(FakeCmd(), de = "KDE").ops()) is KdeOps)
        assertNull(engine(FakeCmd(), de = "XFCE").ops(), "неизвестное окружение -- не поддерживается")
        assertNull(engine(FakeCmd(), de = null).ops(), "без окружения -- тоже")
    }

    @Test
    fun engineApplyWritesSnapshotAndSecondApplyIsNoop() {
        val cmd = FakeCmd()
        val store = MemStore()
        assertTrue(engine(cmd, store).applyNow())
        val snapshot = assertNotNull(store.snapshot, "снимок обязателен до применения")
        assertEquals("gnome", snapshot.platform)
        val afterFirst = cmd.calls.size
        assertTrue(engine(cmd, store).applyNow(), "повтор -- тихий успех")
        assertEquals(afterFirst, cmd.calls.size, "но без единой новой команды: снимок уже есть")
    }

    @Test
    fun engineUnsupportedDesktopTouchesNothing() {
        val cmd = FakeCmd()
        val store = MemStore()
        assertFalse(engine(cmd, store, de = "XFCE").applyNow())
        assertNull(store.snapshot, "неподдерживаемая ОС не оставляет снимка")
        assertTrue(cmd.calls.isEmpty())
    }

    @Test
    fun engineRestoreWithoutSnapshotIsNoop() {
        val cmd = FakeCmd()
        assertFalse(engine(cmd).restoreNow())
        assertTrue(cmd.calls.isEmpty())
    }

    @Test
    fun engineRestoreClearsSnapshot() {
        val cmd = FakeCmd()
        val store = MemStore()
        assertTrue(engine(cmd, store).applyNow())
        val callsAfterApply = cmd.calls.size
        assertTrue(engine(cmd, store).restoreNow(), "восстановление прошло")
        assertNull(store.snapshot, "снимок потреблён")
        assertTrue(cmd.calls.size > callsAfterApply, "команды возврата выполнялись")
        assertFalse(engine(cmd, store).restoreNow(), "второй раз -- уже нечего")
    }

    @Test
    fun engineFailedApplyRollsBackSnapshot() {
        val cmd = FakeCmd { argv ->
            if (argv.getOrNull(1) == "set" && argv.getOrNull(3) == "mode" &&
                argv.getOrNull(4) == "'manual'"
            ) {
                CmdResult(1, "fail")
            } else {
                CmdResult(0, "")
            }
        }
        val store = MemStore()
        assertFalse(engine(cmd, store).applyNow(), "упавший режим -- значит, не применилось")
        assertNull(store.snapshot, "после отката снимка не остаётся")
    }

    @Test
    fun engineKeepsSnapshotWhenRestoreFails() {
        val cmd = FakeCmd()
        val store = MemStore()
        assertTrue(engine(cmd, store).applyNow())
        val failing = FakeCmd { CmdResult(1, "no") }
        assertFalse(engine(failing, store).restoreNow(), "упавший возврат -- не успех")
        assertNotNull(store.snapshot, "снимок остаётся до успешного возврата")
    }

    @Test
    fun fileStoreRoundTripsSnapshot() {
        val file = File(tempDir(), "systemproxy.json")
        val snapshot = ProxySnapshot("gnome", mapOf("mode" to "manual", "http.host" to "'127.0.0.1'"))
        FileSnapshotStore(file).write(snapshot)
        assertEquals(snapshot, FileSnapshotStore(file).read(), "снимок переживает перезапуск")
        FileSnapshotStore(file).clear()
        assertNull(FileSnapshotStore(file).read(), "после очистки файла нет")
        assertNull(FileSnapshotStore(File(tempDir(), "missing.json")).read(), "нет файла -- нет снимка")
    }

    @Test
    fun proxyPortsAreLoopbackDefaults() {
        assertEquals(19060, ProxyPorts.SOCKS)
        assertEquals(19061, ProxyPorts.HTTP)
    }
}
