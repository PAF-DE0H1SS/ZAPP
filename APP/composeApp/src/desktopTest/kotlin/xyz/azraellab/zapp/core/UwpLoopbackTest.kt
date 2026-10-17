package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UwpLoopbackTest {

    private class FakeCmd(
        private val handler: (List<String>) -> CmdResult = { CmdResult(0, "") }
    ) : CmdRunner {
        val calls = mutableListOf<List<String>>()

        override fun run(argv: List<String>): CmdResult {
            calls += argv
            return handler(argv)
        }
    }

    private fun windows(cmd: CmdRunner) =
        UwpLoopbackEngine(cmd = cmd, osName = "Windows 11 Pro")

    @Test
    fun familiesComeFromRegFamilyKey() {
        val regOutput = """
            HKEY_CURRENT_USER\Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppModel\Repository\Families
                Microsoft.WindowsCalculator_8wekyb3d8bbwe
                Microsoft.WindowsMaps_8wekyb3d8bbwe

            HKEY_CURRENT_USER\Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppModel\Repository\Families\Microsoft.WindowsCalculator_8wekyb3d8bbwe
                PackageFamilyName    REG_SZ    Microsoft.WindowsCalculator_8wekyb3d8bbwe
        """.trimIndent().replace("\n", "\r\n")
        val cmd = FakeCmd { argv ->
            if (argv.firstOrNull() == "reg") CmdResult(0, regOutput) else CmdResult(1, "")
        }
        assertEquals(
            listOf("Microsoft.WindowsCalculator_8wekyb3d8bbwe", "Microsoft.WindowsMaps_8wekyb3d8bbwe"),
            windows(cmd).families()
        )
        assertTrue(
            cmd.calls.single().last().endsWith("Families"),
            "запрос должен идти в ключ семей: ${cmd.calls}"
        )
    }

    @Test
    fun parseFamiliesKeepsFirstLevelOnly() {
        val output = """
            HKEY_CURRENT_USER\...\Families
                First_pkg_abc
                Second_pkg_def
                First_pkg_abc
                        DeepValue    REG_SZ    nope

            HKEY_CURRENT_USER\...\Families\First_pkg_abc
                FamilyName    REG_SZ    First_pkg_abc
        """.trimIndent()
        val parsed = parseFamilies(output)
        // Значения вложенных уровней и REG_SZ-строки не семейства,
        // повтор строки не плодит дубль.
        assertEquals(listOf("First_pkg_abc", "Second_pkg_def"), parsed)
    }

    @Test
    fun parseFamiliesToleratesEmptyAndFailedOutput() {
        assertEquals(emptyList(), parseFamilies(""))
        assertEquals(emptyList(), parseFamilies("ERROR: The system was unable to find the registry key."))
        assertEquals(emptyList(), parseFamilies("   \r\n  \r\n"))
    }

    @Test
    fun exemptListParsesNamedEntries() {
        val output = """
            Loopback Exemption List

            Name: Microsoft.WindowsStore_8wekyb3d8bbwe
            Name: TelegramMessenger_abc123def456
            --------------------
            (no entries above this line)
        """.trimIndent()
        assertEquals(
            setOf("Microsoft.WindowsStore_8wekyb3d8bbwe", "TelegramMessenger_abc123def456"),
            parseExempt(output)
        )
    }

    @Test
    fun exemptParsesRawLinesAndLocalizedName() {
        // Строка без "Name:" и имя после локализованного двоеточия:
        // парсер смотрит на структуру, а не на слова языка системы.
        val output = """
            Plain_pkg_abc123
            Имя: МойПакет_9x8y7z
            просто текст без пакета
            ------------------
        """.trimIndent()
        assertEquals(
            setOf("Plain_pkg_abc123", "МойПакет_9x8y7z"),
            parseExempt(output)
        )
    }

    @Test
    fun setBuildsAddAndDeleteArgv() {
        val cmd = FakeCmd { CmdResult(0, "") }
        assertTrue(windows(cmd).set("MyApp_hash", on = true))
        assertTrue(windows(cmd).set("MyApp_hash", on = false))
        assertEquals(
            listOf("CheckNetIsolation", "LoopbackExempt", "-a", "-p=MyApp_hash"),
            cmd.calls[0]
        )
        assertEquals(
            listOf("CheckNetIsolation", "LoopbackExempt", "-d", "-p=MyApp_hash"),
            cmd.calls[1]
        )
    }

    @Test
    fun setReportsFailureAndBlankName() {
        val cmd = FakeCmd { if (it.last().startsWith("-p=x")) CmdResult(1, "denied") else CmdResult(0, "") }
        assertFalse(windows(cmd).set("x", on = true), "ненулевой код -- не успех")
        val idle = FakeCmd { error("команды не должны уходить") }
        assertFalse(windows(idle).set("  ", on = true), "пустое имя не может уйти в утилиту")
        assertTrue(idle.calls.isEmpty())
    }

    @Test
    fun nonWindowsRunsNothing() {
        val cmd = FakeCmd { error("на не-Windows внешних команд быть не должно") }
        val engine = UwpLoopbackEngine(cmd = cmd, osName = "Linux")
        assertEquals(emptyList(), engine.families())
        assertEquals(emptySet<String>(), engine.exempt())
        assertFalse(engine.set("App_hash", on = true))
        assertTrue(cmd.calls.isEmpty())
    }

    @Test
    fun commandFailureYieldsEmptyNotCrash() {
        val cmd = FakeCmd { CmdResult(1, "access denied") }
        assertEquals(emptyList(), windows(cmd).families())
        assertEquals(emptySet<String>(), windows(cmd).exempt())
    }
}
