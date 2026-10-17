package xyz.azraellab.zapp.core.connections

import xyz.azraellab.zapp.core.CmdRunner
import xyz.azraellab.zapp.core.ProcessCmd

/**
 * Десктоп: монитор соединений -- свой Rust-демон zappmon с
 * protobuf-IPC (сам читает /proc, без спавна утилит на каждый опрос).
 * Бинарь собирается под хост (native/build-mon.sh); на Windows и macOS
 * его нет, как и unix-таблиц -- там остаётся прежний опрос: ss, lsof,
 * PowerShell. Если демон вдруг замолчал, [FallbackConnectionsSource]
 * откатывает на опрос молча и без пустых таблиц.
 */
actual fun createConnectionsSource(): ConnectionsSource {
    val polling = DesktopConnectionsSource(cmd = ProcessCmd)
    if (!System.getProperty("os.name").orEmpty().contains("linux", true)) return polling
    val mon = MonConnectionsSource(connect = { ZappMon.connect() })
    return FallbackConnectionsSource(primary = mon, fallback = polling)
}

/**
 * Десктоп: таблицы соединений системными утилитами.
 *
 * Linux -- `ss` (ядро само отдаёт и процесс из inode сокета), macOS --
 * `lsof` полем вывода -F, Windows -- PowerShell: Get-NetTCPConnection
 * и Get-NetUDPEndpoint с именем процесса по Id. Ветки командные, вся
 * механика считается тестами с подставным раннером.
 */
internal class DesktopConnectionsSource(
    private val cmd: CmdRunner,
    private val osName: String = System.getProperty("os.name").orEmpty()
) : ConnectionsSource {

    private val windows get() = osName.contains("win", true)
    private val macos get() = osName.contains("mac", true)

    override fun read(): ConnectionsSample = when {
        windows -> windowsRead()
        macos -> macRead()
        else -> linuxRead()
    }

    private fun linuxRead(): ConnectionsSample {
        val result = runCatching { cmd.run(listOf("ss", "-H", "-tunap")) }.getOrNull()
            ?: return ConnectionsSample(emptyList(), ConnNote.NO_TOOL)
        if (!result.ok) return ConnectionsSample(emptyList(), ConnNote.NO_TOOL)
        return ConnectionsSample(SsParser.parse(result.output))
    }

    private fun windowsRead(): ConnectionsSample {
        val result = runCatching { cmd.run(listOf(POWERSHELL, "-NoProfile", "-Command", WINDOWS_SCRIPT)) }.getOrNull()
            ?: return ConnectionsSample(emptyList(), ConnNote.NO_TOOL)
        if (!result.ok) return ConnectionsSample(emptyList(), ConnNote.NO_TOOL)
        return ConnectionsSample(PsCsvParser.parse(result.output))
    }

    private fun macRead(): ConnectionsSample {
        val result = runCatching { cmd.run(listOf("lsof", "-nP", "-iTCP", "-iUDP", "-Fpcnf")) }.getOrNull()
            ?: return ConnectionsSample(emptyList(), ConnNote.NO_TOOL)
        if (!result.ok) return ConnectionsSample(emptyList(), ConnNote.NO_TOOL)
        return ConnectionsSample(LsofParser.parse(result.output))
    }
}

/**
 * Один проход PowerShell: словарь «id -> имя процесса», затем строки
 * «proto|локальныйПорт|удалённый|удалённыйПорт|процесс». Формат свой,
 * чтобы разбор не зависел от локали вывода консоли.
 */
private val WINDOWS_SCRIPT = (
    "\$names = @{}; Get-Process | ForEach-Object { \$names[\$_.Id] = \$_.ProcessName }; " +
        "Get-NetTCPConnection | ForEach-Object { " +
        "\"tcp|{0}|{1}|{2}|{3}\" -f \$_.LocalPort, \$_.RemoteAddress, \$_.RemotePort, \$names[\$_.OwningProcess] " +
        "}; " +
        "Get-NetUDPEndpoint | ForEach-Object { " +
        "\"udp|{0}|-|-|{1}\" -f \$_.LocalPort, \$names[\$_.OwningProcess] " +
        "}"
    )

private const val POWERSHELL = "powershell"
