package xyz.azraellab.zapp.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Регистрация автозапуска: формат записей трёх систем и проверка статуса.
 * Ничего реального не трогается: команды подменены, файл автозапуска
 * пишется во временный каталог.
 */
class AutostartTest {

    private class FakeCmd(
        private val handler: (List<String>) -> CmdResult = { CmdResult(0, "") }
    ) : CmdRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>): CmdResult {
            calls += argv
            return handler(argv)
        }
    }

    private fun linux(cmd: FakeCmd, exe: String?, file: File?) = AutostartEngine(
        cmd = cmd, osName = "Linux", exePath = { exe }, autostartFile = { file }
    )

    private fun windows(cmd: FakeCmd, exe: String?) = AutostartEngine(
        cmd = cmd, osName = "Windows 11", exePath = { exe }, autostartFile = { null }
    )

    private fun mac(cmd: FakeCmd, exe: String?) = AutostartEngine(
        cmd = cmd, osName = "Mac OS X", exePath = { exe }, autostartFile = { null }
    )

    private fun tempEntry(): File = File(createTempDirectory("zapp-autostart").toFile(), "zapp.desktop")

    // --- Linux: файл автозапуска ---

    @Test
    fun linuxEnableWritesDesktopEntry() {
        val cmd = FakeCmd()
        val entry = tempEntry()

        assertTrue(linux(cmd, "/opt/zapp/zapp", entry).enable())

        val text = entry.readText()
        assertContains(text, "[Desktop Entry]")
        assertContains(text, "Type=Application")
        assertContains(text, "Name=ZAPP")
        assertContains(text, "Exec=/opt/zapp/zapp")
        assertTrue(linux(cmd, "/opt/zapp/zapp", entry).status())
        assertTrue(cmd.calls.isEmpty(), "файловый путь команд не требует")
    }

    @Test
    fun linuxExecQuotesSpaces() {
        val entry = tempEntry()

        assertTrue(linux(FakeCmd(), "/opt/my app/zapp", entry).enable())

        assertContains(entry.readText(), "Exec=\"/opt/my app/zapp\"")
        assertTrue(linux(FakeCmd(), "/opt/my app/zapp", entry).status())
    }

    @Test
    fun linuxStatusFalseWithoutFile() {
        val cmd = FakeCmd()
        assertFalse(linux(cmd, "/opt/zapp/zapp", tempEntry()).status())
        assertTrue(cmd.calls.isEmpty())
    }

    @Test
    fun linuxStatusFalseWhenPathChanged() {
        val entry = tempEntry()
        linux(FakeCmd(), "/old/path/zapp", entry).enable()

        assertFalse(linux(FakeCmd(), "/new/path/zapp", entry).status(), "запись от старого пути неактивна")
    }

    @Test
    fun linuxDisableRemovesFile() {
        val entry = tempEntry()
        val engine = linux(FakeCmd(), "/opt/zapp/zapp", entry)
        engine.enable()

        assertTrue(engine.disable())
        assertFalse(entry.exists())
        assertFalse(engine.status())
    }

    // --- Windows: Run-ключ ---

    @Test
    fun windowsEnableUsesRunKey() {
        val cmd = FakeCmd()

        assertTrue(windows(cmd, "C:\\ZAPP\\zapp.exe").enable())

        assertEquals(
            listOf(
                "reg", "add",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "ZAPP", "/t", "REG_SZ", "/d", "C:\\ZAPP\\zapp.exe", "/f"
            ),
            cmd.calls.single()
        )
    }

    @Test
    fun windowsStatusNeedsKeyAndCurrentPath() {
        val exe = "C:\\ZAPP\\zapp.exe"
        val present = FakeCmd { argv ->
            if (argv[1] == "query") CmdResult(0, "HKEY_CU\\...\\ZAPP    REG_SZ    c:\\zapp\\ZAPP.EXE") else CmdResult(0, "")
        }
        assertTrue(windows(present, exe).status(), "путь сверяется без учёта регистра")

        val stale = FakeCmd { CmdResult(0, "REG_SZ    C:\\Old\\other.exe") }
        assertFalse(windows(stale, exe).status())

        val missing = FakeCmd { CmdResult(1, "") }
        assertFalse(windows(missing, exe).status())
    }

    @Test
    fun windowsDisableSucceedsWhenMissing() {
        val absent = FakeCmd { CmdResult(1, "ERROR: не найдено") }
        assertTrue(windows(absent, "C:\\ZAPP\\zapp.exe").disable(), "удалять нечего -- цель достигнута")
        assertEquals(2, absent.calls.size, "после неудачного удаления -- проверка, а не повтор")

        val present = FakeCmd()
        assertTrue(windows(present, "C:\\ZAPP\\zapp.exe").disable())
        assertEquals("delete", present.calls.single()[1])
    }

    // --- macOS: Login Items ---

    @Test
    fun macEnableUsesAppBundlePath() {
        val cmd = FakeCmd()

        assertTrue(mac(cmd, "/Applications/ZAPP.app/Contents/MacOS/zapp").enable())

        val script = cmd.calls.single().last()
        assertContains(script, "make login item")
        assertContains(script, "path:\"/Applications/ZAPP.app\"")
        assertContains(script, "hidden:true")
    }

    @Test
    fun macStatusParsesExists() {
        val exe = "/Applications/ZAPP.app/Contents/MacOS/zapp"
        val yes = FakeCmd { CmdResult(0, "true\n") }
        assertTrue(mac(yes, exe).status())
        assertContains(yes.calls.single().last(), "exists login item \"ZAPP\"")

        val no = FakeCmd { CmdResult(0, "false\n") }
        assertFalse(mac(no, exe).status())

        val denied = FakeCmd { CmdResult(1, "") }
        assertFalse(mac(denied, exe).status())
    }

    @Test
    fun macDisableDeletesByName() {
        val cmd = FakeCmd()
        assertTrue(mac(cmd, "/Applications/ZAPP.app/Contents/MacOS/zapp").disable())
        val script = cmd.calls.single().last()
        assertContains(script, "delete login item \"ZAPP\"")
        assertEquals(1, cmd.calls.size, "удаление прошло -- статус не переспрашиваем")
    }

    @Test
    fun macDisableSucceedsWhenMissing() {
        val cmd = FakeCmd { argv ->
            if ("exists" in argv.last()) CmdResult(0, "false\n") else CmdResult(1, "нет такого")
        }
        assertTrue(mac(cmd, "/Applications/ZAPP.app/Contents/MacOS/zapp").disable())
        assertEquals(2, cmd.calls.size)
    }

    // --- Общее ---

    @Test
    fun enableWithoutExePathMakesNoCalls() {
        for (os in listOf("Linux", "Windows 11", "Mac OS X")) {
            val cmd = FakeCmd()
            val engine = AutostartEngine(
                cmd = cmd, osName = os, exePath = { null }, autostartFile = { tempEntry() }
            )
            assertFalse(engine.enable(), "$os: без пути регистрации нет")
            assertTrue(cmd.calls.isEmpty(), "$os: ни одной команды")
        }
    }
}
