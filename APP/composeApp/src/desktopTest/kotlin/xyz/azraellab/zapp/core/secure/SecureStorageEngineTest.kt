package xyz.azraellab.zapp.core.secure

import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.azraellab.zapp.core.CmdResult
import xyz.azraellab.zapp.core.CmdRunner

/**
 * Движок секретов: формат команд DPAPI/secret-tool/security, разбор вывода
 * и честные отказы. Ничего реального не трогается: команды подменены,
 * файлы слотов пишутся во временный каталог.
 */
class SecureStorageEngineTest {

    private class FakeCmd(
        private val handler: (List<String>) -> CmdResult = { CmdResult(0, "") }
    ) : CmdRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>): CmdResult {
            calls += argv
            return handler(argv)
        }
    }

    private fun tempDir(): File = createTempDirectory("zapp-secrets").toFile()

    private fun windows(cmd: FakeCmd, dir: File) =
        SecureStorageEngine(cmd = cmd, dir = { dir }, osName = "Windows 11")

    private fun linux(
        cmd: FakeCmd,
        dir: File,
        stdin: (List<String>, String) -> CmdResult = { _, _ -> CmdResult(0, "") }
    ) = SecureStorageEngine(cmd = cmd, dir = { dir }, osName = "Linux", stdin = stdin)

    private fun mac(cmd: FakeCmd, dir: File) =
        SecureStorageEngine(cmd = cmd, dir = { dir }, osName = "Mac OS X")

    // --- Windows: DPAPI ---

    @Test
    fun windowsPutProtectsAndWritesFile() {
        val cmd = FakeCmd { CmdResult(0, "ENC64==\n") }
        val dir = tempDir()

        assertTrue(windows(cmd, dir).put("vpn:p1", "SECRET"))

        val script = cmd.calls.single().joinToString(" ")
        assertContains(script, "powershell")
        assertContains(script, "ProtectedData]::Protect")
        assertContains(script, "CurrentUser")
        val encoded = Base64.getEncoder().encodeToString("SECRET".toByteArray())
        assertContains(script, encoded)
        assertEquals("ENC64==", File(dir, "vpn_p1.dpapi").readText())
    }

    @Test
    fun windowsAvailableWithoutAnyCalls() {
        val cmd = FakeCmd()
        assertTrue(windows(cmd, tempDir()).available, "DPAPI есть у любой учётной записи")
        assertTrue(cmd.calls.isEmpty())
    }

    @Test
    fun windowsGetUnprotectsAndReturns() {
        val cmd = FakeCmd { argv ->
            if ("Unprotect" in argv.last()) CmdResult(0, "{\"privateKey\":\"PRIV\"}\n") else CmdResult(0, "")
        }
        val dir = tempDir()
        File(dir, "vpn_p1.dpapi").writeText("ENC64==")

        assertEquals("{\"privateKey\":\"PRIV\"}", windows(cmd, dir).get("vpn:p1"))
        assertContains(cmd.calls.single().joinToString(" "), "ProtectedData]::Unprotect")
    }

    @Test
    fun windowsGetWithoutFileIsNull() {
        val cmd = FakeCmd()
        assertNull(windows(cmd, tempDir()).get("vpn:p1"))
        assertTrue(cmd.calls.isEmpty())
    }

    @Test
    fun windowsRemoveDeletesFile() {
        val dir = tempDir()
        File(dir, "vpn_p1.dpapi").writeText("ENC64==")
        val cmd = FakeCmd()

        windows(cmd, dir).remove("vpn:p1")

        assertFalse(File(dir, "vpn_p1.dpapi").exists())
        assertTrue(cmd.calls.isEmpty(), "удаление -- просто файл, без команд")
    }

    @Test
    fun windowsSlotNameIsSanitized() {
        val cmd = FakeCmd { CmdResult(0, "ENC64==\n") }
        val dir = tempDir()

        assertTrue(windows(cmd, dir).put("../враг: name", "v"))

        val files = requireNotNull(dir.listFiles())
        assertEquals(1, files.size)
        assertTrue(
            files.single().name.matches(Regex("[A-Za-z0-9._-]+\\.dpapi")),
            "имя файла только из безопасных символов: ${files.single().name}"
        )
    }

    @Test
    fun windowsValueNeverLiesIntoScriptRaw() {
        val cmd = FakeCmd { CmdResult(0, "ENC64==\n") }
        val value = "{\n\"privateKey\": \"it's mine\"\n}"

        assertTrue(windows(cmd, tempDir()).put("vpn:p1", value))

        val script = cmd.calls.single().last()
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray())
        assertContains(script, encoded)
        assertFalse(script.contains("it's mine"), "значение в скрипт идёт только кодированным")
    }

    // --- Linux: Secret Service ---

    @Test
    fun linuxPutFeedsSecretViaStdin() {
        val cmd = FakeCmd()
        val fed = mutableListOf<Pair<List<String>, String>>()
        val engine = linux(cmd, tempDir(), stdin = { argv, input ->
            fed += argv to input
            CmdResult(0, "")
        })

        assertTrue(engine.put("vpn:p1", "SECRET"))

        val argv = fed.single().first
        assertEquals(listOf("secret-tool", "store", "--label=ZAPP", "key", "vpn:p1"), argv)
        assertEquals("SECRET", fed.single().second, "secret-tool берёт секрет из stdin")
        assertContains(cmd.calls, listOf("secret-tool", "--help"))
    }

    @Test
    fun linuxGetLooksUpSlot() {
        val cmd = FakeCmd { argv ->
            if (argv.getOrNull(1) == "lookup") CmdResult(0, "SECRET\n") else CmdResult(0, "")
        }

        assertEquals("SECRET", linux(cmd, tempDir()).get("vpn:p1"))
        assertContains(cmd.calls, listOf("secret-tool", "lookup", "key", "vpn:p1"))
    }

    @Test
    fun linuxWithoutSecretToolIsHonestNo() {
        val cmd = FakeCmd { argv ->
            if (argv == listOf("secret-tool", "--help")) CmdResult(-1, "") else CmdResult(0, "")
        }
        val engine = linux(cmd, tempDir(), stdin = { _, _ -> kotlin.test.fail("без утилиты вызовов быть не должно") })

        assertFalse(engine.available)
        assertFalse(engine.put("vpn:p1", "SECRET"))
        assertNull(engine.get("vpn:p1"))
        assertEquals(listOf(listOf("secret-tool", "--help")), cmd.calls, "только проба утилиты")
    }

    @Test
    fun linuxRemoveClearsSlot() {
        val cmd = FakeCmd()
        linux(cmd, tempDir()).remove("vpn:p1")
        assertContains(cmd.calls, listOf("secret-tool", "clear", "key", "vpn:p1"))
    }

    // --- macOS: Keychain ---

    @Test
    fun macPutUsesKeychainCli() {
        val cmd = FakeCmd()
        assertTrue(mac(cmd, tempDir()).put("vpn:p1", "SECRET"))
        assertEquals(
            listOf("security", "add-generic-password", "-s", "ZAPP", "-a", "vpn:p1", "-w", "SECRET", "-U"),
            cmd.calls.single()
        )
    }

    @Test
    fun macGetReadsPassword() {
        val cmd = FakeCmd { argv ->
            if ("find-generic-password" in argv) CmdResult(0, "SECRET\n") else CmdResult(0, "")
        }
        assertEquals("SECRET", mac(cmd, tempDir()).get("vpn:p1"))
        assertContains(cmd.calls.single(), "-w")
    }

    @Test
    fun macRemoveDeletesEntry() {
        val cmd = FakeCmd()
        mac(cmd, tempDir()).remove("vpn:p1")
        assertEquals(
            listOf("security", "delete-generic-password", "-s", "ZAPP", "-a", "vpn:p1"),
            cmd.calls.single()
        )
    }
}
