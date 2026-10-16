package xyz.azraellab.zapp.core

import java.io.File

actual object AutostartCtl {

    private val engine by lazy {
        AutostartEngine(
            cmd = ProcessCmd,
            exePath = { currentExePath() },
            autostartFile = { defaultAutostartFile() }
        )
    }

    actual val supported: Boolean get() = true

    actual fun status(): Boolean = runCatching { engine.status() }.getOrDefault(false)
    actual fun enable(): Boolean = runCatching { engine.enable() }.getOrDefault(false)
    actual fun disable(): Boolean = runCatching { engine.disable() }.getOrDefault(false)
}

/**
 * Движок регистрации автозапуска с подставными зависимостями: команды
 * ОС, путь лаунчера и файл автозапуска подменяются тестами.
 *
 * Windows -- Run-ключ HKCU (командой `reg`: коды возврата не зависят
 * от языка системы, а `reg query` дописывает данные в stdout, так что
 * путь проверяется в ответе). Linux -- файл в автостарте XDG, Exec
 * кавычится, если путь с пробелами. macOS -- запись в Login Items
 * через `osascript`: путь у Login Items -- .app-бандл, а не лаунчер
 * внутри него, и берётся бандл.
 *
 * Во всех трёх проверка статуса сравнивает с ТЕКУЩИМ путём: запись от
 * старой версии в другом каталоге считается неактивной, тогда как
 * выключение снимает её в любом случае.
 */
internal class AutostartEngine(
    private val cmd: CmdRunner,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val exePath: () -> String?,
    private val autostartFile: () -> File?
) {

    private val windows get() = osName.contains("win", true)
    private val macos get() = osName.contains("mac", true)

    fun status(): Boolean = when {
        windows -> windowsStatus()
        macos -> macStatus()
        else -> linuxStatus()
    }

    fun enable(): Boolean {
        val exe = exePath() ?: return false
        return when {
            windows -> cmd.run(
                listOf("reg", "add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", exe, "/f")
            ).ok
            macos -> macEnable(exe)
            else -> linuxEnable(exe)
        }
    }

    fun disable(): Boolean = when {
        windows -> windowsDisable()
        macos -> macDisable()
        else -> linuxDisable()
    }

    private fun windowsStatus(): Boolean {
        val exe = exePath() ?: return false
        val result = cmd.run(listOf("reg", "query", RUN_KEY, "/v", RUN_VALUE))
        return result.ok && exe.lowercase() in result.output.lowercase()
    }

    /** Удаление без записи -- не ошибка: записать было нечего. */
    private fun windowsDisable(): Boolean {
        val removed = cmd.run(listOf("reg", "delete", RUN_KEY, "/v", RUN_VALUE, "/f"))
        return removed.ok || !cmd.run(listOf("reg", "query", RUN_KEY, "/v", RUN_VALUE)).ok
    }

    private fun linuxEnable(exe: String): Boolean {
        val file = autostartFile() ?: return false
        val exec = if (' ' in exe) "\"$exe\"" else exe
        val content = buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=ZAPP")
            appendLine("Exec=$exec")
            appendLine("X-GNOME-Autostart-enabled=true")
        }
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        }.getOrDefault(false)
    }

    private fun linuxStatus(): Boolean {
        val exe = exePath() ?: return false
        val file = autostartFile() ?: return false
        return runCatching {
            file.isFile && exe.lowercase() in file.readText().lowercase()
        }.getOrDefault(false)
    }

    private fun linuxDisable(): Boolean {
        val file = autostartFile() ?: return true
        return runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    }

    private fun macEnable(exe: String): Boolean {
        val path = macAppPath(exe)
        val script = "tell application \"System Events\" to make login item at end with properties " +
            "{path:\"${appleEsc(path)}\", hidden:true}"
        return cmd.run(listOf("osascript", "-e", script)).ok
    }

    private fun macStatus(): Boolean {
        val script = "tell application \"System Events\" to exists login item \"${appleEsc(macItemName())}\""
        val result = cmd.run(listOf("osascript", "-e", script))
        return result.ok && "true" in result.output.lowercase()
    }

    private fun macDisable(): Boolean {
        val script = "tell application \"System Events\" to delete login item \"${appleEsc(macItemName())}\""
        val removed = cmd.run(listOf("osascript", "-e", script))
        return removed.ok || !macStatus()
    }

    private fun macAppPath(exe: String): String {
        val idx = exe.indexOf(".app/", ignoreCase = true)
        return if (idx >= 0) exe.substring(0, idx + ".app".length) else exe
    }

    private fun macItemName(): String =
        exePath()?.let { File(macAppPath(it)).nameWithoutExtension } ?: LOGIN_ITEM

    private fun appleEsc(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
private const val RUN_VALUE = "ZAPP"
private const val LOGIN_ITEM = "ZAPP"
private const val ENTRY_NAME = "zapp.desktop"

/** Путь лаунчера: у установленного приложения -- свой exe, не java из JRE. */
private fun currentExePath(): String? {
    runCatching {
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return runCatching { ProcessHandle.current().info().command().orElse(null) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

private fun defaultAutostartFile(): File? {
    val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { "$it/.config" }
        ?: return null
    return File(config, "autostart/$ENTRY_NAME")
}
