package xyz.azraellab.zapp.core.connections

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import xyz.azraellab.zapp.core.CmdResult
import xyz.azraellab.zapp.core.CmdRunner

/**
 * Источник десктопа: команды трёх систем и честная пометка, когда
 * утилита не запустилась. Реальные системы не трогаются -- команды
 * подменены.
 */
class DesktopConnectionsSourceTest {

    private class FakeCmd(
        private val handler: (List<String>) -> CmdResult = { CmdResult(0, "") }
    ) : CmdRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>): CmdResult {
            calls += argv
            return handler(argv)
        }
    }

    private fun source(cmd: FakeCmd, os: String) = DesktopConnectionsSource(cmd = cmd, osName = os)

    @Test
    fun linuxAsksSsAndParses() {
        val cmd = FakeCmd {
            CmdResult(0, "tcp ESTAB 0 0 192.168.1.8:49648 142.250.200.14:443")
        }
        val sample = source(cmd, "Linux").read()
        assertEquals(listOf("ss", "-H", "-tunap"), cmd.calls.single())
        assertEquals(ConnRow("tcp", 49648, "142.250.200.14:443", "-"), sample.rows.single())
        assertEquals(null, sample.note)
    }

    @Test
    fun linuxWithoutSsIsMarkedNotTool() {
        val cmd = FakeCmd { CmdResult(127, "ss: command not found") }
        val sample = source(cmd, "Linux").read()
        assertEquals(emptyList(), sample.rows)
        assertEquals(ConnNote.NO_TOOL, sample.note)
    }

    @Test
    fun linuxThrownRunnerIsMarkedNotTool() {
        val cmd = FakeCmd { throw IllegalStateException("нет бинарника") }
        val sample = source(cmd, "Linux").read()
        assertEquals(ConnNote.NO_TOOL, sample.note)
    }

    @Test
    fun windowsRunsPowerShellWithOwnCsv() {
        val cmd = FakeCmd {
            CmdResult(0, "tcp|49648|142.250.200.14|443|chrome\nudp|5353|0.0.0.0|0|mdnsresponder")
        }
        val sample = source(cmd, "Windows 11").read()

        val argv = cmd.calls.single()
        assertEquals("powershell", argv[0])
        assertContains(argv.last(), "Get-NetTCPConnection")
        assertContains(argv.last(), "Get-NetUDPEndpoint")
        assertEquals(2, sample.rows.size)
        assertEquals("chrome", sample.rows[0].owner)
        assertEquals(null, sample.note)
    }

    @Test
    fun windowsFailureIsMarkedNotTool() {
        val cmd = FakeCmd { CmdResult(1, "нет такого cmdlet") }
        assertEquals(ConnNote.NO_TOOL, source(cmd, "Windows 11").read().note)
    }

    @Test
    fun macRunsLsofFieldOutput() {
        val cmd = FakeCmd {
            // Формат -F: строки "тегЗначение", здесь c<имя процесса>
            CmdResult(0, "p1234\ncchrome\nf46t\nn192.168.1.8.49648->142.250.200.14.443 (ESTABLISHED)")
        }
        val sample = source(cmd, "Mac OS X").read()

        val argv = cmd.calls.single()
        assertEquals("lsof", argv[0])
        assertContains(argv, "-Fpcnf")
        assertEquals(ConnRow("tcp", 49648, "142.250.200.14:443", "chrome"), sample.rows.single())
    }

    @Test
    fun macFailureIsMarkedNotTool() {
        val cmd = FakeCmd { CmdResult(1, "") }
        assertEquals(ConnNote.NO_TOOL, source(cmd, "Mac OS X").read().note)
    }
}
